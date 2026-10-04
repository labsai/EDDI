/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.admin;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActivityEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.PublishOptions;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/**
 * The cluster activity timeline: what happened in the cluster, in the order the
 * nodes saw it — nodes joining, leaving and getting lost, leases taken over,
 * fenced writes and other dead letters, degraded mode on and off, deployments
 * reaching each node, cache invalidations (aggregated per minute) and every
 * administrative recovery action.
 * <p>
 * <b>One timeline for the whole cluster.</b> Every entry is published to the
 * JetStream stream {@code <prefix>_ACTIVITY} (subjects
 * {@code eddi.<prefix>.ops.>}, kept for {@code eddi.cluster.activity.max-age},
 * at most 10,000 entries) and every node reads that stream back into a bounded
 * ring of the latest {@value #RING_SIZE}. Whichever node the load balancer
 * picks therefore answers with the same timeline. An observation several nodes
 * make (a node lost) carries a deterministic id that is also the JetStream
 * message id, so the stream's duplicate window stores it once.
 * <p>
 * <b>Degraded.</b> While NATS is unreachable entries are kept in the local ring
 * and in a small outbox ({@value #OUTBOX_SIZE}) that is published on reconnect;
 * the timeline of a single node (in-memory mode) holds that node's admin
 * actions only.
 * <p>
 * <b>Payloads carry ids only</b> — conversation, agent, node and entry ids,
 * counts and reasons; never a message, a user id or a secret.
 */
@ApplicationScoped
public class ClusterActivityLog implements ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(ClusterActivityLog.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int RING_SIZE = 500;
    static final int OUTBOX_SIZE = 200;
    static final long OUTBOX_RETRY_SECONDS = 5;
    static final long MAX_STREAM_MESSAGES = 10_000;
    /** Live subscribers (SSE clients) per node. */
    static final int MAX_LISTENERS = 16;

    public static final String INFO = "info";
    public static final String WARNING = "warning";
    public static final String ERROR = "error";

    private final ClusterConfig config;
    private final NodeIdentity node;
    private final Instance<NatsConnectionManager> connections;
    private final Duration maxAge;

    private final Deque<ActivityEvent> ring = new ArrayDeque<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private final Deque<ActivityEvent> outbox = new ArrayDeque<>();
    private final List<Consumer<ActivityEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile Connection subscribedOn;

    @Inject
    public ClusterActivityLog(ClusterConfig config, NodeIdentity node, Instance<NatsConnectionManager> connections,
            @ConfigProperty(name = "eddi.cluster.activity.max-age", defaultValue = "24h") Duration maxAge) {
        this.config = config;
        this.node = node;
        this.connections = connections;
        this.maxAge = maxAge;
    }

    // ---------------------------------------------------------------- record

    /**
     * Records an observation of this node.
     *
     * @param dedupKey
     *            the id of an observation other nodes may make too (a node lost),
     *            so the cluster stores it once; {@code null} for a unique one
     */
    public ActivityEvent record(String type, String severity, Map<String, Object> payload, String dedupKey) {
        ActivityEvent event = new ActivityEvent(dedupKey != null ? dedupKey : UUID.randomUUID().toString(), type, severity, node.nodeId(),
                System.currentTimeMillis(), payload == null ? Map.of() : new LinkedHashMap<>(payload));
        if (accept(event)) {
            publish(event);
        }
        return event;
    }

    /** Adds an entry to the ring and tells the listeners; false for a duplicate. */
    boolean accept(ActivityEvent event) {
        synchronized (ring) {
            if (!seen.add(event.id())) {
                return false;
            }
            ring.addLast(event);
            while (ring.size() > RING_SIZE) {
                ActivityEvent dropped = ring.pollFirst();
                if (dropped != null) {
                    seen.remove(dropped.id());
                }
            }
        }
        for (Consumer<ActivityEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOGGER.debugf("Activity listener failed: %s", e.getMessage());
            }
        }
        return true;
    }

    /**
     * The latest entries, newest last.
     *
     * @param types
     *            only these types (prefix match, e.g. {@code node.}); empty for all
     */
    public List<ActivityEvent> recent(int limit, List<String> types) {
        int max = Math.max(1, Math.min(limit, RING_SIZE));
        List<ActivityEvent> matching = new ArrayList<>();
        synchronized (ring) {
            for (ActivityEvent e : ring) {
                if (types == null || types.isEmpty() || types.stream().anyMatch(t -> e.type().startsWith(t))) {
                    matching.add(e);
                }
            }
        }
        matching.sort((a, b) -> Long.compare(a.ts(), b.ts()));
        return matching.size() <= max ? matching : List.copyOf(matching.subList(matching.size() - max, matching.size()));
    }

    /**
     * Subscribes a live listener (an SSE client).
     *
     * @return false when {@value #MAX_LISTENERS} are already subscribed on this
     *         node
     */
    public boolean addListener(Consumer<ActivityEvent> listener) {
        synchronized (listeners) {
            if (listeners.size() >= MAX_LISTENERS) {
                return false;
            }
            listeners.add(listener);
            return true;
        }
    }

    public void removeListener(Consumer<ActivityEvent> listener) {
        listeners.remove(listener);
    }

    int listenerCount() {
        return listeners.size();
    }

    // ---------------------------------------------------------------- NATS

    private boolean clustered() {
        return config.isNats() && connections.isResolvable();
    }

    private String stream() {
        return config.natsPrefix() + "_ACTIVITY";
    }

    private void publish(ActivityEvent event) {
        if (!clustered()) {
            return;
        }
        NatsConnectionManager manager = connections.get();
        if (!manager.isConnected()) {
            enqueue(event);
            return;
        }
        try {
            String subject = new ClusterSubjects(config.natsPrefix()).root() + ".ops." + event.type();
            manager.jetStream().publishAsync(subject, JSON.writeValueAsBytes(event),
                    PublishOptions.builder().messageId(event.id()).build()).exceptionally(failure -> {
                        enqueue(event);
                        return null;
                    });
        } catch (IOException | RuntimeException e) {
            enqueue(event);
        }
    }

    private void enqueue(ActivityEvent event) {
        synchronized (outbox) {
            if (outbox.size() >= OUTBOX_SIZE) {
                outbox.pollFirst();
            }
            outbox.addLast(event);
        }
    }

    void flushOutbox() {
        List<ActivityEvent> pending;
        synchronized (outbox) {
            pending = new ArrayList<>(outbox);
            outbox.clear();
        }
        for (ActivityEvent event : pending) {
            publish(event);
        }
    }

    @Override
    public void startCluster() {
        NatsConnectionManager manager = connections.get();
        manager.onConnected(this::onConnected);
        // Retried, not only flushed on connect: right after NATS comes back the streams
        // may still be electing leaders, so the first publishes after a reconnect fail
        // and
        // go back to the outbox — where they used to stay until the next disconnection.
        manager.scheduler().scheduleWithFixedDelay(() -> {
            if (manager.isConnected() && pendingCount() > 0) {
                flushOutbox();
            }
        }, OUTBOX_RETRY_SECONDS, OUTBOX_RETRY_SECONDS, TimeUnit.SECONDS);
    }

    int pendingCount() {
        synchronized (outbox) {
            return outbox.size();
        }
    }

    private synchronized void onConnected() {
        NatsConnectionManager manager = connections.get();
        long lastSequence = provision(manager);
        Connection connection;
        try {
            connection = manager.requireConnected();
        } catch (ClusterUnavailableException e) {
            return;
        }
        if (subscribedOn != connection) {
            try {
                Dispatcher dispatcher = connection.createDispatcher();
                ConsumerConfiguration.Builder cc = ConsumerConfiguration.builder();
                if (lastSequence > 0) {
                    // Read back the latest entries, then follow live.
                    cc.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(Math.max(1, lastSequence - RING_SIZE + 1));
                } else {
                    cc.deliverPolicy(DeliverPolicy.All);
                }
                PushSubscribeOptions options = PushSubscribeOptions.builder().ordered(true).configuration(cc.build()).build();
                String wildcard = new ClusterSubjects(config.natsPrefix()).root() + ".ops.>";
                manager.jetStream().subscribe(wildcard, dispatcher, this::onMessage, false, options);
                subscribedOn = connection;
            } catch (IOException | JetStreamApiException | RuntimeException e) {
                LOGGER.warnf("Could not subscribe to the cluster activity stream: %s — retried on the next connect", e.getMessage());
            }
        }
        flushOutbox();
    }

    private long provision(NatsConnectionManager manager) {
        StreamConfiguration desired = StreamConfiguration.builder().name(stream())
                .subjects(new ClusterSubjects(config.natsPrefix()).root() + ".ops.>").retentionPolicy(RetentionPolicy.Limits)
                .maxAge(maxAge).maxMessages(MAX_STREAM_MESSAGES).discardPolicy(DiscardPolicy.Old).storageType(StorageType.File)
                .replicas(config.natsReplicas()).duplicateWindow(Duration.ofMinutes(2)).build();
        try {
            JetStreamManagement jsm = manager.jetStreamManagement();
            StreamInfo info;
            try {
                jsm.getStreamInfo(stream());
                info = jsm.updateStream(desired);
            } catch (JetStreamApiException notFound) {
                info = jsm.addStream(desired);
                LOGGER.infof("Created cluster activity stream %s", stream());
            }
            return info.getStreamState().getLastSequence();
        } catch (IOException | JetStreamApiException | ClusterUnavailableException e) {
            LOGGER.warnf("Could not provision the activity stream %s: %s", stream(), e.getMessage());
            return 0;
        }
    }

    private void onMessage(Message msg) {
        receive(msg.getData());
    }

    /** Package-private for tests: one entry read back from the stream. */
    void receive(byte[] data) {
        try {
            Map<String, Object> raw = JSON.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = raw.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Object ts = raw.get("ts");
            accept(new ActivityEvent(String.valueOf(raw.get("id")), String.valueOf(raw.get("type")), String.valueOf(raw.get("severity")),
                    String.valueOf(raw.get("node")), ts instanceof Number n ? n.longValue() : 0L, payload));
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Unreadable activity entry: %s", e.getMessage());
        }
    }
}
