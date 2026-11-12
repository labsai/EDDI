/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Leases on a shared KV bucket ({@code <prefix>_LEASES} in NATS mode).
 * <p>
 * <b>Acquire</b> writes the key only while it holds no live value: a
 * compare-and-set at the revision of the delete marker the last release left
 * (or a KV {@code create} when there is none). The revision it returns is the
 * fencing token — KV revisions are the bucket stream's sequence numbers and
 * only ever grow. Each lease value carries a lease id ({@code lid}) unique to
 * this process, so a write whose reply was lost can be recognised as this
 * node's own. A failed acquire parks a waiter, which wakes on
 * <ul>
 * <li>a release notification addressed to this node by the holder, which this
 * node told that it is waiting (core NATS, sub-millisecond; it carries the
 * delete marker's revision, so the wake-up acquires in one round trip),</li>
 * <li>a jittered poll roughly every {@code ttl/4} (that base capped at
 * {@value #MAX_POLL_MILLIS} ms and floored at 50 ms, then spread over 0.5x to
 * 1.5x), which also notices that a crashed holder's lease expired on the server
 * (server-side TTL, so no clock skew between nodes is involved), and</li>
 * <li>fast takeover: a lease whose holder's presence record carries a different
 * boot id (it restarted), or is missing while this node has seen the lease's
 * revision unchanged for three heartbeats, is deleted by compare-and-set and
 * re-acquired at once.</li>
 * </ul>
 * <b>Validity</b>: a lease is trusted for {@code ttl − 2×heartbeat} after the
 * last write of it this node saw acknowledged (measured from when that write
 * was <i>sent</i>, so the server's TTL — counted from when it stored the write
 * — always ends later). Past that {@link LeaseHandle#isLost()} is true and the
 * lost callbacks fire, whether or not NATS could be reached: a holder cut off
 * from NATS stops itself before any other node can take the conversation over.
 * <p>
 * <b>Heartbeat</b>: a node-wide tick every {@code heartbeat/4} renews the
 * leases due (last renewal at least {@code 3/4} of a heartbeat ago) with a
 * compare-and-set on the last revision, at most
 * {@value #MAX_CONCURRENT_RENEWALS} at a time, oldest confirmation first. In
 * NATS mode it runs on a scheduler thread of its own. A revision mismatch is
 * re-read from the stream leader: if the key still holds this lease (a renewal
 * the server applied although its reply was lost), the lease is kept at the
 * revision found; otherwise it was taken over, and the handle fires
 * {@link LeaseHandle#onLost}, which cancels the running turn.
 * <p>
 * <b>Fairness</b>: a waiter tells the holder's node that it is waiting, and
 * leaves a {@code w.<key>} marker (read by the admin console and by nodes of
 * earlier versions). A holder that knows of another node's waiter when it
 * releases holds back its own next acquire of that key for
 * {@code handoff-grace}, and does not hand the lease to its own next queued
 * turn, so a busy node cannot starve the others.
 * <p>
 * <b>No I/O on the caller's thread for acquire</b>: attempts run on the I/O
 * executor and complete the returned stage; the coordinator never blocks a pool
 * thread, and never holds its queue monitor across a NATS call.
 */
public class KvLeaseManager implements IConversationLeaseManager {

    private static final Logger LOGGER = Logger.getLogger(KvLeaseManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WAITER_PREFIX = "w.";
    private static final long NOT_CONNECTED_RETRY_MILLIS = 250;
    /** How many leases of an admin listing are re-read from the stream leader. */
    static final int CONSISTENT_READS = 500;
    /** Upper bound of the waiter poll. */
    static final long MAX_POLL_MILLIS = 500;
    /**
     * Renewals in flight at once. A pass over thousands of leases sent all at once
     * filled the client's outgoing queue, and every renewal after it failed.
     */
    static final int MAX_CONCURRENT_RENEWALS = 64;
    /** Consecutive turns one lease may be handed to without a release. */
    static final int MAX_HANDOFFS = 16;
    /**
     * How long another node's "I am waiting" counts. Waiters repeat it with every
     * poll, so a waiter that gave up stops counting soon after.
     */
    static final long WAITING_FRESH_NANOS = TimeUnit.SECONDS.toNanos(3);

    static final List<String> ACQUIRE_OUTCOMES = List.of("acquired", "timeout", "degraded", "degraded_reject");
    static final List<String> LOST_REASONS = List.of("taken_over", "force_released", "unconfirmed", "gone");

    /** Core NATS notifications between lease managers. */
    public interface LeaseNotifier {
        /**
         * Broadcast to every node: the release notification of earlier versions, still
         * sent by them and by an administrative force-release.
         */
        void publishReleased(String key);

        void onReleased(Consumer<String> listener);

        /**
         * Tells {@code toNode}, which waits for {@code key}, that it was released;
         * {@code deleteRevision} is the delete marker's revision ({@code 0} when
         * unknown). Defaults to the broadcast.
         */
        default void publishReleasedTo(String toNode, String key, long deleteRevision) {
            publishReleased(key);
        }

        /**
         * Tells the holder's node {@code toNode} that {@code fromNode} waits for
         * {@code key}.
         */
        default void publishWaiting(String toNode, String key, String fromNode) {
        }

        /** Delivers the notifications addressed to {@code nodeId}. */
        default void onAddressed(String nodeId, AddressedListener listener) {
        }
    }

    /** Notifications addressed to one node. */
    public interface AddressedListener {
        void released(String key, long deleteRevision);

        void waiting(String key, String fromNode);
    }

    /** A lease taken over from a holder that had died or restarted. */
    public interface TakeoverListener {
        void tookOver(String key, LeaseInfo previousHolder);
    }

    /** What an admin force-release did. */
    public enum ForceReleaseOutcome {
        /** The lease was deleted; the next turn acquires a newer fencing token. */
        RELEASED,
        /** Nobody held it (any more). Nothing was changed. */
        ALREADY_RELEASED,
        /**
         * The holder renewed it after the caller looked (it is alive). Nothing was
         * changed; the current revision is in {@link ForceRelease#currentRevision}.
         */
        RENEWED
    }

    /**
     * Result of {@link #forceRelease}.
     *
     * @param outcome
     *            what the call did
     * @param holder
     *            the holder the lease had, or {@code null}
     * @param currentRevision
     *            the revision found ({@code 0} when absent)
     */
    public record ForceRelease(ForceReleaseOutcome outcome, LeaseInfo holder, long currentRevision) {
    }

    /**
     * A lease as the admin listing shows it.
     *
     * @param key
     *            full lease key ({@code c.<conversationId>}, {@code g.<id>},
     *            {@code leader.<role>})
     * @param holder
     *            holder node, boot, revision and acquisition time
     * @param renewedAt
     *            when the current revision was written (server clock), {@code 0}
     *            when unknown
     * @param waitingNode
     *            a node that is waiting for this lease, or {@code null}
     */
    public record LeaseSnapshot(String key, LeaseInfo holder, long renewedAt, String waitingNode) {
    }

    /** What the lease manager needs to know about the node and its peers. */
    public interface ClusterView {
        boolean isConnected();

        boolean isDegraded();

        /**
         * The boot id of a live member, empty when the node is not present. Throws
         * {@link ClusterUnavailableException} when presence could not be read: that is
         * "unknown", never "not present".
         */
        Optional<String> liveBoot(String nodeId);

        /**
         * True while another live JVM uses this node's id. Its leases then carry this
         * node id under a boot that is not ours but is not stale either, so neither the
         * boot sweep nor a takeover may treat them as an earlier incarnation.
         */
        default boolean isDuplicateNode() {
            return false;
        }

        /**
         * True once presence has settled who owns this node id: this JVM holds its own
         * presence record (written fresh or taken over from a dead predecessor), and no
         * twin was seen. Until then a lease under this node id and another boot may be
         * a live twin's, which presence tells apart only an interval after the first
         * connect — so neither the boot sweep nor a takeover may treat it as an earlier
         * incarnation's.
         */
        default boolean isNodeIdentityConfirmed() {
            return !isDuplicateNode();
        }
    }

    private final ISharedKv kv;
    private final NodeIdentity node;
    private final ClusterConfig config;
    private final ScheduledExecutorService scheduler;
    private final Executor io;
    private final LeaseNotifier notifier;
    private final ClusterView view;
    private final MeterRegistry meterRegistry;

    /** How long a lease is trusted after its last acknowledged write was sent. */
    private final long validityNanos;
    /** How old a renewal must be before the tick renews the lease again. */
    private final long renewDueNanos;
    private final long tickMillis;

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Map<String, Set<Waiter>> waiters = new ConcurrentHashMap<>();
    /** Lease ids a {@link #tryAcquireKey} of this process is writing, per key. */
    private final Map<String, Set<String>> tryAcquiring = new ConcurrentHashMap<>();
    private final Map<String, Long> yieldUntil = new ConcurrentHashMap<>();
    /**
     * Other nodes waiting for a lease this node holds, with when each last said so.
     */
    private final Map<String, Map<String, Long>> waitingNodes = new ConcurrentHashMap<>();
    /**
     * What this node last saw in a contended lease whose holder has no presence
     * record: the revision and when (monotonic clock) it was first seen.
     */
    private final Map<String, Observation> observed = new ConcurrentHashMap<>();
    private final AtomicLong leaseIds = new AtomicLong();
    private final Semaphore renewSlots = new Semaphore(MAX_CONCURRENT_RENEWALS);
    /** The sweep for this node's earlier boots has run (once per process). */
    private final AtomicBoolean swept = new AtomicBoolean();

    private record Observation(long revision, long sinceNanos) {
    }

    private volatile boolean shuttingDown;
    /**
     * Set by an administrator: no new leases until undrained. Unlike
     * {@link #shuttingDown} it can be reversed.
     */
    private volatile boolean draining;
    private final List<TakeoverListener> takeoverListeners = new CopyOnWriteArrayList<>();

    private final Counter takeovers;
    private final Counter releaseConflicts;
    private final Counter handoffs;
    private final Counter renewFailures;
    private final Counter orphansDeleted;
    private ScheduledFuture<?> heartbeat;
    /** A heartbeat pass is running on the I/O executor. */
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean();

    public KvLeaseManager(ISharedKv kv, NodeIdentity node, ClusterConfig config, ScheduledExecutorService scheduler, Executor io,
            LeaseNotifier notifier, ClusterView view, MeterRegistry meterRegistry) {
        this.kv = kv;
        this.node = node;
        this.config = config;
        this.scheduler = scheduler;
        this.io = io;
        this.notifier = notifier;
        this.view = view;
        this.meterRegistry = meterRegistry;
        this.validityNanos = validityNanos(config.leaseTtl(), config.leaseHeartbeatInterval());
        long heartbeatNanos = config.leaseHeartbeatInterval().toNanos();
        this.renewDueNanos = heartbeatNanos - heartbeatNanos / 4;
        this.tickMillis = Math.max(10, config.leaseHeartbeatInterval().toMillis() / 4);
        this.takeovers = Counter.builder("eddi.cluster.lease.takeover")
                .description("Leases taken over from a holder that had died or restarted").register(meterRegistry);
        this.releaseConflicts = Counter.builder("eddi.cluster.lease.release.conflicts")
                .description("Releases that found the lease already taken over").register(meterRegistry);
        this.handoffs = Counter.builder("eddi.cluster.lease.handoffs")
                .description("Leases handed straight to the next turn queued on this node, without a release").register(meterRegistry);
        this.renewFailures = Counter.builder("eddi.cluster.lease.renew.failures")
                .description("Lease renewals that could not reach NATS").register(meterRegistry);
        this.orphansDeleted = Counter.builder("eddi.cluster.lease.orphans.deleted")
                .description("Leases this process wrote but never took (the reply was lost), deleted").register(meterRegistry);
        Gauge.builder("eddi.cluster.lease.held", held, Map::size).description("Leases this node holds").register(meterRegistry);
        registerMeters();
        notifier.onReleased(key -> wakeWaiters(key, 0));
        notifier.onAddressed(node.nodeId(), new AddressedListener() {
            @Override
            public void released(String key, long deleteRevision) {
                wakeWaiters(key, deleteRevision);
            }

            @Override
            public void waiting(String key, String fromNode) {
                noteWaiting(key, fromNode);
            }
        });
    }

    /**
     * Registers every lease meter with all its tag values at once, so an alert on
     * {@code increase()} sees the first occurrence on each pod: a meter that only
     * appears with its first increment has no earlier sample to increase from.
     */
    private void registerMeters() {
        for (String outcome : ACQUIRE_OUTCOMES) {
            for (String contended : List.of("true", "false")) {
                Timer.builder("eddi.cluster.lease.acquire").tag("outcome", outcome).tag("contended", contended).register(meterRegistry);
            }
        }
        for (String reason : LOST_REASONS) {
            meterRegistry.counter("eddi.cluster.lease.lost", "reason", reason);
        }
        for (String op : List.of("create", "renew")) {
            meterRegistry.counter("eddi.cluster.lease.adopted", "op", op);
        }
        for (String action : List.of(ClusterConfig.REJECT, "local")) {
            meterRegistry.counter("eddi.cluster.degraded.decisions", "area", "turns", "action", action);
        }
    }

    /**
     * How long a lease is trusted after its last acknowledged write was sent:
     * {@code ttl − 2×heartbeat}, which leaves two missed renewals of margin before
     * the server could expire it. Where that leaves less than one and a half
     * heartbeats (a heartbeat close to half the TTL, which the configuration
     * allows), the midpoint between one heartbeat and the TTL instead: still a
     * quarter of the TTL or more short of it, and at least one renewal can land.
     */
    static long validityNanos(Duration ttl, Duration heartbeat) {
        long t = ttl.toNanos();
        long h = heartbeat.toNanos();
        long v = t - 2 * h;
        return v >= h + h / 2 ? v : (t + h) / 2;
    }

    /** Starts the node-wide heartbeat. */
    public void start() {
        heartbeat = scheduler.scheduleAtFixedRate(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    // ---------------------------------------------------------------- acquire

    @Override
    public CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait) {
        Waiter waiter = new Waiter(key, System.nanoTime() + maxWait.toNanos(), newLeaseId());
        if (shuttingDown) {
            waiter.future.completeExceptionally(
                    new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null, "node is shutting down"));
            return waiter.future;
        }
        if (draining) {
            waiter.future.completeExceptionally(drainingRefusal());
            return waiter.future;
        }
        waiters.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(waiter);
        waiter.future.whenComplete((h, t) -> removeWaiter(waiter));
        Long until = yieldUntil.remove(key);
        long delay = until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
        if (delay > 0) {
            waiter.contended = true;
            waiter.notBeforeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
            waiter.next = scheduler.schedule(() -> io.execute(() -> attempt(waiter)), delay, TimeUnit.MILLISECONDS);
        } else {
            io.execute(() -> attempt(waiter));
        }
        return waiter.future;
    }

    private String newLeaseId() {
        return Long.toString(leaseIds.incrementAndGet(), 36);
    }

    private void removeWaiter(Waiter waiter) {
        Set<Waiter> set = waiters.get(waiter.key);
        if (set != null) {
            set.remove(waiter);
            if (set.isEmpty()) {
                waiters.remove(waiter.key, set);
                observed.remove(waiter.key);
            }
        }
        ScheduledFuture<?> next = waiter.next;
        if (next != null) {
            next.cancel(false);
        }
        clearMarker(waiter);
    }

    /**
     * Deletes this waiter's {@code w.<key>} marker — by compare-and-set at the
     * revision it wrote, so a marker another node has written since stays. A waiter
     * that gave up (timeout, drain, shutdown) removes its own as well.
     */
    private void clearMarker(Waiter w) {
        long revision = w.markerRevision;
        if (revision <= 0) {
            return;
        }
        w.markerRevision = -1;
        try {
            io.execute(() -> {
                try {
                    kv.delete(WAITER_PREFIX + w.key, revision);
                } catch (ClusterUnavailableException ignored) {
                    // expires with the bucket TTL
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down; expires with the bucket TTL
        }
    }

    void attempt(Waiter w) {
        if (w.future.isDone()) {
            return;
        }
        // Set BEFORE trying to enter: an attempt already running re-runs once it
        // finishes, so a wake-up that arrives mid-attempt is never lost.
        w.rerun = true;
        if (!w.attempting.compareAndSet(false, true)) {
            return;
        }
        w.rerun = false;
        try {
            if (System.nanoTime() >= w.deadlineNanos) {
                timeout(w);
                return;
            }
            // Re-checked here: acquireKey reads the flag before it registers the waiter,
            // so a drain whose sweep ran in between never sees this waiter. An attempt
            // already past this check when the drain starts is refused by the sweep, and
            // grant() hands back the lease of a waiter that is already completed.
            if (draining) {
                w.future.completeExceptionally(drainingRefusal());
                return;
            }
            long holdBack = w.notBeforeNanos - System.nanoTime();
            if (holdBack > 0) {
                // Yielding to another node's waiter (handoff grace): a release
                // notification must not let this node jump the queue.
                reschedule(w, Math.max(1, TimeUnit.NANOSECONDS.toMillis(holdBack)));
                return;
            }
            if (!view.isConnected()) {
                if (view.isDegraded()) {
                    degraded(w);
                } else {
                    reschedule(w, NOT_CONNECTED_RETRY_MILLIS);
                }
                return;
            }
            tryOnce(w);
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Lease attempt for %s could not reach NATS: %s", sanitize(w.key), e.getMessage());
            if (view.isDegraded()) {
                degraded(w);
            } else {
                reschedule(w, NOT_CONNECTED_RETRY_MILLIS);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Lease attempt for %s failed unexpectedly", sanitize(w.key));
            reschedule(w, NOT_CONNECTED_RETRY_MILLIS);
        } finally {
            w.attempting.set(false);
            if (w.rerun && !w.future.isDone()) {
                ScheduledFuture<?> next = w.next;
                if (next != null) {
                    next.cancel(false);
                }
                io.execute(() -> attempt(w));
            }
        }
    }

    /**
     * One acquisition attempt. Uncontended it costs two round trips — read the
     * key's last record, write at its revision — or one, when a release
     * notification named the delete marker's revision.
     */
    private void tryOnce(Waiter w) {
        long sent = System.nanoTime();
        byte[] value = encode(System.currentTimeMillis(), w.lid);
        long hint = w.hint;
        w.hint = 0;
        if (hint > 0) {
            OptionalLong revision = kv.update(w.key, value, hint);
            if (revision.isPresent()) {
                grant(w, revision.getAsLong(), sent);
                return;
            }
        }
        ISharedKv.Latest latest = kv.latest(w.key);
        if (!latest.isLive()) {
            OptionalLong revision = kv.createAt(w.key, value, latest);
            if (revision.isPresent()) {
                grant(w, revision.getAsLong(), sent);
                return;
            }
            // Somebody wrote the key in between — look again at once.
            w.contended = true;
            observed.remove(w.key);
            reschedule(w, 1);
            return;
        }
        w.contended = true;
        ISharedKv.Versioned current = latest.live();
        LeaseInfo info = decode(current);
        w.lastHolder = info.node();
        if (isThisBoot(info)) {
            String lid = leaseId(current);
            if (w.lid.equals(lid)) {
                // An earlier attempt of this very waiter wrote the lease, but its reply was
                // lost (a timeout, a NATS leader change): it is ours.
                adopt(w, info);
                return;
            }
            if (!heldHere(w.key, lid)) {
                deleteOrphan(w.key, info);
                reschedule(w, 0);
                return;
            }
        } else if (isStale(w.key, info) && kv.delete(w.key, info.revision())) {
            observed.remove(w.key);
            takeovers.increment();
            notifyTakeover(w.key, info);
            w.takenOver = true;
            LOGGER.infof("Took over lease %s from %s/%s (holder gone)", sanitize(w.key), info.node(), info.boot());
            reschedule(w, 0);
            return;
        }
        announceWaiting(w, info);
        reschedule(w, pollDelayMillis());
    }

    /**
     * Takes a lease this waiter had written although the write's reply never came,
     * renewing it at once: when that write reached the server is unknown, so its
     * validity cannot be counted from it.
     */
    private void adopt(Waiter w, LeaseInfo info) {
        long sent = System.nanoTime();
        OptionalLong revision = kv.update(w.key, encode(System.currentTimeMillis(), w.lid), info.revision());
        if (revision.isEmpty()) {
            reschedule(w, 1);
            return;
        }
        meterRegistry.counter("eddi.cluster.lease.adopted", "op", "create").increment();
        LOGGER.infof("Lease %s was written by this node although the reply was lost — taken", sanitize(w.key));
        grant(w, revision.getAsLong(), sent);
    }

    /**
     * A lease this process wrote but nobody here holds: the write of an acquire
     * that gave up after its reply was lost. Left alone it would block the
     * conversation for the TTL — on every node, this one included.
     */
    private void deleteOrphan(String key, LeaseInfo info) {
        if (kv.delete(key, info.revision())) {
            orphansDeleted.increment();
            LOGGER.infof("Deleted lease %s, written by this node but never taken (its reply was lost)", sanitize(key));
        }
    }

    private boolean isThisBoot(LeaseInfo info) {
        return node.nodeId().equals(info.node()) && node.bootId().equals(info.boot());
    }

    /**
     * Whether this process holds the lease with id {@code lid} under {@code key},
     * or is still taking it: a waiter's or a {@link #tryAcquireKey}'s write is
     * visible in the bucket before it is recorded in {@code held}, and another
     * acquire of this process reading it in that window must not delete it as an
     * orphan. A waiter stays registered until after {@code grant} recorded its
     * lease (the future completes after {@code held.put}), and a tryAcquireKey id
     * until the call returns.
     */
    private boolean heldHere(String key, String lid) {
        if (lid == null) {
            return false;
        }
        Held h = held.get(key);
        if (h != null && lid.equals(h.lid)) {
            return true;
        }
        Set<Waiter> pending = waiters.get(key);
        if (pending != null) {
            for (Waiter w : pending) {
                if (lid.equals(w.lid)) {
                    return true;
                }
            }
        }
        Set<String> trying = tryAcquiring.get(key);
        return trying != null && trying.contains(lid);
    }

    /**
     * Tells the holder's node that this node waits (repeated with every poll; core
     * NATS, no round trip), and leaves the {@code w.<key>} marker once.
     */
    private void announceWaiting(Waiter w, LeaseInfo info) {
        if (!info.node().isEmpty() && !node.nodeId().equals(info.node())) {
            try {
                notifier.publishWaiting(info.node(), w.key, node.nodeId());
            } catch (RuntimeException e) {
                LOGGER.debugf("Waiting notice for %s not sent: %s", sanitize(w.key), e.getMessage());
            }
        }
        if (w.markerRevision == 0) {
            w.markerRevision = -1;
            try {
                w.markerRevision = kv.put(WAITER_PREFIX + w.key, node.nodeId().getBytes(StandardCharsets.UTF_8));
            } catch (ClusterUnavailableException ignored) {
                // fairness and the admin listing only
            }
            if (w.future.isDone()) {
                clearMarker(w); // completed while the marker was being written
            }
        }
    }

    private long pollDelayMillis() {
        long base = Math.min(MAX_POLL_MILLIS, Math.max(50, config.leaseTtl().toMillis() / 4));
        return base / 2 + ThreadLocalRandom.current().nextLong(base);
    }

    private void reschedule(Waiter w, long delayMillis) {
        if (w.future.isDone()) {
            return;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(w.deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0) {
            timeout(w);
            return;
        }
        long delay = Math.min(delayMillis, remainingMillis);
        if (delay <= 0) {
            io.execute(() -> attempt(w));
        } else {
            w.next = scheduler.schedule(() -> io.execute(() -> attempt(w)), delay, TimeUnit.MILLISECONDS);
        }
    }

    private boolean isStale(String key, LeaseInfo info) {
        if (node.nodeId().equals(info.node())) {
            // Our own earlier incarnation (same node id, different boot). A lease of this
            // boot is never stale: the caller recognises its own writes by the lease id.
            // While a live twin shares our id, the other boot may be that twin.
            return view.isNodeIdentityConfirmed() && !view.isDuplicateNode() && !node.bootId().equals(info.boot());
        }
        Optional<String> liveBoot;
        try {
            liveBoot = view.liveBoot(info.node());
        } catch (ClusterUnavailableException e) {
            // The presence lookup itself failed (a different bucket from the leases, so it
            // can fail while they work): unknown is not "gone". Never rob a holder
            // because a lookup failed.
            return false;
        }
        if (liveBoot.isPresent()) {
            return !liveBoot.get().equals(info.boot());
        }
        // No presence record. Presence and the lease heartbeat are separate writes, so
        // that alone does not prove the holder dead — and the holder's own clock (the
        // lease's "since") cannot be compared with ours. What does: a live holder
        // rewrites its lease every heartbeat interval, so a revision this node has
        // watched stay the same for three of them belongs to a holder that is gone.
        long now = System.nanoTime();
        Observation seen = observed.get(key);
        if (seen == null || seen.revision() != info.revision()) {
            observed.put(key, new Observation(info.revision(), now));
            return false;
        }
        return now - seen.sinceNanos() >= config.leaseHeartbeatInterval().multipliedBy(3).toNanos();
    }

    private void grant(Waiter w, long revision, long sentNanos) {
        Held h = newHeld(w.key, revision, w.takenOver, w.lid, sentNanos);
        observed.remove(w.key);
        held.put(w.key, h);
        if (!w.future.complete(h)) {
            // The waiter timed out in the meantime: give it straight back.
            release(h);
            return;
        }
        record(w, "acquired");
    }

    private Held newHeld(String key, long revision, boolean takenOver, String lid, long sentNanos) {
        return new Held(key, revision, takenOver, lid, sentNanos, validityNanos, this::expired);
    }

    private void timeout(Waiter w) {
        if (w.future.completeExceptionally(new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, w.lastHolder,
                "lease " + w.key + " is held by " + (w.lastHolder == null ? "another node" : w.lastHolder)))) {
            record(w, "timeout");
        }
    }

    private void degraded(Waiter w) {
        if (ClusterConfig.REJECT.equals(config.degradedTurns())) {
            if (w.future.completeExceptionally(
                    new LeaseUnavailableException(LeaseUnavailableException.Reason.DEGRADED, null, "NATS is unreachable"))) {
                record(w, "degraded_reject");
                meterRegistry.counter("eddi.cluster.degraded.decisions", "area", "turns", "action", "reject").increment();
            }
            return;
        }
        if (w.future.complete(LocalLeaseManager.unfenced(w.key))) {
            record(w, "degraded");
            meterRegistry.counter("eddi.cluster.degraded.decisions", "area", "turns", "action", "local").increment();
            LOGGER.warnf("NATS unreachable: running %s without a cluster lease (node-local ordering only, unfenced)",
                    sanitize(w.key));
        }
    }

    private void record(Waiter w, String outcome) {
        Timer.builder("eddi.cluster.lease.acquire").tag("outcome", outcome).tag("contended", String.valueOf(w.contended))
                .register(meterRegistry).record(System.nanoTime() - w.startNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public Optional<LeaseHandle> tryAcquireKey(String key) {
        if (shuttingDown || draining || !view.isConnected()) {
            return Optional.empty();
        }
        Held existing = held.get(key);
        if (existing != null && !existing.isLost()) {
            return Optional.of(existing);
        }
        String lid = newLeaseId();
        tryAcquiring.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(lid);
        try {
            long sent = System.nanoTime();
            ISharedKv.Latest latest = kv.latest(key);
            if (latest.isLive()) {
                ISharedKv.Versioned current = latest.live();
                LeaseInfo info = decode(current);
                boolean orphan = isThisBoot(info) && !heldHere(key, leaseId(current));
                boolean stale = !orphan && isStale(key, info);
                if (!orphan && !stale) {
                    return Optional.empty();
                }
                OptionalLong deleted = kv.deleteReturningRevision(key, info.revision());
                if (deleted.isEmpty()) {
                    return Optional.empty();
                }
                if (orphan) {
                    orphansDeleted.increment();
                } else {
                    observed.remove(key);
                    takeovers.increment();
                    notifyTakeover(key, info);
                }
                latest = new ISharedKv.Latest(null, deleted.getAsLong());
            }
            OptionalLong revision = kv.createAt(key, encode(System.currentTimeMillis(), lid), latest);
            if (revision.isEmpty()) {
                return Optional.empty();
            }
            Held h = newHeld(key, revision.getAsLong(), false, lid, sent);
            observed.remove(key);
            held.put(key, h);
            if (draining || shuttingDown) {
                // A drain (or shutdown) began while this create was in flight: its sweep
                // snapshot could not see this lease, so hand it straight back rather than
                // renew a lease this node will not use and no other node can take.
                release(h);
                return Optional.empty();
            }
            return Optional.of(h);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        } finally {
            tryAcquiring.computeIfPresent(key, (k, ids) -> {
                ids.remove(lid);
                return ids.isEmpty() ? null : ids;
            });
        }
    }

    // ---------------------------------------------------------------- handoff

    /**
     * Keeps a lease for the next turn queued on this node: no release, no
     * re-acquire, no round trip. Refused when the lease is lost or about to be
     * (less than one heartbeat of validity left), when another node is known to be
     * waiting for it, after {@value #MAX_HANDOFFS} handoffs in a row, and while the
     * node drains or shuts down.
     */
    @Override
    public boolean handOver(LeaseHandle handle) {
        if (!(handle instanceof Held h) || held.get(h.key) != h || h.released.get() || h.isLost() || draining || shuttingDown) {
            return false;
        }
        if (System.nanoTime() - h.confirmedNanos > validityNanos - config.leaseHeartbeatInterval().toNanos()) {
            return false;
        }
        if (!freshWaitingNodes(h.key).isEmpty() || h.handoffs >= MAX_HANDOFFS) {
            return false;
        }
        h.prepareForNextTurn();
        handoffs.increment();
        return true;
    }

    private void noteWaiting(String key, String fromNode) {
        if (fromNode == null || node.nodeId().equals(fromNode) || !held.containsKey(key)) {
            return;
        }
        waitingNodes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(fromNode, System.nanoTime());
        if (!held.containsKey(key)) {
            waitingNodes.remove(key); // released in between: no entry may outlive the lease
        }
    }

    private List<String> freshWaitingNodes(String key) {
        Map<String, Long> nodes = waitingNodes.get(key);
        return nodes == null ? List.of() : fresh(nodes);
    }

    private static List<String> fresh(Map<String, Long> nodes) {
        long now = System.nanoTime();
        List<String> fresh = new ArrayList<>(nodes.size());
        nodes.forEach((n, at) -> {
            if (now - at <= WAITING_FRESH_NANOS) {
                fresh.add(n);
            }
        });
        return fresh;
    }

    // ---------------------------------------------------------------- release

    @Override
    public void release(LeaseHandle handle) {
        if (!(handle instanceof Held h) || !h.released.compareAndSet(false, true)) {
            return; // unfenced, foreign or already released
        }
        held.remove(h.key, h);
        Map<String, Long> waiting = held.containsKey(h.key) ? null : waitingNodes.remove(h.key);
        try {
            long deleteRevision;
            if (h.isLost()) {
                // Lost here does not mean gone there: a lease lost because no renewal was
                // acknowledged may still be ours on the server, and would block the
                // conversation for the rest of its TTL — this node included.
                deleteRevision = deleteIfStillOurs(h);
            } else {
                OptionalLong deleted = kv.deleteReturningRevision(h.key, h.revision);
                deleteRevision = deleted.isPresent() ? deleted.getAsLong() : deleteIfStillOurs(h);
            }
            if (deleteRevision >= 0) {
                announceReleased(h.key, deleteRevision, waiting == null ? List.of() : fresh(waiting));
            }
        } catch (ClusterUnavailableException e) {
            // The lease expires on the server within the TTL; waiters poll for that.
            LOGGER.debugf("Lease release of %s could not reach NATS: %s", sanitize(h.key), e.getMessage());
        }
    }

    /**
     * Wakes this node's waiters, and tells every other node known to wait that the
     * lease is free — addressed to each, not broadcast to the cluster. Having
     * released to another node's waiter, this node holds back its own next acquire
     * of the key for the handoff grace.
     */
    private void announceReleased(String key, long deleteRevision, List<String> otherNodes) {
        wakeWaiters(key, deleteRevision);
        if (otherNodes.isEmpty()) {
            return;
        }
        yieldUntil.put(key, System.currentTimeMillis() + config.leaseHandoffGrace().toMillis());
        for (String other : otherNodes) {
            try {
                notifier.publishReleasedTo(other, key, deleteRevision);
            } catch (RuntimeException e) {
                LOGGER.debugf("Release notice for %s to %s not sent: %s", sanitize(key), other, e.getMessage());
            }
        }
    }

    /**
     * The delete at the last revision we knew missed, or the lease is lost. Either
     * it was taken over (a real conflict), or a write of ours landed that we have
     * not recorded — a renewal racing this release, or one whose reply was lost —
     * then the key still holds this lease at a newer revision, and leaving it would
     * hold the conversation for the whole TTL after the turn had finished.
     *
     * @return the delete marker's revision ({@code 0} when the key was already
     *         gone), or {@code -1} when another lease holds the key now
     */
    private long deleteIfStillOurs(Held h) {
        Optional<ISharedKv.Versioned> current = kv.getConsistent(h.key);
        if (current.isEmpty()) {
            return 0; // already gone
        }
        LeaseInfo info = decode(current.get());
        if (isThisBoot(info) && h.lid.equals(leaseId(current.get()))) {
            OptionalLong deleted = kv.deleteReturningRevision(h.key, info.revision());
            if (deleted.isPresent()) {
                return deleted.getAsLong();
            }
        }
        releaseConflicts.increment();
        return -1;
    }

    @Override
    public void stopAcquiring() {
        shuttingDown = true;
        for (Set<Waiter> set : new ArrayList<>(waiters.values())) {
            for (Waiter w : new ArrayList<>(set)) {
                w.future.completeExceptionally(
                        new LeaseUnavailableException(LeaseUnavailableException.Reason.SHUTTING_DOWN, null, "node is shutting down"));
            }
        }
    }

    /**
     * Drains (or undrains) this node: while draining it acquires no lease — a turn
     * that arrives here is answered 409 with {@code Retry-After} at once, so the
     * client retries on another node — and does not lead the HITL recovery. Leases
     * already held stay held until their turns finish. Turns still waiting for a
     * lease when the drain starts are answered the same way.
     * <p>
     * Leader leases ({@code leader.*}) are released at once: a drained node no
     * longer runs the leader's work ({@link #tryAcquireKey} answers empty while
     * draining), so keeping its leader lease alive through the heartbeat would
     * leave that work undone on every node until the drain ended.
     */
    public void setDraining(boolean drain) {
        draining = drain;
        if (drain) {
            for (Set<Waiter> set : new ArrayList<>(waiters.values())) {
                for (Waiter w : new ArrayList<>(set)) {
                    w.future.completeExceptionally(drainingRefusal());
                }
            }
            for (Held h : new ArrayList<>(held.values())) {
                if (h.key.startsWith(LEADER)) {
                    LOGGER.infof("Drained: handing over leader lease %s", sanitize(h.key));
                    release(h);
                }
            }
        }
    }

    public boolean isDraining() {
        return draining;
    }

    private static LeaseUnavailableException drainingRefusal() {
        return new LeaseUnavailableException(LeaseUnavailableException.Reason.DRAINING, null,
                "node is drained by an administrator and takes no new turns");
    }

    /** Called after every takeover (fast path or expiry-based) this node made. */
    public void onTakeover(TakeoverListener listener) {
        takeoverListeners.add(listener);
    }

    private void notifyTakeover(String key, LeaseInfo previous) {
        for (TakeoverListener listener : takeoverListeners) {
            try {
                listener.tookOver(key, previous);
            } catch (RuntimeException e) {
                LOGGER.debugf("Takeover listener failed: %s", e.getMessage());
            }
        }
    }

    /**
     * Administrative release of a lease held by any node.
     * <p>
     * Deletes the key by compare-and-set at the revision found (and, when
     * {@code expectedRevision} is given, only if it still is that one), then wakes
     * the waiters. Safe because of the fence: the next holder's lease has a higher
     * revision, which it raises on the conversation before its turn runs, so a
     * still-alive former holder's late write is refused by the database and
     * dead-lettered — and that holder's next heartbeat finds the lease gone and
     * cancels its turn at the next task boundary. Idempotent: an absent lease is
     * {@link ForceReleaseOutcome#ALREADY_RELEASED}.
     *
     * @throws ClusterUnavailableException
     *             when NATS cannot be reached
     */
    public ForceRelease forceRelease(String key, Long expectedRevision) {
        Optional<ISharedKv.Versioned> current = kv.getConsistent(key);
        if (current.isEmpty()) {
            return new ForceRelease(ForceReleaseOutcome.ALREADY_RELEASED, null, 0);
        }
        LeaseInfo info = decode(current.get());
        if (expectedRevision != null && expectedRevision != info.revision()) {
            return new ForceRelease(ForceReleaseOutcome.RENEWED, info, info.revision());
        }
        if (!kv.delete(key, info.revision())) {
            long now = kv.getConsistent(key).map(ISharedKv.Versioned::revision).orElse(0L);
            return now == 0
                    ? new ForceRelease(ForceReleaseOutcome.ALREADY_RELEASED, info, 0)
                    : new ForceRelease(ForceReleaseOutcome.RENEWED, info, now);
        }
        Held local = held.get(key);
        if (local != null && local.revision == info.revision()) {
            // Held by this very node: stop its turn now instead of at the next heartbeat.
            lose(local, "force_released");
        }
        try {
            // Broadcast: the waiters may be on any node, of any version.
            notifier.publishReleased(key);
        } catch (RuntimeException e) {
            LOGGER.debugf("Release notification for %s not sent: %s", sanitize(key), e.getMessage());
        }
        LOGGER.warnf("Lease %s of node %s (revision %d) force-released by an administrator", sanitize(key), info.node(), info.revision());
        return new ForceRelease(ForceReleaseOutcome.RELEASED, info, info.revision());
    }

    /**
     * One lease with its renewal time and waiting node, or empty when nobody holds
     * it.
     */
    public Optional<LeaseSnapshot> peekSnapshot(String key) {
        Optional<ISharedKv.Versioned> current = kv.getConsistent(key);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String waiting = kv.getConsistent(WAITER_PREFIX + key).map(m -> new String(m.value(), StandardCharsets.UTF_8)).orElse(null);
        return Optional.of(new LeaseSnapshot(key, decode(current.get()), current.get().writtenAt(), waiting));
    }

    /**
     * Every lease currently in the bucket, at most {@code max}, with the node
     * waiting for it where one left a marker.
     */
    public List<LeaseSnapshot> snapshot(int max) {
        List<ISharedKv.Entry> all = kv.entries("", Math.max(max, 1) * 2 + 16);
        Map<String, String> waitingByKey = new HashMap<>();
        for (ISharedKv.Entry e : all) {
            if (e.key().startsWith(WAITER_PREFIX)) {
                waitingByKey.put(e.key().substring(WAITER_PREFIX.length()), new String(e.value().value(), StandardCharsets.UTF_8));
            }
        }
        List<LeaseSnapshot> leases = new ArrayList<>();
        for (ISharedKv.Entry e : all) {
            if (e.key().startsWith(WAITER_PREFIX)) {
                continue;
            }
            if (leases.size() >= max) {
                break;
            }
            // The watch lists the keys; the values are re-read from the stream leader, so a
            // lagging replica cannot show an old holder or revision. Bounded by `max`.
            Optional<ISharedKv.Versioned> value = leases.size() < CONSISTENT_READS ? kv.getConsistent(e.key()) : Optional.of(e.value());
            if (value.isEmpty()) {
                continue; // released since the watch saw it
            }
            leases.add(new LeaseSnapshot(e.key(), decode(value.get()), value.get().writtenAt(), waitingByKey.get(e.key())));
        }
        return leases;
    }

    /**
     * Releases every held lease at shutdown, side by side and within a bounded
     * time. One after another, a few dozen leases against a NATS that answers
     * slowly would outlast the pod's termination grace. A lease this misses expires
     * on the server within the TTL — safe, only slower for the next turn.
     */
    @Override
    public void releaseAll() {
        stopAcquiring();
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        List<CompletableFuture<Void>> releases = new ArrayList<>();
        for (Held h : new ArrayList<>(held.values())) {
            try {
                releases.add(CompletableFuture.runAsync(() -> release(h), io));
            } catch (RuntimeException e) {
                release(h); // executor already gone: release on this thread
            }
        }
        awaitAll(releases, config.natsRequestTimeout().multipliedBy(4));
    }

    private void wakeWaiters(String key, long deleteRevision) {
        Set<Waiter> set = waiters.get(key);
        if (set == null) {
            return;
        }
        for (Waiter w : set) {
            if (deleteRevision > 0) {
                w.hint = deleteRevision;
            }
            ScheduledFuture<?> next = w.next;
            if (next != null) {
                next.cancel(false);
            }
            io.execute(() -> attempt(w));
        }
    }

    // ---------------------------------------------------------------- heartbeat

    /**
     * The scheduler's tick, every quarter heartbeat: marks the leases whose
     * validity ran out as lost — no I/O, so it runs whatever NATS does — and starts
     * a renewal pass on the I/O executor. The lease scheduler also runs every lease
     * waiter's retry and deadline timer, so a pass that waits for a stalled renewal
     * must not run on it.
     */
    void tick() {
        expireUnconfirmed();
        dispatchHeartbeat();
    }

    /** Loses every held lease whose validity ran out. */
    void expireUnconfirmed() {
        long now = System.nanoTime();
        for (Held h : new ArrayList<>(held.values())) {
            if (!h.lost && h.isExpired(now) && h.expiring.compareAndSet(false, true)) {
                expired(h);
            }
        }
        long nowMillis = System.currentTimeMillis();
        yieldUntil.values().removeIf(until -> until < nowMillis);
    }

    /**
     * A lease whose validity ran out: loses it on the I/O executor, so the lost
     * callbacks never run on the lease scheduler or on the thread that asked
     * {@link LeaseHandle#isLost()}.
     */
    private void expired(Held h) {
        try {
            io.execute(() -> lose(h, "unconfirmed"));
        } catch (RejectedExecutionException e) {
            lose(h, "unconfirmed");
        }
    }

    /**
     * Starts a pass renewing the leases due, unless one is still running; the
     * renewals of that pass carry on, and the next tick picks the rest up.
     */
    void dispatchHeartbeat() {
        if (!heartbeatRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            io.execute(() -> {
                try {
                    renewDue();
                } finally {
                    heartbeatRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            heartbeatRunning.set(false); // executor shut down
        }
    }

    /** Renews the leases whose last renewal is at least 3/4 heartbeat old. */
    void renewDue() {
        long now = System.nanoTime();
        List<Held> due = new ArrayList<>();
        for (Held h : held.values()) {
            if (now - h.lastRenewalNanos >= renewDueNanos) {
                due.add(h);
            }
        }
        renew(due, Duration.ofMillis(tickMillis));
    }

    /**
     * Renews every held lease, whether due or not, and waits for the renewals — at
     * most one heartbeat interval.
     */
    void heartbeatAll() {
        renew(new ArrayList<>(held.values()), config.leaseHeartbeatInterval());
    }

    /**
     * Renews {@code leases}, the least recently confirmed first, at most
     * {@value #MAX_CONCURRENT_RENEWALS} in flight. Waits at most {@code budget} for
     * a free slot and for the renewals; what does not start within it is left to
     * the next pass, and a renewal still running then finishes on the I/O executor
     * — its lease is skipped through {@code renewing}, never renewed twice
     * concurrently.
     */
    private void renew(List<Held> leases, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        leases.sort(Comparator.comparingLong(h -> h.confirmedNanos));
        List<CompletableFuture<Void>> renewals = new ArrayList<>();
        for (Held h : leases) {
            if (h.lost || h.released.get() || !h.renewing.compareAndSet(false, true)) {
                continue;
            }
            boolean slot;
            try {
                slot = renewSlots.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                h.renewing.set(false);
                break;
            }
            if (!slot) {
                h.renewing.set(false);
                break;
            }
            CompletableFuture<Void> renewal = new CompletableFuture<>();
            renewals.add(renewal);
            try {
                io.execute(() -> {
                    try {
                        renewOne(h);
                    } finally {
                        h.renewing.set(false);
                        renewSlots.release();
                        renewal.complete(null);
                    }
                });
            } catch (RuntimeException e) {
                // The executor refused (shutting down): nothing to renew any more.
                h.renewing.set(false);
                renewSlots.release();
                renewal.complete(null);
            }
        }
        awaitAll(renewals, Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
    }

    private void renewOne(Held h) {
        if (h.lost || h.released.get()) {
            return;
        }
        long sent = System.nanoTime();
        if (h.isExpired(sent)) {
            lose(h, "unconfirmed");
            return;
        }
        h.lastRenewalNanos = sent;
        try {
            OptionalLong next = kv.update(h.key, encode(h.since, h.lid), h.revision);
            if (next.isPresent()) {
                h.confirm(next.getAsLong(), sent);
            } else if (!h.released.get()) {
                reconcile(h);
            }
        } catch (ClusterUnavailableException e) {
            // Not lost yet: the lease stays valid until its validity, counted from the
            // last acknowledged renewal, runs out — then the tick loses it.
            renewFailures.increment();
            LOGGER.debugf("Heartbeat of %s could not reach NATS: %s", sanitize(h.key), e.getMessage());
        } catch (RuntimeException e) {
            renewFailures.increment();
            LOGGER.debugf("Heartbeat of %s failed: %s", sanitize(h.key), e.getMessage());
        }
    }

    /**
     * A renewal conflicted. Read the key from the stream leader: if it still holds
     * this lease, an earlier renewal was applied although its reply was lost (a
     * timeout, a NATS leader change) — keep the lease, renewing it at the revision
     * found. Anything else is a takeover.
     */
    private void reconcile(Held h) {
        Optional<ISharedKv.Versioned> current = kv.getConsistent(h.key);
        if (current.isEmpty()) {
            lose(h, "gone");
            return;
        }
        LeaseInfo info = decode(current.get());
        if (!isThisBoot(info) || !h.lid.equals(leaseId(current.get()))) {
            lose(h, "taken_over");
            return;
        }
        long sent = System.nanoTime();
        OptionalLong next = kv.update(h.key, encode(h.since, h.lid), info.revision());
        if (next.isPresent()) {
            h.confirm(next.getAsLong(), sent);
            meterRegistry.counter("eddi.cluster.lease.adopted", "op", "renew").increment();
            LOGGER.infof("Lease %s: a renewal had been applied although its reply was lost — kept at revision %d", sanitize(h.key),
                    next.getAsLong());
        } else if (!h.released.get()) {
            lose(h, "taken_over");
        }
    }

    /**
     * Waits for {@code tasks}, at most {@code budget}. Every task is itself bounded
     * by the NATS request timeout; the budget only keeps a stuck executor from
     * holding the caller forever.
     */
    private static void awaitAll(List<CompletableFuture<Void>> tasks, Duration budget) {
        if (tasks.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(budget.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.debugf("Lease batch did not complete within %s: %s", budget, e.getMessage());
        }
    }

    private void lose(Held h, String reason) {
        held.remove(h.key, h);
        if (!held.containsKey(h.key)) {
            waitingNodes.remove(h.key);
        }
        if (h.released.get()) {
            return; // the turn already finished with it
        }
        List<Runnable> callbacks = h.claimLost();
        if (callbacks == null) {
            return; // already lost
        }
        meterRegistry.counter("eddi.cluster.lease.lost", "reason", reason).increment();
        LOGGER.warnf("Lost lease %s (%s) — the running turn is cancelled at its next task boundary and its write is fenced",
                sanitize(h.key), reason);
        for (Runnable r : callbacks) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Lease-lost callback failed for %s", sanitize(h.key));
            }
        }
    }

    /**
     * Deletes leases this node id still holds under an earlier boot id, so a
     * restarted pod does not wait out its own leases. Runs once per process, on the
     * first connect that lets it finish: leases of an earlier boot can only exist
     * from before this process started, so a reconnect has nothing to add — and
     * every node scanning the whole bucket on every reconnect, all at once, was the
     * cost of an outage. One watch lists the bucket instead of a read per key.
     * <p>
     * It runs only once presence has confirmed this node owns its id
     * ({@link ClusterView#isNodeIdentityConfirmed()}): on a twin's first connect
     * presence has not yet told a live twin from a dead predecessor, and sweeping
     * then would delete the live twin's leases. Presence calls this again when it
     * confirms the id.
     */
    public void sweepOwnStaleLeases() {
        if (swept.get()) {
            return;
        }
        if (view.isDuplicateNode()) {
            // An earlier boot's leases cannot be told from a live twin's; retried when
            // presence confirms the id, or on the next connect.
            LOGGER.warn("Stale-lease sweep skipped: another live JVM uses this node id");
            return;
        }
        if (!view.isNodeIdentityConfirmed()) {
            LOGGER.debug("Stale-lease sweep deferred until presence confirms this node's id");
            return;
        }
        try {
            for (ISharedKv.Entry e : kv.entries("", Integer.MAX_VALUE)) {
                if (e.key().startsWith(WAITER_PREFIX)) {
                    continue;
                }
                LeaseInfo info = decode(e.value());
                if (node.nodeId().equals(info.node()) && !node.bootId().equals(info.boot()) && kv.delete(e.key(), info.revision())) {
                    takeovers.increment();
                    notifyTakeover(e.key(), info);
                    LOGGER.infof("Released lease %s left by this node's previous boot %s", sanitize(e.key()), info.boot());
                }
            }
            swept.set(true);
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Stale-lease sweep skipped (retried on the next connect): %s", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- peek

    @Override
    public Optional<LeaseInfo> peekKey(String key) {
        try {
            return peekKeyStrict(key);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<LeaseInfo> peekKeyStrict(String key) {
        return kv.get(key).map(this::decode);
    }

    @Override
    public int heldCount() {
        return held.size();
    }

    // ---------------------------------------------------------------- encoding

    /**
     * The lease value. {@code node}, {@code boot} and {@code since} are what every
     * version reads; {@code lid} — the id of this lease within this process — is
     * new, and earlier versions ignore it.
     */
    private byte[] encode(long since, String lid) {
        ObjectNode value = JSON.createObjectNode();
        value.put("node", node.nodeId());
        value.put("boot", node.bootId());
        value.put("since", since);
        value.put("lid", lid);
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    LeaseInfo decode(ISharedKv.Versioned v) {
        try {
            JsonNode n = JSON.readTree(v.value());
            return new LeaseInfo(n.path("node").asText(""), n.path("boot").asText(""), v.revision(), n.path("since").asLong(0));
        } catch (IOException e) {
            return new LeaseInfo("", "", v.revision(), 0);
        }
    }

    /** The lease id in a value, or {@code null} (written by an earlier version). */
    static String leaseId(ISharedKv.Versioned v) {
        try {
            JsonNode lid = JSON.readTree(v.value()).get("lid");
            return lid == null || lid.isNull() ? null : lid.asText();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- types

    private static final class Waiter {
        final String key;
        final long deadlineNanos;
        final String lid;
        final long startNanos = System.nanoTime();
        final CompletableFuture<LeaseHandle> future = new CompletableFuture<>();
        final AtomicBoolean attempting = new AtomicBoolean();
        volatile ScheduledFuture<?> next;
        volatile boolean contended;
        /** Revision of this waiter's {@code w.<key>} marker; 0 not written, -1 none. */
        volatile long markerRevision;
        volatile boolean takenOver;
        volatile boolean rerun;
        volatile long notBeforeNanos;
        volatile String lastHolder;
        /** The delete marker's revision from a release notification, or 0. */
        volatile long hint;

        Waiter(String key, long deadlineNanos, String lid) {
            this.key = key;
            this.deadlineNanos = deadlineNanos;
            this.lid = lid;
        }
    }

    static final class Held implements LeaseHandle {
        final String key;
        final long fence;
        final String lid;
        final long since = System.currentTimeMillis();
        private final long validityNanos;
        private final Consumer<Held> onExpired;
        volatile boolean takenOver;
        volatile long revision;
        /** When the last write of this lease that was acknowledged had been sent. */
        volatile long confirmedNanos;
        /** When the last renewal was started. */
        volatile long lastRenewalNanos;
        volatile boolean lost;
        final AtomicBoolean released = new AtomicBoolean();
        /** Turns this lease was handed to without a release. */
        volatile int handoffs;
        /** A heartbeat renewal of this lease is in flight. */
        final AtomicBoolean renewing = new AtomicBoolean();
        /** Its expiry has been handed to {@code onExpired} (once). */
        final AtomicBoolean expiring = new AtomicBoolean();
        private final List<Runnable> lostCallbacks = new ArrayList<>();

        Held(String key, long fence, boolean takenOver, String lid, long sentNanos, long validityNanos, Consumer<Held> onExpired) {
            this.key = key;
            this.fence = fence;
            this.revision = fence;
            this.takenOver = takenOver;
            this.lid = lid;
            this.confirmedNanos = sentNanos;
            this.lastRenewalNanos = sentNanos;
            this.validityNanos = validityNanos;
            this.onExpired = onExpired;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String conversationId() {
            int dot = key.indexOf('.');
            return dot >= 0 ? key.substring(dot + 1) : key;
        }

        @Override
        public Long fence() {
            return fence;
        }

        /**
         * True once the lease was taken over or force-released, and as soon as its
         * validity ran out without an acknowledged renewal — whether or not the tick
         * has noticed yet (then the lost callbacks are started from here).
         */
        @Override
        public boolean isLost() {
            if (lost) {
                return true;
            }
            if (isExpired(System.nanoTime())) {
                if (!released.get() && expiring.compareAndSet(false, true)) {
                    onExpired.accept(this);
                }
                return true;
            }
            return false;
        }

        boolean isExpired(long nowNanos) {
            return nowNanos - confirmedNanos >= validityNanos;
        }

        synchronized void confirm(long newRevision, long sentNanos) {
            revision = newRevision;
            if (sentNanos - confirmedNanos > 0) {
                confirmedNanos = sentNanos;
            }
        }

        @Override
        public boolean wasTakenOver() {
            return takenOver;
        }

        @Override
        public void onLost(Runnable callback) {
            boolean runNow;
            synchronized (lostCallbacks) {
                runNow = lost;
                if (!runNow) {
                    lostCallbacks.add(callback);
                }
            }
            if (runNow) {
                callback.run();
            }
        }

        /**
         * Marks the lease lost; the callbacks to run, or {@code null} if it already
         * was.
         */
        List<Runnable> claimLost() {
            synchronized (lostCallbacks) {
                if (lost) {
                    return null;
                }
                lost = true;
                List<Runnable> callbacks = new ArrayList<>(lostCallbacks);
                lostCallbacks.clear();
                return callbacks;
            }
        }

        /** Resets the handle for the next turn it is handed to. */
        void prepareForNextTurn() {
            synchronized (lostCallbacks) {
                lostCallbacks.clear();
            }
            takenOver = false;
            handoffs++;
        }
    }
}
