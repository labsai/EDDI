/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import ai.labs.eddi.engine.cluster.ClusterMessageSigner;
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
import io.nats.client.api.StreamInfo;
import io.nats.client.impl.Headers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Cluster events on a JetStream stream ({@code <prefix>_EVENTS}, subjects
 * {@code eddi.<prefix>.evt.>}, kept for {@code eddi.cluster.events.max-age}).
 * <p>
 * <b>Receiving</b>: one ordered push consumer per node, starting at
 * {@code DeliverPolicy.New}, followed by one resync after the first
 * subscription of a boot (what was published before it is unknown). A stream
 * that was deleted and recreated — its creation time changed, or its sequence
 * went back — is re-subscribed and resynced. The client recreates an ordered
 * consumer from the last stream sequence it delivered, so events published
 * while this node was briefly disconnected are replayed. If the first sequence
 * after a resume is beyond what retention still holds (a gap), or the node was
 * disconnected for longer than the retention, every registered full flush runs
 * — the node then rebuilds its caches from the database instead of trusting
 * what it missed.
 * <p>
 * <b>Publishing</b> never blocks the caller: {@code publishAsync}; a failure or
 * a disconnected client appends to a bounded outbox
 * ({@code eddi.cluster.events.outbox-size}) that is flushed on reconnect, and
 * every few seconds while connected (an async publish can fail without a
 * disconnect). An outbox overflow publishes {@code cache.resync-all} on
 * reconnect, so the other nodes flush whatever this one could not tell them.
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
    /** A flush of the outbox is running (at most one at a time). */
    private final AtomicBoolean flushing = new AtomicBoolean();
    /** A full resync is queued on the dispatch thread and has not started yet. */
    private final AtomicBoolean resyncQueued = new AtomicBoolean();
    /**
     * Two bounded single-thread lanes (B3). Cache evictions — by far the most
     * frequent events — run on {@link #bulk}; everything else (secrets,
     * connections, deployments, GDPR, resyncs) on {@link #control}, so a burst of
     * evictions no longer delays a {@code secret.changed}. An event a full lane
     * cannot take is dropped and replaced by a full local resync: a lost eviction
     * is exactly what a resync repairs.
     */
    private final ThreadPoolExecutor control = lane("eddi-cluster-events");
    private final ThreadPoolExecutor bulk = lane("eddi-cluster-events-cache");
    private volatile Connection subscribedOn;
    private volatile Dispatcher subscribedDispatcher;
    private volatile long lastStreamSequence;
    /**
     * When the event stream was created, as last read from the server. A different
     * value later means the stream was deleted and recreated (B2): its sequence
     * numbers start again, the ordered consumer may be deaf, and every event
     * published before it is gone.
     */
    private volatile ZonedDateTime streamCreated;
    /** Whether this boot has resynced after its first subscription (B2). */
    private final AtomicBoolean firstSubscriptionResynced = new AtomicBoolean();
    private final AtomicInteger ticks = new AtomicInteger();
    /** Last moment this node was seen connected (sampled every few seconds). */
    private volatile long lastConnectedAt = System.currentTimeMillis();
    private final Duration resyncJitter;
    private final ClusterMessageSigner signer;
    private Counter publishFailed;
    private Counter resyncCounter;

    static final int LANE_CAPACITY = 10_000;
    /** How often (in 2 s ticks) the stream's identity is checked: 30 s. */
    static final int STREAM_CHECK_TICKS = 15;

    @Inject
    public JetStreamEventBus(NatsConnectionManager connections, MeterRegistry meterRegistry, ClusterMessageSigner signer,
            @ConfigProperty(name = "eddi.cluster.events.resync-jitter", defaultValue = "5s") Duration resyncJitter) {
        this.connections = connections;
        this.node = connections.node();
        this.subjects = new ClusterSubjects(connections.config().natsPrefix());
        this.stream = connections.config().natsPrefix() + "_EVENTS";
        this.maxAge = connections.config().eventsMaxAge();
        this.outboxSize = connections.config().eventsOutboxSize();
        this.meterRegistry = meterRegistry;
        this.signer = signer;
        this.resyncJitter = resyncJitter == null || resyncJitter.isNegative() ? Duration.ZERO : resyncJitter;
    }

    /** For tests and the in-JVM cluster ITs: unsigned, no resync jitter. */
    public JetStreamEventBus(NatsConnectionManager connections, MeterRegistry meterRegistry) {
        this(connections, meterRegistry, ClusterMessageSigner.unsigned(), Duration.ZERO);
    }

    private static ThreadPoolExecutor lane(String name) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(LANE_CAPACITY), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
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
        Gauge.builder("eddi.cluster.events.dispatch.queue", this, b -> b.control.getQueue().size() + b.bulk.getQueue().size())
                .description("Received cluster events waiting to be handled on this node").register(meterRegistry);
        Counter.builder("eddi.cluster.events.dropped").description("Received events a full dispatch lane could not take (each one "
                + "triggers a resync)").register(meterRegistry);
        connections.onConnected(this::onConnected);
        connections.scheduler().scheduleAtFixedRate(() -> {
            if (connections.isConnected()) {
                lastConnectedAt = System.currentTimeMillis();
                if (outboxCount.get() > 0 && !flushing.get()) {
                    // An event whose async publish failed while the connection stayed up
                    // (a JetStream timeout, a leader election) waited for the next
                    // reconnect, which may never come. Flushed off this thread: a flush
                    // publishes synchronously.
                    Thread.ofVirtual().name("eddi-cluster-outbox").start(this::flushOutbox);
                }
                if (ticks.incrementAndGet() % STREAM_CHECK_TICKS == 0) {
                    // Off this shared scheduler thread: a STREAM.INFO can wait a request timeout.
                    Thread.ofVirtual().name("eddi-cluster-events-check").start(this::checkStreamIdentity);
                }
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
            // Signed when it is sent, not when it was queued: an event that waited in the
            // outbox carries the time it really left this node.
            var ack = js.publishAsync(subject, signer.sign(subject, data, null, ClusterEvent.VERSION), data);
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
        if (!flushing.compareAndSet(false, true)) {
            return; // another flush is draining the same deque
        }
        try {
            drainOutbox();
        } finally {
            flushing.set(false);
        }
    }

    private void drainOutbox() {
        if (outboxOverflowed.compareAndSet(true, false)) {
            LOGGER.warn("Cluster event outbox overflowed while NATS was unreachable — asking every node to resync");
            publish(ClusterEvent.RESYNC_ALL, Map.of("reason", "outbox-overflow", "node", node.nodeId()));
        }
        byte[][] item;
        while ((item = outbox.pollFirst()) != null) {
            outboxCount.decrementAndGet();
            String subject = new String(item[0], StandardCharsets.UTF_8);
            try {
                connections.jetStream().publish(subject, signer.sign(subject, item[1], null, ClusterEvent.VERSION), item[1]);
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
        boolean recreated = provisionStream();
        Connection connection = connections.requireConnected();
        if (recreated) {
            dropSubscription();
        }
        if (subscribedOn != connection) {
            subscribe(connection);
        }
        if (recreated && subscribedOn != null) {
            resync("the event stream was recreated; every event published before it is gone");
        }
        if (subscribedOn != null && firstSubscriptionResynced.compareAndSet(false, true)) {
            // B2: the consumer starts at DeliverPolicy.New, so whatever was published
            // before this first subscription — while this node booted with NATS down, or
            // before it existed at all — never arrives, and with no earlier sequence no
            // gap can be seen either. The caches reload from the database instead.
            if (lastStreamSequence == 0 && !recreated) {
                resync("first subscription to the event stream since this node started");
            }
        } else if (subscribedOn != null && downFor > maxAge.toMillis() && lastStreamSequence > 0) {
            resync("disconnected for " + Duration.ofMillis(downFor).toSeconds() + "s, longer than the event retention");
        }
        flushOutbox();
    }

    private void subscribe(Connection connection) {
        try {
            Dispatcher dispatcher = connection.createDispatcher();
            PushSubscribeOptions options = PushSubscribeOptions.builder().ordered(true)
                    .configuration(ConsumerConfiguration.builder().deliverPolicy(DeliverPolicy.New).build()).build();
            connections.jetStream().subscribe(subjects.eventWildcard(), dispatcher, this::onMessage, false, options);
            subscribedDispatcher = dispatcher;
            subscribedOn = connection;
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            LOGGER.warnf("Could not subscribe to cluster events: %s — retried on the next connect", e.getMessage());
        }
    }

    /**
     * Drops the current subscription so the next connect, or check, subscribes
     * anew.
     */
    private void dropSubscription() {
        Connection connection = subscribedOn;
        Dispatcher dispatcher = subscribedDispatcher;
        subscribedOn = null;
        subscribedDispatcher = null;
        lastStreamSequence = 0;
        if (connection != null && dispatcher != null) {
            try {
                connection.closeDispatcher(dispatcher);
            } catch (RuntimeException ignored) {
                // the connection is going away anyway
            }
        }
    }

    /**
     * B2: a stream that was deleted and recreated while this node stayed connected
     * leaves its ordered consumer silent. Checked every
     * {@value #STREAM_CHECK_TICKS} ticks; a recreated stream is re-subscribed and
     * triggers a resync.
     */
    synchronized void checkStreamIdentity() {
        if (!connections.isConnected()) {
            return;
        }
        try {
            StreamInfo info = connections.jetStreamManagement().getStreamInfo(stream);
            ZonedDateTime created = info.getCreateTime();
            ZonedDateTime known = streamCreated;
            streamCreated = created;
            if (known != null && created != null && !known.equals(created)) {
                LOGGER.warnf("Cluster event stream %s was recreated (created %s, was %s) — re-subscribing", stream, created, known);
                dropSubscription();
                subscribe(connections.requireConnected());
                resync("the event stream was recreated; every event published before it is gone");
            }
        } catch (JetStreamApiException notFound) {
            LOGGER.warnf("Cluster event stream %s is missing — recreating it", stream);
            if (provisionStream()) {
                dropSubscription();
                subscribe(connections.requireConnected());
                resync("the event stream was recreated; every event published before it is gone");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Event stream check failed: %s", e.getMessage());
        }
    }

    StreamConfiguration desiredStream() {
        return StreamConfiguration.builder().name(stream).subjects(subjects.eventWildcard()).retentionPolicy(RetentionPolicy.Limits)
                .maxAge(maxAge).maxMessages(MAX_STREAM_MESSAGES).discardPolicy(DiscardPolicy.Old).storageType(StorageType.File)
                .replicas(connections.config().natsReplicas()).build();
    }

    /**
     * Whether the stream on the server differs from what this node wants in a way
     * an update can change (D7: every node used to send an update on every
     * reconnect, whether anything differed or not).
     */
    static boolean differs(StreamConfiguration current, StreamConfiguration desired) {
        return !current.getSubjects().equals(desired.getSubjects()) || !current.getMaxAge().equals(desired.getMaxAge())
                || current.getMaxMsgs() != desired.getMaxMsgs() || current.getDiscardPolicy() != desired.getDiscardPolicy()
                || current.getRetentionPolicy() != desired.getRetentionPolicy() || current.getReplicas() != desired.getReplicas();
    }

    /**
     * Creates the stream when it is missing and updates it only when its
     * configuration differs.
     *
     * @return true when the stream this node knew was replaced by another one (it
     *         was missing and was created, or its creation time changed)
     */
    private boolean provisionStream() {
        StreamConfiguration desired = desiredStream();
        try {
            JetStreamManagement jsm = connections.jetStreamManagement();
            ZonedDateTime created;
            try {
                StreamInfo info = jsm.getStreamInfo(stream);
                created = info.getCreateTime();
                if (differs(info.getConfiguration(), desired)) {
                    jsm.updateStream(desired);
                    LOGGER.infof("Updated cluster event stream %s to the configured limits", stream);
                }
            } catch (JetStreamApiException notFound) {
                created = jsm.addStream(desired).getCreateTime();
                LOGGER.infof("Created cluster event stream %s", stream);
            }
            ZonedDateTime known = streamCreated;
            streamCreated = created;
            return known != null && created != null && !known.equals(created);
        } catch (IOException | JetStreamApiException | ClusterUnavailableException e) {
            LOGGER.warnf("Could not provision event stream %s: %s", stream, e.getMessage());
            return false;
        }
    }

    private void onMessage(Message msg) {
        long sequence = 0;
        try {
            sequence = msg.metaData().streamSequence();
        } catch (RuntimeException ignored) {
            // not a JetStream message
        }
        receive(msg.getSubject(), msg.hasHeaders() ? msg.getHeaders() : null, msg.getData(), sequence);
    }

    /** Package-private for tests: handles one delivered, unsigned event. */
    void receive(byte[] data, long streamSequence) {
        receive(null, null, data, streamSequence);
    }

    /** Package-private for tests: handles one delivered event. */
    void receive(String subject, Headers headers, byte[] data, long streamSequence) {
        long previous = lastStreamSequence;
        if (streamSequence > 0) {
            lastStreamSequence = streamSequence;
            if (previous > 0 && streamSequence > previous + 1) {
                resync("gap in the event stream (" + previous + " -> " + streamSequence + ")");
            } else if (previous > 0 && streamSequence <= previous) {
                // B2: sequences only grow within one stream; going back means it was recreated.
                resync("the event stream restarted its sequence (" + previous + " -> " + streamSequence + "): it was recreated");
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
        // Signed over the subject the publisher addressed; a test without one signs
        // nothing.
        String signedSubject = subject != null ? subject : subjects.event(event.type());
        // An event is legitimately up to the stream retention old when a node replays
        // what it missed while disconnected; a replayed copy is refused by its nonce.
        if (!signer.verify("event", signedSubject, headers, data, maxAge.plus(signer.maxAge()), true).accepted()) {
            return;
        }
        if (event.v() > ClusterEvent.VERSION && newerVersionWarned.compareAndSet(false, true)) {
            LOGGER.warnf("Received a cluster event of payload version %d from node %s; this node understands up to %d. A newer "
                    + "EDDI version is in the cluster (rolling upgrade?) — handled on a best-effort basis.", event.v(),
                    event.originNode(), ClusterEvent.VERSION);
        }
        if (event.v() > ClusterEvent.VERSION) {
            meterRegistry.counter("eddi.cluster.payload.newer", "kind", "event").increment();
        }
        boolean cacheEvent = ClusterEvent.CACHE_EVICT.equals(event.type()) || ClusterEvent.CACHE_CLEAR.equals(event.type());
        try {
            (cacheEvent ? bulk : control).execute(() -> handle(event));
        } catch (RejectedExecutionException full) {
            meterRegistry.counter("eddi.cluster.events.dropped").increment();
            resync("the event dispatch queue is full (" + LANE_CAPACITY + "): events were dropped");
        }
    }

    private final AtomicBoolean newerVersionWarned = new AtomicBoolean();

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

    /**
     * Administrative: flush every invalidatable cache on every node, this one
     * included (an event from this node's own boot is ignored when it comes back,
     * so the local flush runs here). The caches reload from the database.
     *
     * @return true when the request went out to the other nodes now; false when it
     *         waits in the outbox for a reconnect (this node has flushed already)
     */
    public boolean requestResyncAll(String reason) {
        // Decided before publishing: a disconnected publish goes to the outbox and only
        // reaches the other nodes on reconnect — the caller must not report it as done.
        boolean connected = connections.isConnected();
        publish(ClusterEvent.RESYNC_ALL, Map.of("reason", reason, "node", node.nodeId()));
        resync(reason, false);
        return connected;
    }

    /** {@link #resync(String, boolean)} with jitter. */
    void resync(String reason) {
        resync(reason, true);
    }

    /**
     * Flushes every invalidatable cache on this node, on the control lane.
     * <p>
     * Coalesced: a request that arrives while a flush is queued but has not started
     * joins it. When NATS comes back after a long outage, every node whose outbox
     * overflowed asks for a resync, and each node may also see a gap — without
     * coalescing a node flushed its caches once per request, and each flush is a
     * wave of cache misses against the database. A request that arrives once the
     * flush has started queues one more, so nothing loaded before a request is kept
     * after it.
     * <p>
     * D7: a resync every node runs because of the same cause (a cluster-wide
     * request, the same outage) starts after a random delay of up to
     * {@code eddi.cluster.events.resync-jitter}, so the nodes do not all reload
     * from the database in the same second. An administrator's own request on this
     * node runs at once.
     */
    void resync(String reason, boolean jitter) {
        if (!resyncQueued.compareAndSet(false, true)) {
            LOGGER.debugf("Cluster cache resync (%s) joined the one already queued", reason);
            meterRegistry.counter("eddi.cluster.events.resync.coalesced").increment();
            return;
        }
        LOGGER.warnf("Cluster cache resync: %s — flushing every invalidatable cache on this node", reason);
        if (resyncCounter != null) {
            resyncCounter.increment();
        }
        long delay = jitter && resyncJitter.toMillis() > 0 ? ThreadLocalRandom.current().nextLong(resyncJitter.toMillis() + 1) : 0L;
        if (delay > 0) {
            try {
                connections.scheduler().schedule(this::enqueueResync, delay, TimeUnit.MILLISECONDS);
                return;
            } catch (RuntimeException e) {
                LOGGER.debugf("Resync jitter not scheduled (%s) — resyncing now", e.getMessage());
            }
        }
        enqueueResync();
    }

    private void enqueueResync() {
        try {
            control.execute(() -> {
                resyncQueued.set(false);
                for (Runnable flush : resyncs) {
                    try {
                        flush.run();
                    } catch (RuntimeException e) {
                        LOGGER.warnf(e, "Cluster resync step failed");
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            resyncQueued.set(false);
            LOGGER.warn("Cluster cache resync not queued: the event dispatch queue is full — retried by the next request");
        }
    }

    int outboxDepth() {
        return outboxCount.get();
    }
}
