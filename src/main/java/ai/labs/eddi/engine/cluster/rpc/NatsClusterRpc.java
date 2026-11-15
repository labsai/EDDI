/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import ai.labs.eddi.engine.cluster.ClusterMessageSigner;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * {@link IClusterRpc} on core NATS request-reply. Handlers run on virtual
 * threads, never on the NATS dispatcher.
 * <p>
 * <b>Signed (C4).</b> With {@code eddi.cluster.signing.key} every request
 * carries an HMAC over its subject, a timestamp, a nonce and its body
 * ({@link ClusterMessageSigner}); the receiving node refuses — without a reply
 * — a request with an invalid signature, one older than
 * {@code eddi.cluster.signing.max-age}, a nonce it has seen, and, with
 * {@code eddi.cluster.signing.required=true}, an unsigned one. A reply is
 * signed over the nonce of the request it answers, and the caller drops a reply
 * that does not verify, so a forged answer cannot fake, say, a GDPR stop. Every
 * request and reply carries the payload version ({@value #PAYLOAD_VERSION}) in
 * the {@code Eddi-V} header.
 */
@ApplicationScoped
// ClusterStartable as well: ClusterBootstrap finds what to start through
// Instance<ClusterStartable>, and a bean typed to its own class only is
// invisible
// there — this one would never subscribe.
@Typed({NatsClusterRpc.class, ClusterStartable.class})
public class NatsClusterRpc implements IClusterRpc, ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(NatsClusterRpc.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    /** The payload format of requests and replies (C15). */
    public static final int PAYLOAD_VERSION = 1;

    private final NatsConnectionManager connections;
    private final ClusterPresence presence;
    private final MeterRegistry meterRegistry;
    private final ClusterSubjects subjects;
    private final Duration timeout;
    private final ClusterMessageSigner signer;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean newerVersionWarned = new AtomicBoolean();
    private volatile Connection subscribedOn;

    @Inject
    public NatsClusterRpc(NatsConnectionManager connections, ClusterPresence presence, MeterRegistry meterRegistry,
            ClusterMessageSigner signer) {
        this.connections = connections;
        this.presence = presence;
        this.meterRegistry = meterRegistry;
        this.subjects = new ClusterSubjects(connections.config().natsPrefix());
        this.timeout = connections.config().natsRequestTimeout();
        this.signer = signer;
    }

    /** For tests and the in-JVM cluster ITs: unsigned. */
    public NatsClusterRpc(NatsConnectionManager connections, ClusterPresence presence, MeterRegistry meterRegistry) {
        this(connections, presence, meterRegistry, ClusterMessageSigner.unsigned());
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @Override
    public void startCluster() {
        signer.logMode();
        connections.onConnected(this::subscribe);
    }

    synchronized void subscribe() {
        Connection connection;
        try {
            connection = connections.requireConnected();
        } catch (RuntimeException e) {
            LOGGER.warnf("Node RPC not subscribed — retried on the next connect: %s", e.getMessage());
            return;
        }
        if (subscribedOn == connection) {
            return;
        }
        Dispatcher dispatcher = null;
        try {
            dispatcher = connection.createDispatcher(this::onRequest);
            dispatcher.subscribe(subjects.rpcNodeWildcard(connections.node().nodeId()));
            dispatcher.subscribe(subjects.rpcAllWildcard());
            subscribedOn = connection;
        } catch (RuntimeException e) {
            LOGGER.warnf("Node RPC not subscribed — retried on the next connect: %s", e.getMessage());
            if (dispatcher != null) {
                try {
                    connection.closeDispatcher(dispatcher);
                } catch (RuntimeException ignored) {
                    // the connection is going away anyway
                }
            }
        }
    }

    private void onRequest(Message msg) {
        handleRequest(msg.getSubject(), msg.getReplyTo(), msg.hasHeaders() ? msg.getHeaders() : null, msg.getData());
    }

    /**
     * Handles one request. Package-private for tests.
     *
     * @return whether the request was accepted (it verified, or signing allows it)
     */
    boolean handleRequest(String subject, String replyTo, Headers headers, byte[] data) {
        String op = subject.substring(subject.lastIndexOf('.') + 1);
        ClusterMessageSigner.Verdict verdict = signer.verify("rpc", subject, headers, data, signer.maxAge(), true);
        if (!verdict.accepted()) {
            // No reply: an unverified caller learns nothing, and the real caller's
            // timeout is what an unreachable node looks like anyway.
            return false;
        }
        noteVersion(headers, "rpc");
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("node", connections.node().nodeId());
        Function<Map<String, Object>, Map<String, Object>> handler = handlers.get(op);
        if (handler == null) {
            reply.put("error", "no handler for " + op);
        }
        workers.execute(() -> {
            if (handler != null) {
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
                    byte[] body = JSON.writeValueAsBytes(reply);
                    Headers replyHeaders = signer.sign(ClusterMessageSigner.replySubject(verdict.nonce()), body, null, PAYLOAD_VERSION);
                    connections.requireConnected().publish(replyTo, replyHeaders, body);
                } catch (IOException | ClusterUnavailableException e) {
                    LOGGER.debugf("RPC reply for %s not sent: %s", op, e.getMessage());
                }
            }
        });
        return true;
    }

    private void noteVersion(Headers headers, String kind) {
        int version = ClusterMessageSigner.versionOf(headers);
        if (version > PAYLOAD_VERSION) {
            meterRegistry.counter("eddi.cluster.payload.newer", "kind", kind).increment();
            if (newerVersionWarned.compareAndSet(false, true)) {
                LOGGER.warnf("Received a cluster %s of payload version %d; this node understands up to %d. A newer EDDI version is "
                        + "in the cluster (rolling upgrade?) — handled on a best-effort basis.", kind, version, PAYLOAD_VERSION);
            }
        }
    }

    /**
     * Verifies a reply against the request it answers; null when it must be
     * dropped.
     */
    private Map<String, Object> readReply(Message reply, Headers request) throws IOException {
        Headers headers = reply.hasHeaders() ? reply.getHeaders() : null;
        if (!signer.verify("rpc-reply", ClusterMessageSigner.replySubject(ClusterMessageSigner.nonceOf(request)), headers,
                reply.getData(), signer.maxAge(), false).accepted()) {
            return null;
        }
        noteVersion(headers, "rpc-reply");
        return JSON.readValue(reply.getData(), MAP);
    }

    @Override
    public Optional<Map<String, Object>> call(String nodeId, String op, Map<String, Object> request) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            String subject = subjects.rpc(nodeId, op);
            byte[] body = JSON.writeValueAsBytes(request);
            Headers headers = signer.sign(subject, body, null, PAYLOAD_VERSION);
            Message reply = connections.requireConnected().request(subject, headers, body, timeout);
            if (reply == null) {
                outcome = "timeout";
                return Optional.empty();
            }
            Map<String, Object> verified = readReply(reply, headers);
            if (verified == null) {
                outcome = "rejected";
                return Optional.empty();
            }
            return Optional.of(verified);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            outcome = "unavailable";
            LOGGER.debugf("RPC %s to %s failed: %s", LogSanitizer.sanitize(op), LogSanitizer.sanitize(nodeId), e.getMessage());
            return Optional.empty();
        } finally {
            record(op, outcome, start);
        }
    }

    @Override
    public Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request) {
        return callAll(op, request, timeout, cachedPeers());
    }

    @Override
    public Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request, Duration wait, int expected) {
        long start = System.nanoTime();
        Map<String, Map<String, Object>> replies = new LinkedHashMap<>();
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
            String subject = subjects.rpcAll(op);
            byte[] body = JSON.writeValueAsBytes(request);
            Headers headers = signer.sign(subject, body, null, PAYLOAD_VERSION);
            connection.publish(subject, inbox, headers, body);
            long deadline = System.nanoTime() + wait.toNanos();
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
                Map<String, Object> reply;
                try {
                    reply = readReply(msg, headers);
                } catch (IOException unreadable) {
                    // One bad reply must not cost the replies of every other node.
                    LOGGER.debugf("Unreadable RPC scatter reply for %s: %s", op, unreadable.getMessage());
                    continue;
                }
                if (reply == null) {
                    continue; // did not verify: not an answer from a node of this cluster
                }
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

    /**
     * Read now, uncached: an unavailable presence bucket throws
     * {@link ClusterUnavailableException} instead of answering "no peers", so a
     * caller that must not accept a silent cluster (GDPR erasure) fails closed. The
     * remote nodes are counted by excluding this node's own record when it is
     * present, not by subtracting one regardless.
     */
    @Override
    public int expectedPeers() {
        String self = connections.node().nodeId();
        int remote = 0;
        for (Map<String, Object> member : presence.currentMembers()) {
            if (!self.equals(String.valueOf(member.get("node")))) {
                remote++;
            }
        }
        return remote;
    }

    /**
     * The cached count {@link #callAll} uses to stop waiting early; best effort,
     * never throws.
     */
    private int cachedPeers() {
        String self = connections.node().nodeId();
        int remote = 0;
        for (Map<String, Object> member : presence.members()) {
            if (!self.equals(String.valueOf(member.get("node")))) {
                remote++;
            }
        }
        return remote;
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
