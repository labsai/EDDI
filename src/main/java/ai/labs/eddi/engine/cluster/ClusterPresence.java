/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;
import io.nats.client.api.KeyValueWatcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Who is in the cluster right now.
 * <p>
 * Every node writes {@code n.<nodeId>} into the {@code NODES} bucket every
 * {@code eddi.cluster.presence.interval} (10 s); the bucket's 30 s TTL removes
 * a node that stopped writing. The value carries the node's boot id (for fast
 * lease takeover after a restart), its version, the payload protocol it speaks
 * and a few load numbers for the admin cluster view. Nothing here is on a
 * turn's path.
 * <p>
 * <b>Watched, not polled (D6).</b> Each node keeps a KV watch on the bucket and
 * answers {@link #members()} and {@link #currentMembers()} from what it
 * delivered: polling cost every node one key listing plus one get per member
 * every few seconds, O(N²) for the cluster. A watch does not see a value
 * expire, so a record is dropped locally once this node has not seen it
 * rewritten for the bucket TTL. While the watch is not running (before its
 * initial values, after a disconnect) the bucket is read as before.
 * <p>
 * <b>Ages on this node's clock (OPS-1).</b> How old a record is — for the
 * expiry above and for {@link #heartbeatAgeMillis} — is measured on this node's
 * monotonic clock from the moment it saw that revision arrive, never by
 * comparing a writer's {@code updatedAt} or the server's write time with this
 * node's wall clock: a node whose clock ran 20 s ahead dropped every member
 * between two rewrites, and a peer whose clock ran 10 s behind looked stale to
 * the drain guards. The price is paid at the start: a record delivered with the
 * watch's initial values, or found by the first bucket read, was written at
 * some unknown moment within the TTL. It is listed, kept for at most one TTL
 * after it arrived, and has no known age until it is rewritten (one presence
 * interval for a live node). {@code updatedAt} stays in the record, for
 * display.
 * <p>
 * <b>Duplicate node ids (C6).</b> Two JVMs with one node id see each other's
 * leases as "our earlier boot" and delete or steal them, so mutual exclusion is
 * gone. A record is therefore written with compare-and-set, never blindly. A
 * node that finds its key held by another boot waits one interval: a record
 * that did not change is a dead predecessor (a crashed pod restarted under the
 * same name) and is taken over; one that changed — or a CAS that loses to a
 * foreign write — is a live twin. Then this node stops writing its record, logs
 * an ERROR, reports {@code eddi.cluster.node.duplicate=1} and its readiness
 * goes DOWN ({@link NodeIdentityHealthCheck}), until the other record
 * disappears.
 * <p>
 * <b>Mixed versions (C15).</b> When the live members report more than one EDDI
 * version or payload protocol, a WARN names them once per combination
 * ({@code eddi.cluster.versions} counts them) — expected during a rolling
 * upgrade, a problem if it stays.
 */
@ApplicationScoped
@Typed(ClusterPresence.class)
public class ClusterPresence {

    private static final Logger LOGGER = Logger.getLogger(ClusterPresence.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_PREFIX = "n.";
    private static final String LEFT_PREFIX = "left.";
    private static final long MEMBER_CACHE_MILLIS = 3_000;
    /**
     * The payload protocol of this version: what its RPCs, events and records look
     * like. Raised when one of them changes incompatibly.
     */
    public static final int PROTOCOL = 1;

    private final NatsConnectionManager connections;
    private final ISharedKv nodes;
    private final MeterRegistry meterRegistry;
    /**
     * The bucket's name on the server, for the watch; null in unit tests (no
     * watch).
     */
    private final String bucketName;
    private final long ttlMillis;
    private final long startedAt = System.currentTimeMillis();
    private final String host = resolveHost();
    private final List<Supplier<Map<String, Object>>> contributors = new CopyOnWriteArrayList<>();
    private volatile List<Map<String, Object>> cachedMembers = List.of();
    private volatile long cachedAt;
    private volatile int lastKnownMembers = 1;

    /** Watched records by key. */
    private final Map<String, Seen> watched = new ConcurrentHashMap<>();
    private volatile boolean watchReady;
    private volatile Connection watchedOn;
    /** What the last bucket read found, by key: the dating without a watch. */
    private final Map<String, Seen> polled = new ConcurrentHashMap<>();
    /**
     * When the bucket was last read, on {@link #clock}; 0 before the first read.
     */
    private volatile long polledAt;

    /**
     * A record as this node received it: its revision, when this node saw that
     * revision on {@link #clock}, and whether that moment is when it was written —
     * false for a record that was already there when this node started looking.
     */
    private record Seen(Map<String, Object> record, long revision, long seenAt, boolean dated) {
    }

    /** The revision of this node's own last write, 0 before the first. */
    private volatile long ownRevision;
    /**
     * A foreign record seen under this node's key, waiting one interval to tell a
     * predecessor from a twin.
     */
    private volatile long foreignRevisionSeen;
    /**
     * When {@link #foreignRevisionSeen} was first seen: a takeover needs one full
     * interval of monotonic time, not merely two writes in a row (a reconnect and a
     * scheduled tick can come milliseconds apart).
     */
    private volatile long foreignSeenAt;
    private LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
    private volatile boolean duplicate;
    /** Run once each time this node's ownership of its id becomes confirmed. */
    private final List<Runnable> identityConfirmedListeners = new CopyOnWriteArrayList<>();
    private volatile boolean identityConfirmedAnnounced;
    private final Set<String> warnedVersionMixes = ConcurrentHashMap.newKeySet();
    private volatile int distinctVersions = 1;

    @Inject
    public ClusterPresence(NatsConnectionManager connections, NatsSharedStateFactory sharedState, MeterRegistry meterRegistry) {
        this(connections, sharedState.bucket(nodesBucket(connections)), meterRegistry, sharedState.serverName(nodesBucket(connections)),
                nodesBucket(connections).ttl());
    }

    ClusterPresence(NatsConnectionManager connections, ISharedKv nodes, MeterRegistry meterRegistry) {
        this(connections, nodes, meterRegistry, null, Duration.ofSeconds(30));
    }

    ClusterPresence(NatsConnectionManager connections, ISharedKv nodes, MeterRegistry meterRegistry, String bucketName, Duration ttl) {
        this.connections = connections;
        this.nodes = nodes;
        this.meterRegistry = meterRegistry;
        this.bucketName = bucketName;
        this.ttlMillis = ttl.toMillis();
    }

    /**
     * For tests: the monotonic millisecond clock the takeover interval and the
     * record ages are measured with.
     */
    ClusterPresence withClock(LongSupplier clock) {
        this.clock = clock;
        return this;
    }

    private static SharedBucket nodesBucket(NatsConnectionManager connections) {
        return SharedBucket.NODES.withTtl(connections.config().presenceInterval().multipliedBy(3));
    }

    /** Adds load numbers to this node's presence record. */
    public void contribute(Supplier<Map<String, Object>> contributor) {
        contributors.add(contributor);
    }

    public void start() {
        Gauge.builder("eddi.cluster.members", this, p -> p.lastKnownMembers).description("Cluster members seen in the presence bucket")
                .register(meterRegistry);
        Gauge.builder("eddi.cluster.node.duplicate", this, p -> p.duplicate ? 1 : 0)
                .description("1 while another live JVM uses this node's id (readiness is DOWN)").register(meterRegistry);
        Gauge.builder("eddi.cluster.versions", this, p -> p.distinctVersions)
                .description("Distinct EDDI versions / payload protocols among the live members").register(meterRegistry);
        long period = connections.config().presenceInterval().toMillis();
        connections.scheduler().scheduleAtFixedRate(this::publishSafely, 0, period, TimeUnit.MILLISECONDS);
        connections.onConnected(this::publishSafely);
        connections.onConnected(this::startWatch);
    }

    void publishSafely() {
        if (!connections.isConnected()) {
            return;
        }
        try {
            publish();
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Presence write failed: %s", e.getMessage());
        }
        announceIdentity();
    }

    private void announceIdentity() {
        if (!isIdentityConfirmed()) {
            identityConfirmedAnnounced = false;
            return;
        }
        if (identityConfirmedAnnounced) {
            return;
        }
        identityConfirmedAnnounced = true;
        for (Runnable listener : identityConfirmedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Identity-confirmed listener failed");
            }
        }
    }

    /**
     * True once this JVM holds its own presence record — written fresh, or taken
     * over from a predecessor that did not rewrite it for one interval — and no
     * twin is flagged. Before that, a record or lease under this node id and
     * another boot may belong to a live twin.
     */
    public boolean isIdentityConfirmed() {
        return ownRevision > 0 && !duplicate;
    }

    /**
     * Runs {@code listener} each time {@link #isIdentityConfirmed()} becomes true,
     * on the presence thread.
     */
    public void onIdentityConfirmed(Runnable listener) {
        identityConfirmedListeners.add(listener);
    }

    /**
     * Writes this node's record with compare-and-set, after checking that the key
     * is not held by a live twin (see the class comment).
     */
    void publish() throws IOException {
        String key = KEY_PREFIX + connections.node().nodeId();
        String boot = connections.node().bootId();
        byte[] value = JSON.writeValueAsBytes(record());
        Optional<ISharedKv.Versioned> current = nodes.get(key);
        if (current.isEmpty()) {
            if (duplicate) {
                LOGGER.infof("The other JVM using node id %s is gone — this node takes the id back", connections.node().nodeId());
                duplicate = false;
            }
            foreignRevisionSeen = 0;
            // A lost create leaves this node unconfirmed: the record belongs to whoever
            // won it, and the next write tells a predecessor from a twin as usual.
            long created = 0;
            try {
                created = nodes.create(key, value).orElse(0L);
            } finally {
                ownRevision = created;
            }
            return;
        }
        ISharedKv.Versioned seen = current.get();
        String holder = bootOf(seen.value());
        if (boot.equals(holder) || seen.revision() == ownRevision) {
            // Our own record: rewrite it at the revision we know.
            OptionalLong rev = nodes.update(key, value, seen.revision());
            if (rev.isPresent()) {
                ownRevision = rev.getAsLong();
            }
            return;
        }
        // Someone else's boot holds our key.
        if (ownRevision > 0 && !duplicate) {
            // We had a record and it was overwritten: another JVM writes under this id.
            flagDuplicate(holder, "it overwrote this node's presence record");
            return;
        }
        if (duplicate) {
            return; // stays flagged while the other record lives; do not fight over the key
        }
        if (foreignRevisionSeen == 0) {
            if (leftCleanly(connections.node().nodeId(), holder)) {
                takeOver(key, value, seen, "its previous boot left cleanly");
                return;
            }
            // Wait one interval: a live twin rewrites its record by then, a dead
            // predecessor does not.
            foreignRevisionSeen = seen.revision();
            foreignSeenAt = clock.getAsLong();
            LOGGER.infof("Presence key of node id %s is held by boot %s — checking for one interval whether that is a live JVM "
                    + "or this node's previous boot", connections.node().nodeId(), holder);
            return;
        }
        if (seen.revision() != foreignRevisionSeen) {
            flagDuplicate(holder, "its record kept being rewritten");
            return;
        }
        if (clock.getAsLong() - foreignSeenAt < connections.config().presenceInterval().toMillis()) {
            return; // not yet a full interval: a live twin may simply not have written again
        }
        takeOver(key, value, seen, "its record did not change for one interval");
    }

    private void takeOver(String key, byte[] value, ISharedKv.Versioned seen, String why) {
        OptionalLong rev = nodes.update(key, value, seen.revision());
        foreignRevisionSeen = 0;
        if (rev.isPresent()) {
            ownRevision = rev.getAsLong();
            LOGGER.infof("Took over the presence record of node id %s from an earlier boot (%s)", connections.node().nodeId(), why);
        } else {
            flagDuplicate(bootOf(nodes.get(key).map(ISharedKv.Versioned::value).orElse(null)), "it wrote the record while this "
                    + "node was taking it over");
        }
    }

    private void flagDuplicate(String otherBoot, String evidence) {
        duplicate = true;
        LOGGER.errorf("Another live JVM uses node id '%s' (boot %s, %s). Two nodes with one id break lease mutual exclusion "
                + "and RPC routing. This node stops announcing itself and reports NOT READY until the other one is gone. Give "
                + "every replica a unique eddi.cluster.node-id (the pod name).", connections.node().nodeId(), otherBoot, evidence);
    }

    /** True while another live JVM uses this node's id. */
    public boolean isDuplicate() {
        return duplicate;
    }

    private static String bootOf(byte[] value) {
        if (value == null) {
            return null;
        }
        try {
            Object boot = JSON.readValue(value, new TypeReference<Map<String, Object>>() {
            }).get("boot");
            return boot == null ? null : boot.toString();
        } catch (IOException e) {
            return null;
        }
    }

    Map<String, Object> record() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("node", connections.node().nodeId());
        record.put("boot", connections.node().bootId());
        record.put("version", Optional.ofNullable(ClusterPresence.class.getPackage().getImplementationVersion()).orElse("dev"));
        record.put("protocol", PROTOCOL);
        record.put("host", host);
        record.put("startedAt", startedAt);
        record.put("updatedAt", System.currentTimeMillis());
        record.put("natsRtt", connections.rttMillis());
        record.put("degraded", connections.isDegraded());
        for (Supplier<Map<String, Object>> contributor : contributors) {
            try {
                record.putAll(contributor.get());
            } catch (RuntimeException e) {
                LOGGER.debugf("Presence contributor failed: %s", e.getMessage());
            }
        }
        return record;
    }

    /**
     * This node's record as it would publish it now, whether or not NATS is
     * reachable.
     */
    public Map<String, Object> selfRecord() {
        return record();
    }

    // ------------------------------------------------------------------ watch

    /** (Re)starts the KV watch on the presence bucket; runs on every connect. */
    void startWatch() {
        if (bucketName == null) {
            return;
        }
        Connection connection;
        try {
            connection = connections.requireConnected();
        } catch (ClusterUnavailableException e) {
            return;
        }
        if (watchedOn == connection) {
            return;
        }
        watchReady = false;
        watched.clear();
        try {
            connections.keyValue(bucketName).watch(KEY_PREFIX + ">", new KeyValueWatcher() {
                @Override
                public void watch(KeyValueEntry entry) {
                    onWatched(entry.getKey(), entry.getValue(), entry.getOperation() == KeyValueOperation.PUT, entry.getRevision());
                }

                @Override
                public void endOfData() {
                    watchReady = true;
                }
            });
            watchedOn = connection;
        } catch (IOException | JetStreamApiException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debugf("Presence watch not started (members are read from the bucket meanwhile): %s", e.getMessage());
        }
    }

    /**
     * Applies one delivered change of the watch, stamped with when this node
     * received it. A value delivered before the end of the initial data was written
     * at some unknown time and stays undated. Package-private for tests.
     */
    void onWatched(String key, byte[] value, boolean put, long revision) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return;
        }
        if (!put || value == null) {
            watched.remove(key);
            return;
        }
        try {
            watched.put(key, new Seen(JSON.readValue(value, new TypeReference<Map<String, Object>>() {
            }), revision, clock.getAsLong(), watchReady));
        } catch (IOException e) {
            LOGGER.debugf("Unreadable presence record %s", key);
        }
    }

    /**
     * Marks the watch as delivering (tests); production sets it at end of initial
     * data.
     */
    void watchReady(boolean ready) {
        this.watchReady = ready;
    }

    private boolean watchUsable() {
        return watchReady && connections.isConnected();
    }

    private List<Map<String, Object>> fromWatch() {
        long now = clock.getAsLong();
        // A watch does not see a value expire: anything not rewritten for the TTL is
        // gone, and is dropped here, or the records of crashed nodes would pile up.
        watched.entrySet().removeIf(e -> now - e.getValue().seenAt() >= ttlMillis);
        List<Map<String, Object>> members = new ArrayList<>();
        watched.values().forEach(w -> members.add(w.record()));
        return members;
    }

    /** How many watched records are kept (for tests). */
    int watchedRecords() {
        return watched.size();
    }

    // ---------------------------------------------------------------- members

    /**
     * The presence records of every live member, sorted by node id. From the watch;
     * without one, read from the bucket and cached ~3 s.
     */
    public List<Map<String, Object>> members() {
        if (watchUsable()) {
            return remember(fromWatch());
        }
        long now = System.currentTimeMillis();
        if (now - cachedAt < MEMBER_CACHE_MILLIS) {
            return cachedMembers;
        }
        try {
            return currentMembers();
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Presence read failed, answering with the last member list: %s", e.getMessage());
            return cachedMembers;
        }
    }

    /**
     * The presence records as they are now; throws
     * {@link ClusterUnavailableException} when they cannot be known (NATS
     * unreachable). For a decision that must not be taken on a stale or empty list
     * — "is this the last node serving?" — where {@link #members()} would answer
     * from its cache, or with nothing before the first successful read.
     */
    public List<Map<String, Object>> currentMembers() {
        if (watchUsable()) {
            return remember(fromWatch());
        }
        long now = clock.getAsLong();
        // A record that is new, or at a new revision, since a read less than one TTL
        // ago was written in between, and is dated now. On a first read, or after a
        // longer gap, when it was written is unknown.
        boolean bracketed = polledAt > 0 && now - polledAt < ttlMillis;
        List<Map<String, Object>> members = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (String key : nodes.keys()) {
            if (!key.startsWith(KEY_PREFIX)) {
                continue;
            }
            nodes.get(key).ifPresent(v -> {
                try {
                    Map<String, Object> record = JSON.readValue(v.value(), new TypeReference<Map<String, Object>>() {
                    });
                    members.add(record);
                    found.add(key);
                    Seen before = polled.get(key);
                    if (before == null || before.revision() != v.revision()) {
                        polled.put(key, new Seen(record, v.revision(), now, bracketed));
                    }
                } catch (IOException e) {
                    LOGGER.debugf("Unreadable presence record %s", key);
                }
            });
        }
        polled.keySet().retainAll(found);
        polledAt = now;
        return remember(members);
    }

    /**
     * How long ago this node saw {@code nodeId}'s presence record written, on its
     * own monotonic clock — never the writer's {@code updatedAt} against this
     * node's wall clock, which misjudges every member whose clock is skewed. Read
     * it after {@link #members()} or {@link #currentMembers()}: it dates the
     * records those delivered. Empty when there is no such record, or when it was
     * already there when this node started looking and has not been rewritten
     * since; a caller deciding something must treat that as "cannot vouch for it".
     * This node's own record is always 0.
     */
    public OptionalLong heartbeatAgeMillis(String nodeId) {
        if (connections.node().nodeId().equals(nodeId)) {
            return OptionalLong.of(0);
        }
        Seen seen = (watchUsable() ? watched : polled).get(KEY_PREFIX + nodeId);
        if (seen == null || !seen.dated()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0, clock.getAsLong() - seen.seenAt()));
    }

    private List<Map<String, Object>> remember(List<Map<String, Object>> members) {
        members.sort(Comparator.comparing(m -> String.valueOf(m.get("node"))));
        cachedMembers = List.copyOf(members);
        cachedAt = System.currentTimeMillis();
        lastKnownMembers = Math.max(1, members.size());
        checkVersions(cachedMembers);
        return cachedMembers;
    }

    /**
     * C15: warns once per combination when the members do not all run one version.
     */
    void checkVersions(List<Map<String, Object>> members) {
        Set<String> versions = new TreeSet<>();
        for (Map<String, Object> member : members) {
            versions.add(member.getOrDefault("version", "?") + "/protocol " + member.getOrDefault("protocol", 1));
        }
        distinctVersions = Math.max(1, versions.size());
        if (versions.size() > 1 && warnedVersionMixes.add(versions.toString())) {
            LOGGER.warnf("Cluster members run different versions: %s. Expected during a rolling upgrade; if it persists, "
                    + "finish the upgrade — see docs/clustering.md#rolling-upgrades.", versions);
        }
    }

    /** How many distinct version/protocol pairs the last member list held. */
    int distinctVersions() {
        return distinctVersions;
    }

    /** The record of {@code nodeId}, if it is alive. */
    public Optional<Map<String, Object>> member(String nodeId) {
        try {
            return memberStrict(nodeId);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        }
    }

    /**
     * Like {@link #member}, but a lookup that failed throws instead of answering
     * "not present": a caller deciding whether a node is gone must tell the two
     * apart.
     */
    public Optional<Map<String, Object>> memberStrict(String nodeId) {
        return nodes.get(KEY_PREFIX + nodeId).map(v -> {
            try {
                return JSON.readValue(v.value(), new TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * Writes this node's record now instead of at the next interval — after a
     * change an admin should see at once (a drain).
     */
    public void publishNow() {
        connections.scheduler().execute(this::publishSafely);
    }

    private static String resolveHost() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    /** The member count last observed; never below 1 (this node). */
    public int lastKnownMembers() {
        return lastKnownMembers;
    }

    /**
     * Whether {@code nodeId} in boot {@code boot} announced a clean shutdown (the
     * marker lives for the bucket's TTL). Unknown — NATS unreachable — is false.
     */
    public boolean leftCleanly(String nodeId, String boot) {
        try {
            return nodes.get(LEFT_PREFIX + nodeId).map(v -> new String(v.value(), StandardCharsets.UTF_8).equals(boot)).orElse(false);
        } catch (ClusterUnavailableException e) {
            return false;
        }
    }

    /** Removes this node's record — part of a clean shutdown. */
    public void leave() {
        if (duplicate) {
            return; // the record under this id belongs to the other JVM
        }
        try {
            // Said explicitly, so nobody has to guess it from timing: a node whose record
            // disappears with this marker beside it shut down; without it, it was lost.
            nodes.put(LEFT_PREFIX + connections.node().nodeId(), connections.node().bootId().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            LOGGER.debugf("Leave marker not written: %s", e.getMessage());
        }
        try {
            long rev = ownRevision;
            if (rev > 0) {
                nodes.delete(KEY_PREFIX + connections.node().nodeId(), rev);
            } else {
                nodes.delete(KEY_PREFIX + connections.node().nodeId());
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Presence delete failed: %s", e.getMessage());
        }
    }
}
