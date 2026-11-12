/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The open {@code GET /me/events} connections of this node, and the fan-out of
 * events to them.
 *
 * <h3>Scoping is decided here, per connection</h3> An event carries its
 * {@link UserEventAudience}; every connection is checked against it with what
 * the connection's own authenticated request established — its principal, its
 * {@code eddi-admin} / {@code eddi-approver} roles, its team subjects — and,
 * for a paused 1:1 conversation, a fresh {@link ResourceAccessGuard}
 * {@code USE} check on the agent. Nothing a client sends selects what it
 * receives.
 *
 * <h3>Bounded everywhere</h3>
 * <ul>
 * <li>At most {@code eddi.user-events.max-connections-per-user} streams per
 * principal and {@code eddi.user-events.max-connections} per node; the next one
 * is refused ({@code 429}). The per-user cap does not apply to the anonymous
 * bucket: with authorization off every caller of the deployment shares it, so a
 * per-user cap there would cap the whole deployment; only the node cap bounds
 * it. Before refusing, the caller's own streams whose client has already gone
 * are unregistered, so a reconnect is not refused by its own dead tabs.</li>
 * <li>Fan-out runs on one thread behind a queue of
 * {@code eddi.user-events.dispatch-queue-size}; an event that finds it full is
 * dropped and counted.</li>
 * <li>Each connection may have {@code eddi.user-events.buffer-size} frames
 * written but not yet acknowledged; a connection that falls further behind is
 * closed (its client reconnects and refetches).</li>
 * <li>A connection is closed when its access token expires, or after
 * {@code eddi.user-events.max-connection-age}, so the roles captured when it
 * opened are never older than that.</li>
 * </ul>
 *
 * <h3>Heartbeat</h3> Every {@code eddi.user-events.heartbeat-interval} each
 * connection gets an SSE comment line. It keeps proxies from cutting an idle
 * stream, and it is how a vanished client is noticed: its write fails and the
 * connection is unregistered.
 */
@ApplicationScoped
public class UserEventHub {

    private static final Logger LOGGER = Logger.getLogger(UserEventHub.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The bucket every connection shares when authorization is off. Exempt from the
     * per-user cap; the node cap still applies.
     */
    static final String ANONYMOUS_BUCKET = "anonymous";

    /** The first frame of every stream: the connection is live. */
    static final String EVENT_READY = "ready";

    static final String METER_CONNECTIONS = "eddi.user_events.connections";
    static final String METER_DELIVERED = "eddi.user_events.delivered";
    static final String METER_DROPPED = "eddi.user_events.dropped";
    static final String METER_REJECTED = "eddi.user_events.rejected";

    static final String DROP_SLOW_CONSUMER = "slow_consumer";
    static final String DROP_QUEUE_FULL = "dispatch_queue_full";
    static final String REJECT_USER_LIMIT = "user_limit";
    static final String REJECT_NODE_LIMIT = "node_limit";

    private final boolean enabled;
    private final int maxConnectionsPerUser;
    private final int maxConnections;
    private final int bufferSize;
    private final Duration heartbeatInterval;
    private final Duration maxConnectionAge;
    private final OwnershipValidator ownershipValidator;
    private final Instance<ResourceAccessGuard> accessGuard;
    private final MeterRegistry meterRegistry;
    private final LongSupplier clock;

    /**
     * Connections by bucket: the principal's hash, or {@link #ANONYMOUS_BUCKET}.
     */
    private final Map<String, Set<Connection>> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final Object registration = new Object();
    private final ThreadPoolExecutor dispatcher;
    private final ScheduledExecutorService heartbeat;

    @Inject
    public UserEventHub(@ConfigProperty(name = "eddi.user-events.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.user-events.max-connections-per-user", defaultValue = "8") int maxConnectionsPerUser,
            @ConfigProperty(name = "eddi.user-events.max-connections", defaultValue = "10000") int maxConnections,
            @ConfigProperty(name = "eddi.user-events.buffer-size", defaultValue = "64") int bufferSize,
            @ConfigProperty(name = "eddi.user-events.dispatch-queue-size", defaultValue = "10000") int dispatchQueueSize,
            @ConfigProperty(name = "eddi.user-events.heartbeat-interval", defaultValue = "20s") Duration heartbeatInterval,
            @ConfigProperty(name = "eddi.user-events.max-connection-age", defaultValue = "1h") Duration maxConnectionAge,
            OwnershipValidator ownershipValidator, Instance<ResourceAccessGuard> accessGuard, MeterRegistry meterRegistry) {
        this(enabled, maxConnectionsPerUser, maxConnections, bufferSize, dispatchQueueSize, heartbeatInterval, maxConnectionAge,
                ownershipValidator, accessGuard, meterRegistry, System::currentTimeMillis, true);
    }

    /** For tests: a controllable clock, and no background heartbeat thread. */
    UserEventHub(boolean enabled, int maxConnectionsPerUser, int maxConnections, int bufferSize, int dispatchQueueSize,
            Duration heartbeatInterval, Duration maxConnectionAge, OwnershipValidator ownershipValidator,
            Instance<ResourceAccessGuard> accessGuard, MeterRegistry meterRegistry, LongSupplier clock, boolean scheduleHeartbeat) {
        this.enabled = enabled;
        this.maxConnectionsPerUser = Math.max(1, maxConnectionsPerUser);
        this.maxConnections = Math.max(1, maxConnections);
        this.bufferSize = Math.max(1, bufferSize);
        this.heartbeatInterval = heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                ? Duration.ofSeconds(20)
                : heartbeatInterval;
        this.maxConnectionAge = maxConnectionAge == null || maxConnectionAge.isNegative() || maxConnectionAge.isZero()
                ? Duration.ofHours(1)
                : maxConnectionAge;
        this.ownershipValidator = ownershipValidator;
        this.accessGuard = accessGuard;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.dispatcher = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, dispatchQueueSize)), r -> {
            Thread t = new Thread(r, "eddi-user-events");
            t.setDaemon(true);
            return t;
        });
        Gauge.builder(METER_CONNECTIONS, total, AtomicInteger::get).description("Open GET /me/events streams on this node")
                .register(meterRegistry);
        if (scheduleHeartbeat && enabled) {
            this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eddi-user-events-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long period = this.heartbeatInterval.toMillis();
            heartbeat.scheduleAtFixedRate(this::heartbeat, period, period, TimeUnit.MILLISECONDS);
        } else {
            this.heartbeat = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    // ---------------------------------------------------------------- connections

    /**
     * What the connection's own request established about its caller, captured when
     * it opened.
     *
     * @param principal
     *            the caller's principal name; {@code null} only with authorization
     *            off
     * @param admin
     *            holds {@code eddi-admin}
     * @param approver
     *            holds {@code eddi-approver}
     * @param teamSubjects
     *            the caller's team subjects, for the {@code USE} check
     * @param tokenExpiresAt
     *            when the access token expires (epoch millis), or 0 when unknown
     */
    public record Subscriber(String principal, boolean admin, boolean approver, List<String> teamSubjects, long tokenExpiresAt) {

        public Subscriber {
            teamSubjects = teamSubjects == null ? List.of() : List.copyOf(teamSubjects);
        }
    }

    /** One open stream. */
    public static final class Connection {
        private final String id = UUID.randomUUID().toString();
        private final Subscriber subscriber;
        private final String bucket;
        private final UserEventSink sink;
        private final long openedAt;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();

        Connection(Subscriber subscriber, String bucket, UserEventSink sink, long openedAt) {
            this.subscriber = subscriber;
            this.bucket = bucket;
            this.sink = sink;
            this.openedAt = openedAt;
        }

        public String id() {
            return id;
        }

        public boolean isClosed() {
            return closed.get();
        }

        Subscriber subscriber() {
            return subscriber;
        }
    }

    /** Why a stream could not be opened. */
    public static final class Refused extends Exception {
        private final String reason;

        Refused(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** {@link #REJECT_USER_LIMIT} or {@link #REJECT_NODE_LIMIT}. */
        public String reason() {
            return reason;
        }
    }

    /**
     * Registers a stream and writes its {@code ready} frame. The caps are checked,
     * the caller's dead streams swept and the stream added under one lock, so two
     * concurrent opens at one below a cap cannot both pass it.
     *
     * @throws Refused
     *             when the caller (unless anonymous) or this node already has the
     *             most live streams allowed
     */
    public Connection open(Subscriber subscriber, UserEventSink sink) throws Refused {
        String bucket = bucketOf(subscriber);
        Connection connection = new Connection(subscriber, bucket, sink, clock.getAsLong());
        boolean perUserCapped = !ANONYMOUS_BUCKET.equals(bucket);
        synchronized (registration) {
            if (total.get() >= maxConnections || (perUserCapped && connectionCountOfBucket(bucket) >= maxConnectionsPerUser)) {
                // At a cap: first drop this caller's streams whose client already went
                // away (a reload, a dropped network) but that no write or heartbeat has
                // noticed yet, so they do not hold the slots of the reconnect.
                sweepClosed(bucket);
            }
            if (total.get() >= maxConnections) {
                rejected(REJECT_NODE_LIMIT);
                throw new Refused(REJECT_NODE_LIMIT, "This node has the most event streams it accepts open");
            }
            // Looked up after the sweep, which removes a bucket it empties.
            Set<Connection> connections = buckets.computeIfAbsent(bucket, b -> new CopyOnWriteArraySet<>());
            if (perUserCapped && connections.size() >= maxConnectionsPerUser) {
                rejected(REJECT_USER_LIMIT);
                throw new Refused(REJECT_USER_LIMIT, "Too many event streams are open for this user");
            }
            connections.add(connection);
            total.incrementAndGet();
        }
        Map<String, Object> ready = new HashMap<>();
        ready.put("v", 1);
        ready.put("heartbeatSeconds", heartbeatInterval.toSeconds());
        write(connection, EVENT_READY, toJson(ready));
        return connection;
    }

    /** Unregisters and closes a stream. Idempotent. */
    public void close(Connection connection) {
        if (connection == null || !connection.closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (registration) {
            Set<Connection> connections = buckets.get(connection.bucket);
            if (connections != null && connections.remove(connection)) {
                total.decrementAndGet();
                if (connections.isEmpty()) {
                    buckets.remove(connection.bucket, connections);
                }
            }
        }
        connection.sink.close();
    }

    /** Open streams on this node. */
    public int connectionCount() {
        return total.get();
    }

    /** Open streams of one principal on this node. */
    int connectionCount(String principal) {
        return connectionCountOfBucket(bucketOf(principal));
    }

    private int connectionCountOfBucket(String bucket) {
        Set<Connection> connections = buckets.get(bucket);
        return connections == null ? 0 : connections.size();
    }

    /**
     * Unregisters the streams of one bucket whose sink is already closed, or whose
     * {@link #close} has begun but not yet unregistered it. Called with
     * {@link #registration} held, and only for the bucket of the caller being
     * admitted — never a scan of every bucket.
     */
    private void sweepClosed(String bucket) {
        Set<Connection> connections = buckets.get(bucket);
        if (connections == null) {
            return;
        }
        for (Connection connection : connections) {
            if (connection.isClosed() || connection.sink.isClosed()) {
                // Marked closed so fan-out skips it; a close() racing on another thread
                // then finds it already removed and does not count it twice.
                connection.closed.set(true);
                if (connections.remove(connection)) {
                    total.decrementAndGet();
                }
            }
        }
        if (connections.isEmpty()) {
            buckets.remove(bucket, connections);
        }
    }

    private String bucketOf(Subscriber subscriber) {
        return bucketOf(subscriber.principal());
    }

    private String bucketOf(String principal) {
        return principal == null || principal.isBlank() ? ANONYMOUS_BUCKET : KvKeys.sha256(principal.trim());
    }

    // ---------------------------------------------------------------- fan-out

    /**
     * Hands an event to the fan-out thread. Never blocks and never throws: a full
     * queue drops the event and counts it.
     */
    public void dispatch(UserEvent event, UserEventAudience audience) {
        if (!enabled || event == null || audience == null || audience.isEmpty() || total.get() == 0) {
            return;
        }
        try {
            dispatcher.execute(() -> fanOut(event, audience));
        } catch (RejectedExecutionException e) {
            dropped(DROP_QUEUE_FULL);
        }
    }

    /** Delivers one event to every entitled connection of this node. */
    void fanOut(UserEvent event, UserEventAudience audience) {
        String plain = null;
        String yourTurn = null;
        String notYourTurn = null;
        for (Connection connection : candidates(audience)) {
            if (connection.isClosed()) {
                continue;
            }
            boolean entitled;
            try {
                entitled = entitled(connection, audience);
            } catch (RuntimeException e) {
                // Fail closed: an access check that cannot answer delivers nothing.
                LOGGER.debugf("User event entitlement check failed: %s", e.getMessage());
                entitled = false;
            }
            if (!entitled) {
                continue;
            }
            String data;
            if (audience.yourTurnHash() != null) {
                boolean mine = audience.yourTurnHash().equals(connection.bucket);
                if (mine) {
                    data = yourTurn != null ? yourTurn : (yourTurn = toJson(event.withYourTurn(true)));
                } else {
                    data = notYourTurn != null ? notYourTurn : (notYourTurn = toJson(event.withYourTurn(false)));
                }
            } else {
                data = plain != null ? plain : (plain = toJson(event));
            }
            if (data != null && write(connection, event.kind(), data)) {
                Counter.builder(METER_DELIVERED).description("User events written to a /me/events stream").tag("kind", event.kind())
                        .register(meterRegistry).increment();
            }
        }
    }

    /**
     * The connections that could be entitled: every one when roles decide, or when
     * authorization is off; otherwise only those of the named principals.
     */
    private List<Connection> candidates(UserEventAudience audience) {
        List<Connection> candidates = new ArrayList<>();
        if (!authEnabled() || audience.approvers() || audience.admins()) {
            buckets.values().forEach(candidates::addAll);
            return candidates;
        }
        for (String hash : audience.principalHashes()) {
            Set<Connection> connections = buckets.get(hash);
            if (connections != null) {
                candidates.addAll(connections);
            }
        }
        return candidates;
    }

    /**
     * Whether this connection's caller may receive an event with this audience.
     * With authorization off everybody is the owner and an administrator, as on
     * every other endpoint, so everybody may.
     */
    boolean entitled(Connection connection, UserEventAudience audience) {
        if (!authEnabled()) {
            return true;
        }
        Subscriber subscriber = connection.subscriber;
        if (subscriber.principal() == null || subscriber.principal().isBlank()) {
            return false;
        }
        if (audience.admins() && subscriber.admin()) {
            return true;
        }
        if (audience.approvers() && (subscriber.admin() || subscriber.approver())) {
            return true;
        }
        if (!audience.principalHashes().contains(connection.bucket)) {
            return false;
        }
        if (audience.useResourceId() != null && !subscriber.admin()) {
            ResourceAccessGuard guard = accessGuard != null && accessGuard.isResolvable() ? accessGuard.get() : null;
            if (guard == null) {
                return false;
            }
            return guard.checkPrincipalUse(audience.useResourceId(), subscriber.principal(), subscriber.teamSubjects(),
                    false) == ResourceAccessGuard.UseCheck.ALLOWED;
        }
        return true;
    }

    private boolean authEnabled() {
        return ownershipValidator == null || ownershipValidator.isAuthEnabled();
    }

    /**
     * Writes one frame, counting it against the connection's buffer. A connection
     * already {@code bufferSize} frames behind is closed instead.
     *
     * @return whether the frame was handed to the connection
     */
    private boolean write(Connection connection, String name, String data) {
        if (connection.isClosed()) {
            return false;
        }
        if (connection.inFlight.incrementAndGet() > bufferSize) {
            connection.inFlight.decrementAndGet();
            LOGGER.debugf("Closing user event stream %s: its client is %d frames behind", connection.id, bufferSize);
            dropped(DROP_SLOW_CONSUMER);
            close(connection);
            return false;
        }
        try {
            CompletionStage<?> stage;
            // The fan-out and the heartbeat threads both write; one frame at a time.
            synchronized (connection) {
                stage = name == null ? connection.sink.comment(data) : connection.sink.send(name, data);
            }
            stage.whenComplete((ignored, failure) -> {
                connection.inFlight.decrementAndGet();
                if (failure != null) {
                    close(connection);
                }
            });
            return true;
        } catch (RuntimeException e) {
            connection.inFlight.decrementAndGet();
            close(connection);
            return false;
        }
    }

    // ---------------------------------------------------------------- heartbeat

    /**
     * One heartbeat pass: closes connections whose client is gone, whose token
     * expired or that reached their maximum age, and writes a keep-alive comment to
     * the rest.
     */
    void heartbeat() {
        long now = clock.getAsLong();
        for (Set<Connection> connections : buckets.values()) {
            for (Connection connection : connections) {
                try {
                    long expiresAt = connection.subscriber.tokenExpiresAt();
                    if (connection.sink.isClosed() || (expiresAt > 0 && now >= expiresAt)
                            || now - connection.openedAt >= maxConnectionAge.toMillis()) {
                        close(connection);
                    } else {
                        write(connection, null, "keepalive");
                    }
                } catch (RuntimeException e) {
                    close(connection);
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        dispatcher.shutdownNow();
        for (Set<Connection> connections : buckets.values()) {
            connections.forEach(this::close);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void dropped(String reason) {
        Counter.builder(METER_DROPPED).description("User events or streams dropped: a full fan-out queue, or a client too far behind")
                .tag("reason", reason).register(meterRegistry).increment();
    }

    private void rejected(String reason) {
        Counter.builder(METER_REJECTED).description("GET /me/events streams refused at a connection cap").tag("reason", reason)
                .register(meterRegistry).increment();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            LOGGER.warnf("User event not serializable: %s", e.getMessage());
            return null;
        }
    }
}
