/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * {@link IClusterRpc} on core NATS request-reply. Handlers run on virtual
 * threads, never on the NATS dispatcher.
 */
@ApplicationScoped
@Typed(NatsClusterRpc.class)
public class NatsClusterRpc implements IClusterRpc, ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(NatsClusterRpc.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final NatsConnectionManager connections;
    private final ClusterPresence presence;
    private final MeterRegistry meterRegistry;
    private final ClusterSubjects subjects;
    private final Duration timeout;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Connection subscribedOn;

    @Inject
    public NatsClusterRpc(NatsConnectionManager connections, ClusterPresence presence, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.presence = presence;
        this.meterRegistry = meterRegistry;
        this.subjects = new ClusterSubjects(connections.config().natsPrefix());
        this.timeout = connections.config().natsRequestTimeout();
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @Override
    public void startCluster() {
        connections.onConnected(this::subscribe);
    }

    private synchronized void subscribe() {
        Connection connection = connections.requireConnected();
        if (subscribedOn == connection) {
            return;
        }
        Dispatcher dispatcher = connection.createDispatcher(this::onRequest);
        dispatcher.subscribe(subjects.rpcNodeWildcard(connections.node().nodeId()));
        dispatcher.subscribe(subjects.rpcAllWildcard());
        subscribedOn = connection;
    }

    private void onRequest(Message msg) {
        String subject = msg.getSubject();
        String op = subject.substring(subject.lastIndexOf('.') + 1);
        String replyTo = msg.getReplyTo();
        byte[] data = msg.getData();
        workers.execute(() -> {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("node", connections.node().nodeId());
            Function<Map<String, Object>, Map<String, Object>> handler = handlers.get(op);
            if (handler == null) {
                reply.put("error", "no handler for " + op);
            } else {
                try {
                    Map<String, Object> request = data == null || data.length == 0 ? Map.of() : JSON.readValue(data, MAP);
                    Map<String, Object> result = handler.apply(request);
                    if (result != null) {
                        reply.putAll(result);
                    }
                } catch (IOException | RuntimeException e) {
                    reply.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (replyTo != null) {
                try {
                    connections.requireConnected().publish(replyTo, JSON.writeValueAsBytes(reply));
                } catch (IOException | ClusterUnavailableException e) {
                    LOGGER.debugf("RPC reply for %s not sent: %s", op, e.getMessage());
                }
            }
        });
    }

    @Override
    public Optional<Map<String, Object>> call(String nodeId, String op, Map<String, Object> request) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            Message reply = connections.requireConnected()
                    .request(subjects.rpc(nodeId, op), JSON.writeValueAsBytes(request), timeout);
            if (reply == null) {
                outcome = "timeout";
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(reply.getData(), MAP));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            outcome = "unavailable";
            LOGGER.debugf("RPC %s to %s failed: %s", op, nodeId, e.getMessage());
            return Optional.empty();
        } finally {
            record(op, outcome, start);
        }
    }

    @Override
    public Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request) {
        long start = System.nanoTime();
        Map<String, Map<String, Object>> replies = new LinkedHashMap<>();
        int expected = Math.max(0, presence.members().size() - 1);
        String outcome = "ok";
        Connection connection;
        try {
            connection = connections.requireConnected();
        } catch (ClusterUnavailableException e) {
            record(op, "unavailable", start);
            return replies;
        }
        String inbox = connection.createInbox();
        Subscription sub = connection.subscribe(inbox);
        try {
            connection.publish(subjects.rpcAll(op), inbox, JSON.writeValueAsBytes(request));
            long deadline = System.nanoTime() + timeout.toNanos();
            String self = connections.node().nodeId();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || (expected > 0 && replies.size() >= expected)) {
                    break;
                }
                Message msg = sub.nextMessage(Duration.ofNanos(remaining));
                if (msg == null) {
                    break;
                }
                Map<String, Object> reply = JSON.readValue(msg.getData(), MAP);
                String from = String.valueOf(reply.get("node"));
                if (!self.equals(from)) {
                    replies.put(from, reply);
                }
            }
            if (expected > 0 && replies.size() < expected) {
                outcome = "partial";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
        } catch (IOException | RuntimeException e) {
            outcome = "unavailable";
            LOGGER.debugf("RPC scatter %s failed: %s", op, e.getMessage());
        } finally {
            try {
                sub.unsubscribe();
            } catch (RuntimeException ignored) {
                // connection gone
            }
            record(op, outcome, start);
        }
        return replies;
    }

    @Override
    public void handle(String op, Function<Map<String, Object>, Map<String, Object>> handler) {
        handlers.put(op, handler);
    }

    private void record(String op, String outcome, long start) {
        Timer.builder("eddi.cluster.rpc").tag("op", op).tag("outcome", outcome).register(meterRegistry)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
}
