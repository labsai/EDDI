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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Leases on a shared KV bucket ({@code <prefix>_LEASES} in NATS mode).
 * <p>
 * <b>Acquire</b> is a KV {@code create}: it succeeds only while the key is
 * absent, and the revision it returns is the fencing token — KV revisions are
 * the bucket stream's sequence numbers and only ever grow. A failed create
 * parks a waiter, which wakes on
 * <ul>
 * <li>the holder's release notification (core NATS, sub-millisecond),</li>
 * <li>a jittered poll every {@code ttl/4}, which is what notices that a crashed
 * holder's lease expired on the server (server-side TTL, so no clock skew
 * between nodes is involved), and</li>
 * <li>fast takeover: a lease whose holder's presence record is gone (or carries
 * a different boot id, i.e. it restarted) is deleted by compare-and-set and
 * re-acquired at once.</li>
 * </ul>
 * <b>Heartbeat</b>: one node-wide task refreshes every held lease each
 * {@code heartbeat-interval} with a compare-and-set on the last revision. A
 * revision mismatch means the lease was taken over: the handle fires
 * {@link LeaseHandle#onLost}, which cancels the running turn. An I/O failure is
 * ignored — the database fence decides whether the turn's write lands.
 * <p>
 * <b>Fairness</b>: a waiter leaves a {@code w.<key>} marker. A holder that sees
 * another node's marker when it releases holds back its own next acquire of
 * that key for {@code handoff-grace}, so a busy node cannot starve the others.
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

    /** Core NATS release notifications. */
    public interface LeaseNotifier {
        void publishReleased(String key);

        void onReleased(Consumer<String> listener);
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
    }

    private final ISharedKv kv;
    private final NodeIdentity node;
    private final ClusterConfig config;
    private final ScheduledExecutorService scheduler;
    private final Executor io;
    private final LeaseNotifier notifier;
    private final ClusterView view;
    private final MeterRegistry meterRegistry;

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Map<String, Set<Waiter>> waiters = new ConcurrentHashMap<>();
    private final Map<String, Long> yieldUntil = new ConcurrentHashMap<>();
    /**
     * What this node last saw in a contended lease whose holder has no presence
     * record: the revision and when (monotonic clock) it was first seen.
     */
    private final Map<String, Observation> observed = new ConcurrentHashMap<>();

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
    private ScheduledFuture<?> heartbeat;

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
        this.takeovers = Counter.builder("eddi.cluster.lease.takeover")
                .description("Leases taken over from a holder that had died or restarted").register(meterRegistry);
        this.releaseConflicts = Counter.builder("eddi.cluster.lease.release.conflicts")
                .description("Releases that found the lease already taken over").register(meterRegistry);
        Gauge.builder("eddi.cluster.lease.held", held, Map::size).description("Leases this node holds").register(meterRegistry);
        notifier.onReleased(this::wakeWaiters);
    }

    /** Starts the node-wide heartbeat. */
    public void start() {
        long period = config.leaseHeartbeatInterval().toMillis();
        heartbeat = scheduler.scheduleAtFixedRate(this::heartbeatAll, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    // ---------------------------------------------------------------- acquire

    @Override
    public CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait) {
        Waiter waiter = new Waiter(key, System.nanoTime() + maxWait.toNanos());
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
            OptionalLong revision = kv.create(w.key, encode(System.currentTimeMillis()));
            if (revision.isPresent()) {
                grant(w, revision.getAsLong());
                return;
            }
            w.contended = true;
            Optional<ISharedKv.Versioned> current = kv.get(w.key);
            if (current.isEmpty()) {
                // Released between our create and our read — try again at once.
                observed.remove(w.key);
                reschedule(w, 1);
                return;
            }
            LeaseInfo info = decode(current.get());
            w.lastHolder = info.node();
            if (isStale(w.key, info) && kv.delete(w.key, info.revision())) {
                observed.remove(w.key);
                takeovers.increment();
                notifyTakeover(w.key, info);
                w.takenOver = true;
                LOGGER.infof("Took over lease %s from %s/%s (holder gone)", sanitize(w.key), info.node(), info.boot());
                reschedule(w, 0);
                return;
            }
            if (!w.markerWritten) {
                w.markerWritten = true;
                try {
                    kv.put(WAITER_PREFIX + w.key, node.nodeId().getBytes(StandardCharsets.UTF_8));
                } catch (ClusterUnavailableException ignored) {
                    // fairness only
                }
            }
            reschedule(w, pollDelayMillis());
        } catch (ClusterUnavailableException e) {
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

    private long pollDelayMillis() {
        long base = Math.max(50, config.leaseTtl().toMillis() / 4);
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
            // Our own earlier incarnation (same node id, different boot) — or, if the
            // boot matches, a lease this process holds under the same key, which the
            // local FIFO makes impossible for conversations; never take that over.
            return !node.bootId().equals(info.boot());
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

    private void grant(Waiter w, long revision) {
        Held h = new Held(w.key, revision, w.takenOver);
        observed.remove(w.key);
        held.put(w.key, h);
        if (!w.future.complete(h)) {
            // The waiter timed out in the meantime: give it straight back.
            release(h);
            return;
        }
        record(w, "acquired");
        if (w.markerWritten) {
            try {
                kv.delete(WAITER_PREFIX + w.key);
            } catch (ClusterUnavailableException ignored) {
                // expires with the bucket TTL
            }
        }
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
        if (existing != null && !existing.lost) {
            return Optional.of(existing);
        }
        try {
            OptionalLong revision = kv.create(key, encode(System.currentTimeMillis()));
            if (revision.isEmpty()) {
                Optional<ISharedKv.Versioned> current = kv.get(key);
                if (current.isPresent()) {
                    LeaseInfo info = decode(current.get());
                    if (isStale(key, info) && kv.delete(key, info.revision())) {
                        observed.remove(key);
                        takeovers.increment();
                        notifyTakeover(key, info);
                        revision = kv.create(key, encode(System.currentTimeMillis()));
                    }
                }
            }
            if (revision.isEmpty()) {
                return Optional.empty();
            }
            Held h = new Held(key, revision.getAsLong(), false);
            observed.remove(key);
            held.put(key, h);
            return Optional.of(h);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- release

    @Override
    public void release(LeaseHandle handle) {
        if (!(handle instanceof Held h) || !held.remove(h.key, h)) {
            return; // unfenced, foreign or already released
        }
        h.released = true;
        if (h.lost) {
            return;
        }
        try {
            if (!kv.delete(h.key, h.revision)) {
                deleteIfStillOurs(h);
            }
            notifier.publishReleased(h.key);
            Optional<ISharedKv.Versioned> marker = kv.get(WAITER_PREFIX + h.key);
            if (marker.isPresent()) {
                String waiterNode = new String(marker.get().value(), StandardCharsets.UTF_8);
                if (!node.nodeId().equals(waiterNode)) {
                    yieldUntil.put(h.key, System.currentTimeMillis() + config.leaseHandoffGrace().toMillis());
                }
            }
        } catch (ClusterUnavailableException e) {
            // The lease expires on the server within the TTL; waiters poll for that.
            LOGGER.debugf("Lease release of %s could not reach NATS: %s", sanitize(h.key), e.getMessage());
        }
    }

    /**
     * The delete at the last revision we knew missed. Either the lease was taken
     * over (a real conflict), or our own heartbeat renewed it between that revision
     * being read and this release — then the key is still ours at a newer revision,
     * and leaving it would hold the conversation for the whole TTL after the turn
     * had finished.
     */
    private void deleteIfStillOurs(Held h) {
        Optional<ISharedKv.Versioned> current = kv.get(h.key);
        if (current.isEmpty()) {
            return; // already gone
        }
        LeaseInfo info = decode(current.get());
        if (node.nodeId().equals(info.node()) && node.bootId().equals(info.boot()) && kv.delete(h.key, info.revision())) {
            return;
        }
        releaseConflicts.increment();
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
     */
    public void setDraining(boolean drain) {
        draining = drain;
        if (drain) {
            for (Set<Waiter> set : new ArrayList<>(waiters.values())) {
                for (Waiter w : new ArrayList<>(set)) {
                    w.future.completeExceptionally(drainingRefusal());
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
        Optional<ISharedKv.Versioned> current = kv.get(key);
        if (current.isEmpty()) {
            return new ForceRelease(ForceReleaseOutcome.ALREADY_RELEASED, null, 0);
        }
        LeaseInfo info = decode(current.get());
        if (expectedRevision != null && expectedRevision != info.revision()) {
            return new ForceRelease(ForceReleaseOutcome.RENEWED, info, info.revision());
        }
        if (!kv.delete(key, info.revision())) {
            long now = kv.get(key).map(ISharedKv.Versioned::revision).orElse(0L);
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
        Optional<ISharedKv.Versioned> current = kv.get(key);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        String waiting = kv.get(WAITER_PREFIX + key).map(m -> new String(m.value(), StandardCharsets.UTF_8)).orElse(null);
        return Optional.of(new LeaseSnapshot(key, decode(current.get()), current.get().writtenAt(), waiting));
    }

    /**
     * Every lease currently in the bucket whose key starts with {@code prefix}, at
     * most {@code max}, with the node waiting for it where one left a marker.
     */
    public List<LeaseSnapshot> snapshot(String prefix, int max) {
        List<ISharedKv.Entry> all = kv.entries("", Math.max(max, 1) * 2 + 16);
        Map<String, String> waitingNodes = new HashMap<>();
        for (ISharedKv.Entry e : all) {
            if (e.key().startsWith(WAITER_PREFIX)) {
                waitingNodes.put(e.key().substring(WAITER_PREFIX.length()), new String(e.value().value(), StandardCharsets.UTF_8));
            }
        }
        List<LeaseSnapshot> leases = new ArrayList<>();
        for (ISharedKv.Entry e : all) {
            if (e.key().startsWith(WAITER_PREFIX) || (prefix != null && !e.key().startsWith(prefix))) {
                continue;
            }
            if (leases.size() >= max) {
                break;
            }
            leases.add(new LeaseSnapshot(e.key(), decode(e.value()), e.value().writtenAt(), waitingNodes.get(e.key())));
        }
        return leases;
    }

    @Override
    public void releaseAll() {
        stopAcquiring();
        for (Held h : new ArrayList<>(held.values())) {
            release(h);
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private void wakeWaiters(String key) {
        Set<Waiter> set = waiters.get(key);
        if (set == null) {
            return;
        }
        for (Waiter w : set) {
            ScheduledFuture<?> next = w.next;
            if (next != null) {
                next.cancel(false);
            }
            io.execute(() -> attempt(w));
        }
    }

    // ---------------------------------------------------------------- heartbeat

    void heartbeatAll() {
        for (Held h : new ArrayList<>(held.values())) {
            if (h.lost || h.released) {
                continue;
            }
            try {
                OptionalLong next = kv.update(h.key, encode(h.since), h.revision);
                if (next.isPresent()) {
                    h.revision = next.getAsLong();
                } else if (!h.released) {
                    lose(h, "taken_over");
                }
            } catch (ClusterUnavailableException e) {
                // Keep running: the DB fence decides whether the turn's write lands.
            } catch (RuntimeException e) {
                LOGGER.debugf("Heartbeat of %s failed: %s", sanitize(h.key), e.getMessage());
            }
        }
    }

    private void lose(Held h, String reason) {
        held.remove(h.key, h);
        meterRegistry.counter("eddi.cluster.lease.lost", "reason", reason).increment();
        LOGGER.warnf("Lost lease %s (%s) — the running turn is cancelled at its next task boundary and its write is fenced",
                sanitize(h.key), reason);
        h.fireLost();
    }

    /**
     * Deletes leases this node id still holds under an earlier boot id, so a
     * restarted pod does not wait out its own leases. Runs on every (re)connect.
     */
    public void sweepOwnStaleLeases() {
        try {
            for (String key : kv.keys()) {
                if (key.startsWith(WAITER_PREFIX)) {
                    continue;
                }
                Optional<ISharedKv.Versioned> v = kv.get(key);
                if (v.isEmpty()) {
                    continue;
                }
                LeaseInfo info = decode(v.get());
                if (node.nodeId().equals(info.node()) && !node.bootId().equals(info.boot()) && kv.delete(key, info.revision())) {
                    takeovers.increment();
                    LOGGER.infof("Released lease %s left by this node's previous boot %s", sanitize(key), info.boot());
                }
            }
        } catch (ClusterUnavailableException e) {
            LOGGER.debugf("Stale-lease sweep skipped: %s", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- peek

    @Override
    public Optional<LeaseInfo> peekKey(String key) {
        try {
            return kv.get(key).map(this::decode);
        } catch (ClusterUnavailableException e) {
            return Optional.empty();
        }
    }

    @Override
    public int heldCount() {
        return held.size();
    }

    // ---------------------------------------------------------------- encoding

    private byte[] encode(long since) {
        ObjectNode value = JSON.createObjectNode();
        value.put("node", node.nodeId());
        value.put("boot", node.bootId());
        value.put("since", since);
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

    // ---------------------------------------------------------------- types

    private static final class Waiter {
        final String key;
        final long deadlineNanos;
        final long startNanos = System.nanoTime();
        final CompletableFuture<LeaseHandle> future = new CompletableFuture<>();
        final AtomicBoolean attempting = new AtomicBoolean();
        volatile ScheduledFuture<?> next;
        volatile boolean contended;
        volatile boolean markerWritten;
        volatile boolean takenOver;
        volatile boolean rerun;
        volatile long notBeforeNanos;
        volatile String lastHolder;

        Waiter(String key, long deadlineNanos) {
            this.key = key;
            this.deadlineNanos = deadlineNanos;
        }
    }

    static final class Held implements LeaseHandle {
        final String key;
        final long fence;
        final long since = System.currentTimeMillis();
        final boolean takenOver;
        volatile long revision;
        volatile boolean lost;
        volatile boolean released;
        private final List<Runnable> lostCallbacks = new ArrayList<>();

        Held(String key, long fence, boolean takenOver) {
            this.key = key;
            this.fence = fence;
            this.revision = fence;
            this.takenOver = takenOver;
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

        @Override
        public boolean isLost() {
            return lost;
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

        void fireLost() {
            List<Runnable> callbacks;
            synchronized (lostCallbacks) {
                if (lost) {
                    return;
                }
                lost = true;
                callbacks = new ArrayList<>(lostCallbacks);
                lostCallbacks.clear();
            }
            for (Runnable r : callbacks) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOGGER.warnf(e, "Lease-lost callback failed for %s", sanitize(key));
                }
            }
        }
    }
}
