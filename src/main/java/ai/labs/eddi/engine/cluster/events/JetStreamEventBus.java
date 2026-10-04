/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.ClusterSubjects;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Cluster events on a JetStream stream ({@code <prefix>_EVENTS}, subjects
 * {@code eddi.<prefix>.evt.>}, kept for {@code eddi.cluster.events.max-age}).
 * <p>
 * <b>Receiving</b>: one ordered push consumer per node, starting at
 * {@code DeliverPolicy.New}. The client recreates an ordered consumer from the
 * last stream sequence it delivered, so events published while this node was
 * briefly disconnected are replayed. If the first sequence after a resume is
 * beyond what retention still holds (a gap), or the node was disconnected for
 * longer than the retention, every registered full flush runs — the node then
 * rebuilds its caches from the database instead of trusting what it missed.
 * <p>
 * <b>Publishing</b> never blocks the caller: {@code publishAsync}; a failure or
 * a disconnected client appends to a bounded outbox
 * ({@code eddi.cluster.events.outbox-size}) that is flushed on reconnect. An
 * outbox overflow publishes {@code cache.resync-all} on reconnect, so the other
 * nodes flush whatever this one could not tell them.
 * <p>
 * Events from this node's own boot are ignored (the change was applied locally
 * before publishing), and handlers run in arrival order on one thread.
 */
@ApplicationScoped
// ClusterStartable as well: ClusterBootstrap finds what to start through
// Instance<ClusterStartable>, and a bean typed to its own class only is
// invisible
// there — this one would never subscribe.
@Typed({JetStreamEventBus.class, ClusterStartable.class})
public class JetStreamEventBus implements IClusterEventBus, ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(JetStreamEventBus.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_STREAM_MESSAGES = 100_000;

    private final NatsConnectionManager connections;
    private final NodeIdentity node;
    private final ClusterSubjects subjects;
    private final String stream;
    private final Duration maxAge;
    private final int outboxSize;
    private final MeterRegistry meterRegistry;

    private final Map<String, List<Consumer<ClusterEvent>>> handlers = new ConcurrentHashMap<>();
    private final List<Runnable> resyncs = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<byte[][]> outbox = new ConcurrentLinkedDeque<>();
    private final AtomicInteger outboxCount = new AtomicInteger();
    private final AtomicBoolean outboxOverflowed = new AtomicBoolean();
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eddi-cluster-events");
        t.setDaemon(true);
        return t;
    });
    private volatile Connection subscribedOn;
    private volatile long lastStreamSequence;
    /** Last moment this node was seen connected (sampled every few seconds). */
    private volatile long lastConnectedAt = System.currentTimeMillis();
    private Counter publishFailed;
    private Counter resyncCounter;

    @Inject
    public JetStreamEventBus(NatsConnectionManager connections, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.node = connections.node();
        this.subjects = new ClusterSubjects(connections.config().natsPrefix());
        this.stream = connections.config().natsPrefix() + "_EVENTS";
        this.maxAge = connections.config().eventsMaxAge();
        this.outboxSize = connections.config().eventsOutboxSize();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @Override
    public void startCluster() {
        publishFailed = Counter.builder("eddi.cluster.events.publish.failed")
                .description("Cluster events that could not be published at once (kept in the outbox)").register(meterRegistry);
        resyncCounter = Counter.builder("eddi.cluster.events.resync")
                .description("Full local cache flushes after possibly missed events").register(meterRegistry);
        Gauge.builder("eddi.cluster.events.outbox", outboxCount, AtomicInteger::get)
                .description("Cluster events waiting to be published").register(meterRegistry);
        connections.onConnected(this::onConnected);
        connections.scheduler().scheduleAtFixedRate(() -> {
            if (connections.isConnected()) {
                lastConnectedAt = System.currentTimeMillis();
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- publish

    @Override
    public void publish(String type, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("v", ClusterEvent.VERSION);
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("type", type);
        envelope.put("originNode", node.nodeId());
        envelope.put("originBoot", node.bootId());
        envelope.put("ts", System.currentTimeMillis());
        envelope.put("payload", payload == null ? Map.of() : payload);
        byte[] data;
        try {
            data = JSON.writeValueAsBytes(envelope);
        } catch (IOException e) {
            LOGGER.warnf("Cluster event %s not serializable: %s", type, e.getMessage());
            return;
        }
        String subject = subjects.event(type);
        if (!connections.isConnected()) {
            enqueue(subject, data);
            return;
        }
        try {
            JetStream js = connections.jetStream();
            var ack = js.publishAsync(subject, data);
            // Success and failure as separate stages: the ack itself carries nothing we
            // use.
            ack.thenRun(() -> meterRegistry.counter("eddi.cluster.events.published", "type", type).increment());
            ack.exceptionally(failure -> {
                enqueue(subject, data);
                return null;
            });
        } catch (RuntimeException e) {
            enqueue(subject, data);
        }
    }

    private void enqueue(String subject, byte[] data) {
        if (publishFailed != null) {
            publishFailed.increment();
        }
        if (outboxCount.get() >= outboxSize) {
            outboxOverflowed.set(true);
            return;
        }
        outbox.addLast(new byte[][]{subject.getBytes(StandardCharsets.UTF_8), data});
        outboxCount.incrementAndGet();
    }

    void flushOutbox() {
        if (outboxOverflowed.compareAndSet(true, false)) {
            LOGGER.warn("Cluster event outbox overflowed while NATS was unreachable — asking every node to resync");
            publish(ClusterEvent.RESYNC_ALL, Map.of("reason", "outbox-overflow", "node", node.nodeId()));
        }
        byte[][] item;
        while ((item = outbox.pollFirst()) != null) {
            outboxCount.decrementAndGet();
            String subject = new String(item[0], StandardCharsets.UTF_8);
            try {
                connections.jetStream().publish(subject, item[1]);
                meterRegistry.counter("eddi.cluster.events.published", "type", typeOf(subject)).increment();
            } catch (IOException | JetStreamApiException | RuntimeException e) {
                // Any failure — a closing connection throws IllegalStateException — puts the
                // event back at the head; it was dropped before.
                outbox.addFirst(item);
                outboxCount.incrementAndGet();
                LOGGER.debugf("Outbox flush paused: %s", e.getMessage());
                return;
            }
        }
    }

    private String typeOf(String subject) {
        String prefix = subjects.event("");
        return subject.startsWith(prefix) ? subject.substring(prefix.length()) : subject;
    }

    // ---------------------------------------------------------------- subscribe

    @Override
    public void subscribe(String type, Consumer<ClusterEvent> handler) {
        handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void onResync(Runnable flush) {
        resyncs.add(flush);
    }

    private synchronized void onConnected() {
        long downFor = System.currentTimeMillis() - lastConnectedAt;
        lastConnectedAt = System.currentTimeMillis();
        provisionStream();
        Connection connection = connections.requireConnected();
        if (subscribedOn != connection) {
            try {
                Dispatcher dispatcher = connection.createDispatcher();
                PushSubscribeOptions options = PushSubscribeOptions.builder().ordered(true)
                        .configuration(ConsumerConfiguration.builder().deliverPolicy(DeliverPolicy.New).build()).build();
                connections.jetStream().subscribe(subjects.eventWildcard(), dispatcher, this::onMessage, false, options);
                subscribedOn = connection;
            } catch (IOException | JetStreamApiException e) {
                LOGGER.warnf("Could not subscribe to cluster events: %s — retried on the next connect", e.getMessage());
            }
        }
        if (subscribedOn != null && downFor > maxAge.toMillis() && lastStreamSequence > 0) {
            resync("disconnected for " + Duration.ofMillis(downFor).toSeconds() + "s, longer than the event retention");
        }
        flushOutbox();
    }

    private void provisionStream() {
        StreamConfiguration desired = StreamConfiguration.builder().name(stream).subjects(subjects.eventWildcard())
                .retentionPolicy(RetentionPolicy.Limits).maxAge(maxAge).maxMessages(MAX_STREAM_MESSAGES)
                .discardPolicy(DiscardPolicy.Old).storageType(StorageType.File).replicas(connections.config().natsReplicas()).build();
        try {
            JetStreamManagement jsm = connections.jetStreamManagement();
            try {
                jsm.getStreamInfo(stream);
                jsm.updateStream(desired);
            } catch (JetStreamApiException notFound) {
                jsm.addStream(desired);
                LOGGER.infof("Created cluster event stream %s", stream);
            }
        } catch (IOException | JetStreamApiException | ClusterUnavailableException e) {
            LOGGER.warnf("Could not provision event stream %s: %s", stream, e.getMessage());
        }
    }

    private void onMessage(Message msg) {
        long sequence = 0;
        try {
            sequence = msg.metaData().streamSequence();
        } catch (RuntimeException ignored) {
            // not a JetStream message
        }
        receive(msg.getData(), sequence);
    }

    /** Package-private for tests: handles one delivered event. */
    void receive(byte[] data, long streamSequence) {
        long previous = lastStreamSequence;
        if (streamSequence > 0) {
            lastStreamSequence = streamSequence;
            if (previous > 0 && streamSequence > previous + 1) {
                resync("gap in the event stream (" + previous + " -> " + streamSequence + ")");
            }
        }
        ClusterEvent event;
        try {
            Map<String, Object> raw = JSON.readValue(data, new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = raw.get("payload") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            event = new ClusterEvent(((Number) raw.getOrDefault("v", 1)).intValue(), String.valueOf(raw.get("id")),
                    String.valueOf(raw.get("type")), String.valueOf(raw.get("originNode")), String.valueOf(raw.get("originBoot")),
                    ((Number) raw.getOrDefault("ts", 0)).longValue(), (String) raw.get("traceparent"), payload);
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Unreadable cluster event: %s", e.getMessage());
            return;
        }
        if (node.bootId().equals(event.originBoot()) && node.nodeId().equals(event.originNode())) {
            return; // our own: applied locally before it was published
        }
        dispatch.execute(() -> handle(event));
    }

    private void handle(ClusterEvent event) {
        meterRegistry.counter("eddi.cluster.events.consumed", "type", event.type()).increment();
        if (ClusterEvent.RESYNC_ALL.equals(event.type())) {
            resync("requested by " + event.originNode());
            return;
        }
        for (Consumer<ClusterEvent> handler : handlers.getOrDefault(event.type(), List.of())) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Handler for cluster event %s failed", event.type());
            }
        }
    }

    void resync(String reason) {
        LOGGER.warnf("Cluster cache resync: %s — flushing every invalidatable cache on this node", reason);
        if (resyncCounter != null) {
            resyncCounter.increment();
        }
        dispatch.execute(() -> {
            for (Runnable flush : resyncs) {
                try {
                    flush.run();
                } catch (RuntimeException e) {
                    LOGGER.warnf(e, "Cluster resync step failed");
                }
            }
        });
    }

    int outboxDepth() {
        return outboxCount.get();
    }
}
