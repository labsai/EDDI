/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.PendingRemoval;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The erasure guarantee for rows written asynchronously: an erasure (or an
 * explicit conversation delete) reports success only when no guarded row of it
 * exists <b>and none can be written later</b>; when that cannot be ensured, it
 * reports a failed step instead.
 *
 * <h3>How</h3>
 * <ol>
 * <li><b>Tombstones first.</b> Before anything is deleted, the erasure records
 * a durable tombstone of the user (a keyed hash, see {@link #userKey}) and of
 * each of the user's conversations ({@link #tombstoneUser},
 * {@link #tombstoneConversation}).</li>
 * <li><b>Drain barrier.</b> Every node then waits until the work its
 * {@link AsyncErasureWriter}s had queued before that point has finished
 * ({@link #drainLocal}; the cluster stop handshake and the
 * {@value IClusterRpc#ERASURE_DRAIN} RPC run it on every other node). Work
 * queued later runs after the tombstone is committed, so {@link #beforeWrite}
 * refuses it.</li>
 * <li><b>Delete, then verify</b> ({@link #verifyUserErased},
 * {@link #eraseConversation}): no row left and no pending removal, or the step
 * fails.</li>
 * </ol>
 * {@link #afterWrite} closes the remaining gap — a write that passed
 * {@link #beforeWrite} just before the tombstone and landed after the delete
 * (on a node the barrier could not reach): it removes its own row, or records a
 * durable pending removal that is retried and that keeps every erasure of that
 * user or conversation failed until it is gone.
 *
 * <h3>Fail closed</h3> A tombstone that cannot be read is treated as present:
 * the write is skipped (or removed again). No tombstone is cached — a cache
 * that missed a tombstone written before a barrier completed would break the
 * guarantee — so a guarded write costs two or three indexed point reads (the
 * key check value, then the tombstones), on the writer's background thread.
 */
@ApplicationScoped
public class ErasureGuard {

    private static final Logger LOGGER = Logger.getLogger(ErasureGuard.class);

    static final String TOMBSTONE_RETENTION_PROPERTY = "eddi.gdpr.tombstone-retention";
    static final String DRAIN_TIMEOUT_PROPERTY = "eddi.gdpr.drain-timeout";
    static final String CLOCK_SKEW_PROPERTY = "eddi.gdpr.tombstone-clock-skew";

    /**
     * The shortest {@code eddi.gdpr.tombstone-retention} accepted, before the drain
     * waits and the clock skew are added ({@link #minimumRetention}).
     */
    static final Duration MIN_RETENTION_BASE = Duration.ofDays(1);

    /** How many pending removals one retry pass handles. */
    static final int RETRY_BATCH = 500;

    /**
     * How far a pending removal that failed again is moved back, so the next
     * maintenance pass reaches the ones behind it first.
     */
    static final Duration RETRY_DEFER = Duration.ofMinutes(5);

    /** Removes one row a writer has just written. */
    @FunctionalInterface
    public interface RowRemover {
        void remove() throws Exception;
    }

    /** Thrown when an erasure of a conversation could not be guaranteed. */
    public static class ErasureIncompleteException extends Exception {
        public ErasureIncompleteException(String message) {
            super(message);
        }
    }

    private final IErasureTombstoneStore store;
    private final Instance<AsyncErasureWriter> writers;
    private final ErasureMetrics metrics;
    private final Duration retention;
    private final Duration drainTimeout;
    private final Duration clockSkew;
    private final Clock clock;
    private volatile TombstoneKey tombstoneKey;
    private final AtomicLong lastMismatchLog = new AtomicLong();
    /** The writers when built without CDI; otherwise null. */
    private Iterable<AsyncErasureWriter> writerList;

    /** Cluster mode: the conversation-delete barrier on every other node. */
    @Inject
    IClusterRpc clusterRpc;

    /**
     * The secrets vault, the source of the tombstone key when it is configured.
     * Field-injected; null when built without CDI.
     */
    @Inject
    Instance<VaultSecretProvider> vault;

    /**
     * Test seam: the vault subkey (returning null: no vault). Overrides
     * {@link #vault} when set.
     */
    Supplier<byte[]> vaultKey;

    /** How long a cluster drain waits for the other nodes' replies. */
    @ConfigProperty(name = "eddi.cluster.gdpr-stop-timeout", defaultValue = "10s")
    Duration clusterTimeout = Duration.ofSeconds(10);

    @Inject
    public ErasureGuard(IErasureTombstoneStore store, Instance<AsyncErasureWriter> writers, ErasureMetrics metrics,
            @ConfigProperty(name = TOMBSTONE_RETENTION_PROPERTY, defaultValue = "400d") Duration retention,
            @ConfigProperty(name = DRAIN_TIMEOUT_PROPERTY, defaultValue = "30s") Duration drainTimeout,
            @ConfigProperty(name = CLOCK_SKEW_PROPERTY, defaultValue = "5m") Duration clockSkew) {
        this(store, writers, metrics, retention, drainTimeout, clockSkew, Clock.systemUTC());
    }

    /** Without CDI: the writers as a plain list (tests). */
    public ErasureGuard(IErasureTombstoneStore store, Iterable<AsyncErasureWriter> writers, ErasureMetrics metrics, Duration retention,
            Duration drainTimeout, Duration clockSkew, Clock clock) {
        this(store, (Instance<AsyncErasureWriter>) null, metrics, retention, drainTimeout, clockSkew, clock);
        this.writerList = writers;
    }

    private ErasureGuard(IErasureTombstoneStore store, Instance<AsyncErasureWriter> writers, ErasureMetrics metrics, Duration retention,
            Duration drainTimeout, Duration clockSkew, Clock clock) {
        if (clockSkew.isNegative()) {
            // A negative skew would pull the user cutoff before the erasure and let
            // content captured right at it through, clocks in sync or not.
            throw new IllegalArgumentException(CLOCK_SKEW_PROPERTY + " is " + clockSkew + "; it must not be negative (0 is allowed)");
        }
        if (drainTimeout.isNegative()) {
            throw new IllegalArgumentException(DRAIN_TIMEOUT_PROPERTY + " is " + drainTimeout + "; it must not be negative");
        }
        Duration minimum = minimumRetention(drainTimeout, clockSkew);
        if (retention.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(TOMBSTONE_RETENTION_PROPERTY + " is " + retention + ", shorter than the minimum " + minimum
                    + " (1 day plus twice " + DRAIN_TIMEOUT_PROPERTY + " plus " + CLOCK_SKEW_PROPERTY + "): a tombstone that expires "
                    + "while its erasure is still draining or deleting lets a late write through after the erasure reported success");
        }
        this.store = store;
        this.writers = writers;
        this.metrics = metrics;
        this.retention = retention;
        this.drainTimeout = drainTimeout;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    /**
     * The shortest tombstone retention accepted: a tombstone must outlive the
     * erasure that wrote it — its local drain (up to {@code drainTimeout}), its
     * cluster drain (up to {@code eddi.cluster.gdpr-stop-timeout} plus
     * {@code drainTimeout}), the deletes and the verification — and the clock skew
     * its user tombstone is compared with. The cluster stop timeout and the delete
     * and verification phase are covered by the one-day base, a conservative
     * margin, not an exact bound.
     */
    static Duration minimumRetention(Duration drainTimeout, Duration clockSkew) {
        return MIN_RETENTION_BASE.plus(drainTimeout.multipliedBy(2)).plus(clockSkew);
    }

    /**
     * Builds the bean at startup, so a tombstone retention the constructor refuses
     * fails the start instead of every later guarded write and erasure.
     */
    void onStart(@Observes StartupEvent event) {
        LOGGER.debugf("Erasure guard ready (tombstone retention %s)", retention);
    }

    // ------------------------------------------------------------ write side

    /**
     * The verdict of {@link #beforeWrite}, and what {@link #afterWrite} checks the
     * stored row against: the scope the check resolved — the user's tombstone key,
     * the conversation, the capture time. Carrying the resolved key means the
     * post-write check and a pending removal it records need no second key lookup,
     * so a key-check read that fails after the write cannot leave a removal without
     * its user scope.
     */
    public static final class Admission {

        private static final Admission REFUSED = new Admission(false, false, null, null, null);
        private static final Admission CHECK_FAILED = new Admission(false, true, null, null, null);

        private final boolean allowed;
        private final boolean checkFailed;
        private final String userKey;
        private final String conversationId;
        private final Instant capturedAt;

        private Admission(boolean allowed, boolean checkFailed, String userKey, String conversationId, Instant capturedAt) {
            this.allowed = allowed;
            this.checkFailed = checkFailed;
            this.userKey = userKey;
            this.conversationId = conversationId;
            this.capturedAt = capturedAt;
        }

        /** Whether the writer may write the row; {@code false}: skip it. */
        public boolean allowed() {
            return allowed;
        }

        /**
         * Whether a refusal is because the tombstones could not be read (fail closed),
         * not because the user or conversation was erased — a refusal a writer may
         * report as retryable. Never {@code true} for an allowed write.
         */
        public boolean checkFailed() {
            return checkFailed;
        }
    }

    /**
     * Whether an asynchronous writer may write a row of {@code userId} and
     * {@code conversationId} captured at {@code capturedAt}. Refused when either
     * was erased — the user: at or after {@code capturedAt}, give or take
     * {@code eddi.gdpr.tombstone-clock-skew}; the conversation: ever — or when that
     * cannot be checked (fail closed). Pass the returned admission to
     * {@link #afterWrite} once the row is stored.
     *
     * @param capturedAt
     *            when the content was captured; null is treated as "before any
     *            erasure"
     */
    public Admission beforeWrite(String userId, String conversationId, Instant capturedAt) {
        try {
            String userKey = userId == null ? null : userKey(KvKeys.sha256(userId));
            if (erased(userKey, conversationId, capturedAt)) {
                metrics.writeSkipped(ErasureMetrics.REASON_TOMBSTONE);
                return Admission.REFUSED;
            }
            return new Admission(true, false, userKey, conversationId, capturedAt);
        } catch (RuntimeException e) {
            LOGGER.warnf("Erasure tombstones could not be read; skipping a guarded write: %s", e.toString());
            metrics.writeSkipped(ErasureMetrics.REASON_CHECK_FAILED);
            return Admission.CHECK_FAILED;
        }
    }

    /**
     * Called by a writer right after it stored the row {@code admission} allowed.
     * When the row's user or conversation has been erased meanwhile (or that cannot
     * be checked), the row is removed through {@code remover}; if that fails, a
     * durable pending removal is recorded, under the user key and conversation the
     * admission resolved, and retried later. The check uses the tombstone key
     * {@link #beforeWrite} resolved rather than resolving it again: a key replaced
     * between the two is outside supported operation (the reset stops every node).
     *
     * @return {@code true} when the writer must withhold the row — not deliver or
     *         use it further. Usually it was removed or a pending removal was
     *         recorded, but not always: when the store fails for both, the guard
     *         only logs an ERROR and counts it, and the erasure's own delete must
     *         reach the row
     * @throws IllegalArgumentException
     *             for a refused admission: the writer must not have written
     */
    public boolean afterWrite(String writer, String rowId, Admission admission, RowRemover remover) {
        Objects.requireNonNull(admission, "admission");
        if (!admission.allowed) {
            throw new IllegalArgumentException("afterWrite for a write beforeWrite refused");
        }
        boolean erased;
        try {
            erased = erased(admission.userKey, admission.conversationId, admission.capturedAt);
        } catch (RuntimeException e) {
            LOGGER.warnf("Erasure tombstones could not be read after a guarded write; removing the row: %s", e.toString());
            erased = true;
        }
        if (!erased) {
            return false;
        }
        try {
            remover.remove();
            metrics.selfDeleted(writer);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("A %s row of an erased user or conversation could not be removed; recording it for a retry: %s", writer,
                    e.toString());
            recordPending(writer, rowId, admission.userKey, admission.conversationId);
            return true;
        }
    }

    private void recordPending(String writer, String rowId, String userKey, String conversationId) {
        try {
            store.addPendingRemoval(new PendingRemoval(writer, rowId, userKey, conversationId, clock.instant()));
            metrics.pendingRemoval(ErasureMetrics.PENDING_RECORDED);
        } catch (RuntimeException e) {
            // Neither removed nor recorded. The erasure's own delete, which runs after the
            // drain barrier, still reaches the row (and its verification counts it);
            // this is only the defence-in-depth path failing, so say it loudly.
            metrics.pendingRemoval(ErasureMetrics.PENDING_RECORD_FAILED);
            LOGGER.errorf("GDPR: a %s row (%s) of an erased user or conversation could neither be removed nor recorded for a retry: %s",
                    writer, sanitize(rowId), e.toString());
        }
    }

    /** {@code userKey}: the user's tombstone key ({@link #userKey}), or null. */
    private boolean erased(String userKey, String conversationId, Instant capturedAt) {
        Instant now = clock.instant();
        if (conversationId != null && store.erasedAt(Kind.CONVERSATION, conversationId, now).isPresent()) {
            return true;
        }
        if (userKey == null) {
            return false;
        }
        Optional<Instant> erasedAt = store.erasedAt(Kind.USER, userKey, now);
        return erasedAt.isPresent() && (capturedAt == null || !capturedAt.isAfter(erasedAt.get().plus(clockSkew)));
    }

    /**
     * The read-side twin of {@link #beforeWrite}, for a reader that hands stored
     * rows on outside EDDI (an export): whether a row of {@code userId} and
     * {@code conversationId} captured at {@code capturedAt} belongs to an erasure —
     * by the same rule as {@link #beforeWrite} — so the reader withholds it even
     * when it read the row before the erasure deleted it. Tombstones are written
     * before anything is deleted, so a row checked after the erasure began is
     * caught. Counts no skipped write. Fails closed: {@code true} when the
     * tombstones cannot be read.
     */
    public boolean erasedForReading(String userId, String conversationId, Instant capturedAt) {
        try {
            String userKey = userId == null ? null : userKey(KvKeys.sha256(userId));
            return erased(userKey, conversationId, capturedAt);
        } catch (RuntimeException e) {
            LOGGER.warnf("Erasure tombstones could not be read; withholding a row from a reader: %s", e.toString());
            return true;
        }
    }

    // ------------------------------------------------------------ erasure side

    /**
     * Records the tombstones of a user erasure: the user and each of the given
     * conversations. Must run before anything is deleted.
     */
    public void tombstoneUser(String userId, Collection<String> conversationIds) {
        Objects.requireNonNull(userId, "userId");
        Instant now = clock.instant();
        Instant expires = now.plus(retention);
        store.putTombstones(Kind.USER, List.of(userKey(KvKeys.sha256(userId))), now, expires);
        if (conversationIds != null && !conversationIds.isEmpty()) {
            store.putTombstones(Kind.CONVERSATION, new LinkedHashSet<>(conversationIds), now, expires);
        }
    }

    /** Records the tombstone of an explicitly deleted conversation. */
    public void tombstoneConversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        tombstoneConversations(List.of(conversationId));
    }

    /** Records the tombstones of these conversations, in one write. */
    public void tombstoneConversations(Collection<String> conversationIds) {
        Instant now = clock.instant();
        store.putTombstones(Kind.CONVERSATION, new LinkedHashSet<>(conversationIds), now, now.plus(retention));
    }

    /**
     * The drain barrier on this node: waits, up to {@code eddi.gdpr.drain-timeout}
     * in total, until every writer finished the work it had queued before this
     * call.
     *
     * @return the names of the writers that did not drain in time (or failed to);
     *         empty when all did
     */
    public List<String> drainLocal() {
        List<String> undrained = new ArrayList<>();
        long started = System.nanoTime();
        // One deadline for all writers, not one each: the whole barrier is bounded by
        // eddi.gdpr.drain-timeout however many writers there are. The writers drain
        // concurrently anyway (their queues run on their own threads); waiting for
        // them one after the other costs nothing but the longest wait.
        long deadline = started + drainTimeout.toNanos();
        for (AsyncErasureWriter writer : writers()) {
            try {
                Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
                if (!writer.awaitDrained(left)) {
                    metrics.drainTimedOut(writer.name());
                    undrained.add(writer.name());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                undrained.add(writer.name());
            } catch (RuntimeException e) {
                LOGGER.warnf("Drain barrier of %s failed: %s", writer.name(), e.toString());
                undrained.add(writer.name());
            }
        }
        metrics.drained(Duration.ofNanos(System.nanoTime() - started));
        if (!undrained.isEmpty()) {
            LOGGER.warnf("GDPR: async writer(s) %s did not drain within %s; the erasure is reported incomplete", undrained, drainTimeout);
        }
        return undrained;
    }

    /**
     * The reply of this node to a drain request (the {@code gdpr-stop} handshake
     * and {@value IClusterRpc#ERASURE_DRAIN}): {@code drained=true}, or
     * {@code drained=false} with an {@code error}.
     */
    public Map<String, Object> drainReply() {
        List<String> undrained = drainLocal();
        return undrained.isEmpty()
                ? Map.of("drained", true)
                : Map.of("drained", false, "error", "async writer(s) not drained within " + drainTimeout + ": " + String.join(", ", undrained));
    }

    /**
     * The longest a node may take to answer a drain request; a caller waiting for
     * replies adds it to its own timeout.
     */
    public Duration drainTimeout() {
        return drainTimeout;
    }

    /**
     * The verification step of a writer's {@code IGdprParticipant}, after it
     * deleted the user's rows: retries the user's pending removals of
     * {@code writer}, then requires {@code remaining} to be 0 and no pending
     * removal to be left.
     *
     * @throws IllegalStateException
     *             when rows or pending removals remain — reported as the
     *             participant's failed step; re-running the erasure finishes it
     */
    public void verifyUserErased(String writer, String userId, LongSupplier remaining) {
        String key = userKey(KvKeys.sha256(userId));
        retryPending(writer, key, null);
        long left = remaining.getAsLong();
        long pending = store.countPendingRemovals(writer, key, null);
        if (left > 0 || pending > 0) {
            throw new IllegalStateException(writer + ": " + left + " row(s) and " + pending + " pending removal(s) of the user remain");
        }
    }

    /**
     * The erasure of a conversation's guarded rows, for an explicit conversation
     * delete: tombstone, drain barrier on every node, delete, verify. Must run
     * before the conversation's snapshot is deleted, so a failed delete stays
     * retryable.
     *
     * @throws ErasureIncompleteException
     *             when any step failed; nothing about it is guaranteed until a
     *             retry succeeds
     */
    public void eraseConversation(String conversationId) throws ErasureIncompleteException {
        Objects.requireNonNull(conversationId, "conversationId");
        Map<String, String> failed = eraseConversations(List.of(conversationId));
        if (!failed.isEmpty()) {
            throw new ErasureIncompleteException(failed.get(conversationId));
        }
    }

    /**
     * {@link #eraseConversation} for many conversations at once (the retention
     * sweep's permanent deletes): one tombstone write and <b>one</b> drain barrier,
     * local and cluster, for all of them, then the delete and verification of each.
     *
     * @return the conversations that could not be erased with the guarantee, each
     *         with the reason — their snapshots must be kept for a retry; empty
     *         when every one succeeded
     */
    public Map<String, String> eraseConversations(Collection<String> conversationIds) {
        Set<String> ids = new LinkedHashSet<>(conversationIds);
        Map<String, String> failed = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return failed;
        }
        try {
            Instant now = clock.instant();
            store.putTombstones(Kind.CONVERSATION, ids, now, now.plus(retention));
        } catch (RuntimeException e) {
            ids.forEach(id -> failed.put(id, "could not record the erasure tombstone: " + e));
            return failed;
        }
        List<String> problems = drainEverywhere(ids.size());
        // The barrier must hold before the delete: a row still queued could land after
        // it. Without it, nothing is deleted here and the whole delete is retried.
        if (!problems.isEmpty()) {
            String reason = String.join("; ", problems);
            ids.forEach(id -> failed.put(id, reason));
            return failed;
        }
        failed.putAll(eraseConversationRows(ids));
        return failed;
    }

    /**
     * The drain barrier on this node and on every other: what it leaves
     * unconfirmed, empty when every node drained. For a caller that wrote
     * tombstones after an earlier barrier — a user erasure that tombstones the
     * conversations it listed again — and must drain once more before it deletes
     * their rows.
     *
     * @param conversations
     *            how many conversations the barrier is for (a count only; it ends
     *            up in the peers' logs)
     */
    public List<String> drainEverywhere(int conversations) {
        List<String> problems = new ArrayList<>();
        List<String> undrained = drainLocal();
        if (!undrained.isEmpty()) {
            problems.add("async writer(s) not drained on this node: " + undrained);
        }
        problems.addAll(drainCluster(conversations));
        return problems;
    }

    /**
     * Deletes and verifies every async writer's rows of each conversation, and
     * requires no pending removal of it to be left — <em>whatever writer</em> the
     * removal names, so a record of a writer that is no longer deployed keeps the
     * conversation failed instead of being overlooked. Writes no tombstone and runs
     * no barrier: the caller must have done both. A user erasure calls it for the
     * user's conversations, so a row that carries a conversation id but no user
     * reference is erased with them.
     * <p>
     * Each conversation's tombstone must still be there when the verification of
     * the whole batch ends (and for a minute beyond): one that expired during the
     * erasure may have let a write through that this verification did not see, so
     * the conversation fails for a re-run. The tombstones that held are then
     * written again — within the hold check's margin, or they fail too, as when the
     * renewal fails — so their retention counts from the completion of the erasure,
     * not its start.
     *
     * @return the conversations whose rows are not all gone, each with the reason;
     *         empty when all are
     */
    public Map<String, String> eraseConversationRows(Collection<String> conversationIds) {
        Map<String, String> failed = new LinkedHashMap<>();
        for (String conversationId : new LinkedHashSet<>(conversationIds)) {
            List<String> problems = new ArrayList<>();
            try {
                retryPending(null, null, conversationId);
            } catch (RuntimeException e) {
                problems.add("pending removals could not be retried: " + e);
            }
            for (AsyncErasureWriter writer : writers()) {
                try {
                    writer.eraseConversation(conversationId);
                    long left = writer.countForConversation(conversationId);
                    if (left > 0) {
                        problems.add(writer.name() + ": " + left + " row(s) remain");
                    }
                } catch (Exception e) {
                    problems.add(writer.name() + ": " + e);
                }
            }
            try {
                long pending = store.countPendingRemovals(null, null, conversationId);
                if (pending > 0) {
                    problems.add(pending + " pending removal(s) remain");
                }
            } catch (RuntimeException e) {
                problems.add("pending removals could not be counted: " + e);
            }
            if (!problems.isEmpty()) {
                failed.put(conversationId, String.join("; ", problems));
            }
        }
        // Checked after the whole batch, not after each conversation: a tombstone
        // present now has been present throughout, while one checked early could
        // still expire while a later conversation is being erased. In chunks, each
        // checked and renewed well inside the hold margin, so a large batch (the
        // retention sweep's, a user with many conversations) still confirms in time.
        List<String> toCheck = new ArrayList<>();
        for (String conversationId : new LinkedHashSet<>(conversationIds)) {
            if (!failed.containsKey(conversationId)) {
                toCheck.add(conversationId);
            }
        }
        for (int from = 0; from < toCheck.size(); from += HOLD_CHUNK) {
            failed.putAll(holdAndRenew(Kind.CONVERSATION, toCheck.subList(from, Math.min(toCheck.size(), from + HOLD_CHUNK)),
                    "the conversation's tombstone"));
        }
        return failed;
    }

    /** How many tombstones one hold check and renewal covers. */
    static final int HOLD_CHUNK = 100;

    /**
     * Checks that each tombstone held, then writes the ones that did again, so
     * their retention counts from now. Every check asks for validity until
     * {@code checkedAt} + {@link #TOMBSTONE_HOLD_MARGIN} + the clock skew at least;
     * a renewal confirmed before {@code checkedAt} + {@link #TOMBSTONE_HOLD_MARGIN}
     * — by the clock and by elapsed time, so a clock stepping back cannot hide a
     * late one — therefore leaves no gap, even on a node or database whose clock is
     * up to the skew ahead. A renewal that fails or lands later is not confirmed.
     *
     * @return the keys that did not hold or whose renewal was not confirmed, each
     *         with the reason; empty when all did
     */
    private Map<String, String> holdAndRenew(Kind kind, List<String> keys, String what) {
        Map<String, String> failed = new LinkedHashMap<>();
        long startedNanos = System.nanoTime();
        Instant checkedAt = clock.instant();
        List<String> held = new ArrayList<>();
        for (String key : keys) {
            try {
                if (tombstoneHolds(kind, key)) {
                    held.add(key);
                } else {
                    failed.put(key, what + " expired during the erasure — retry it");
                }
            } catch (RuntimeException e) {
                failed.put(key, what + " could not be read: " + e);
            }
        }
        if (held.isEmpty()) {
            return failed;
        }
        String unconfirmed = null;
        try {
            Instant now = clock.instant();
            store.putTombstones(kind, held, now, now.plus(retention));
            boolean late = clock.instant().isAfter(checkedAt.plus(TOMBSTONE_HOLD_MARGIN))
                    || System.nanoTime() - startedNanos > TOMBSTONE_HOLD_MARGIN.toNanos();
            if (late) {
                unconfirmed = what + " could not be renewed at completion — retry it: the renewal landed after the hold check's margin of "
                        + TOMBSTONE_HOLD_MARGIN;
            }
        } catch (RuntimeException e) {
            unconfirmed = what + " could not be renewed at completion — retry it: " + e;
        }
        if (unconfirmed != null) {
            for (String key : held) {
                failed.put(key, unconfirmed);
            }
        }
        return failed;
    }

    /**
     * How far beyond the end of a verification a tombstone must still be valid to
     * count as having held throughout: a tombstone about to expire is treated as
     * expired.
     */
    static final Duration TOMBSTONE_HOLD_MARGIN = Duration.ofMinutes(1);

    /**
     * Whether the tombstone is present now and for {@link #TOMBSTONE_HOLD_MARGIN}
     * plus the clock skew beyond (another node's or the database's clock may be
     * that far ahead). Its expiry only ever moves later, so present now means it
     * never expired since it was written.
     */
    private boolean tombstoneHolds(Kind kind, String key) {
        return store.erasedAt(kind, key, clock.instant().plus(TOMBSTONE_HOLD_MARGIN).plus(clockSkew)).isPresent();
    }

    /**
     * The last check of a user erasure, after every participant ran: retries the
     * user's pending removals of <em>every</em> writer and requires none to be
     * left. {@link #verifyUserErased} checks one writer's; this also catches a
     * removal recorded by a writer without a participant, or one no longer
     * deployed.
     *
     * @throws IllegalStateException
     *             when a pending removal remains, or the store cannot tell
     */
    public void verifyNoPendingRemovals(String userId) {
        String key = userKey(KvKeys.sha256(userId));
        retryPending(null, key, null);
        long pending = store.countPendingRemovals(null, key, null);
        if (pending > 0) {
            throw new IllegalStateException(pending + " pending removal(s) of the user remain");
        }
        // The user tombstone must have held throughout (see eraseConversationRows);
        // then it is written again, so its retention counts from the completion.
        Map<String, String> notHeld = holdAndRenew(Kind.USER, List.of(key), "the user's erasure tombstone");
        if (!notHeld.isEmpty()) {
            throw new IllegalStateException(notHeld.values().iterator().next());
        }
    }

    /** The conversation-delete barrier on every other node. */
    List<String> drainCluster(int conversations) {
        if (clusterRpc == null || !clusterRpc.isClustered()) {
            return List.of();
        }
        try {
            // The nodes by id, read before the scatter: each of them must confirm. A node
            // that joins meanwhile answers too, but cannot stand in for one of these.
            Set<String> expected = clusterRpc.expectedPeerIds();
            // Every peer drains everything; the request names no conversation, which
            // would only end up in their logs.
            var replies = clusterRpc.callAllAwaiting(IClusterRpc.ERASURE_DRAIN, Map.of("conversations", conversations),
                    clusterTimeout.plus(drainTimeout), expected);
            return clusterVerdict(replies, expected);
        } catch (RuntimeException e) {
            return List.of("cluster drain failed: " + e);
        }
    }

    /**
     * What a scatter of drain requests leaves unconfirmed: each node of
     * {@code expected} (the peers read before the scatter) that did not answer, and
     * every answer without {@code drained=true} — including a node of an older
     * release, which does not drain at all. A reply from a node outside
     * {@code expected} never counts as a confirmation in place of one inside it; if
     * it reports a failure, that is a problem too.
     */
    public static List<String> clusterVerdict(Map<String, Map<String, Object>> replies, Set<String> expected) {
        List<String> problems = new ArrayList<>();
        List<String> missing = expected.stream().filter(node -> !replies.containsKey(node)).sorted().toList();
        if (!missing.isEmpty()) {
            problems.add(missing.size() + " of " + expected.size() + " node(s) did not confirm the drain: " + String.join(", ", missing));
        }
        for (var reply : replies.entrySet()) {
            if (!Boolean.TRUE.equals(reply.getValue().get("drained"))) {
                Object error = reply.getValue().get("error");
                problems.add("node " + reply.getKey() + ": " + (error != null ? error : "did not drain its async writers (older release?)"));
            }
        }
        return problems;
    }

    /**
     * Retries the pending removals in an erasure's scope — of one writer (null:
     * every writer) and user or conversation — whether or not they are due yet.
     *
     * @return how many were cleared
     */
    public int retryPending(String writer, String userKey, String conversationId) {
        return retry(store.pendingRemovals(writer, userKey, conversationId, null, RETRY_BATCH));
    }

    /**
     * The maintenance pass: retries the pending removals that are due, the
     * longest-due first. One that fails again, or names a writer no bean answers
     * to, is moved back by {@link #RETRY_DEFER}, so removals that keep failing
     * cannot fill every batch and starve the ones behind them.
     *
     * @return how many were cleared
     */
    public int retryDuePending() {
        return retry(store.pendingRemovals(null, null, null, clock.instant(), RETRY_BATCH));
    }

    /**
     * How many pending removals are stored, of every writer and node; also
     * published as the {@code eddi_gdpr_guard_pending_backlog} gauge.
     */
    public long pendingRemovalBacklog() {
        long backlog = store.countPendingRemovals(null, null, null);
        metrics.pendingBacklog(backlog);
        return backlog;
    }

    private int retry(List<PendingRemoval> removals) {
        int cleared = 0;
        for (PendingRemoval removal : removals) {
            AsyncErasureWriter owner = writer(removal.writer());
            if (owner == null) {
                // Left pending on purpose: no bean can remove it, and dropping the record
                // would let the erasure report success over a row that may still exist.
                LOGGER.errorf("GDPR: a pending removal names the unknown async writer '%s'; it stays pending", sanitize(removal.writer()));
                defer(removal);
                continue;
            }
            try {
                owner.removeRow(removal.rowId());
                store.removePendingRemoval(removal.writer(), removal.rowId());
                metrics.pendingRemoval(ErasureMetrics.PENDING_CLEARED);
                cleared++;
            } catch (Exception e) {
                LOGGER.warnf("GDPR: pending removal of a %s row still fails: %s", removal.writer(), e.toString());
                defer(removal);
            }
        }
        return cleared;
    }

    private void defer(PendingRemoval removal) {
        try {
            store.deferPendingRemoval(removal.writer(), removal.rowId(), clock.instant().plus(RETRY_DEFER));
        } catch (RuntimeException e) {
            // It stays due, so the next pass tries it first again; nothing is lost.
            LOGGER.debugf("GDPR: a pending removal could not be moved back: %s", e.toString());
        }
    }

    /** Deletes tombstones past their retention; returns how many. */
    public long deleteExpiredTombstones() {
        return store.deleteExpiredTombstones(clock.instant());
    }

    // ------------------------------------------------------------ helpers

    /**
     * The tombstone key of a user: HMAC-SHA256 of {@code KvKeys.sha256(userId)}
     * (the form the cluster stop carries) under the deployment's tombstone key. A
     * plain hash of an e-mail address would tell anyone with a candidate list and
     * database access whom the deployment erased.
     *
     * @throws IllegalStateException
     *             when the tombstone key cannot be resolved or no longer matches
     *             the one the tombstones were written with — every caller fails
     *             closed on it
     */
    public String userKey(String userIdHash) {
        return HexFormat.of().formatHex(key().macs().get().doFinal(userIdHash.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The internal subject a writer stores on a row of {@code userId} so the row
     * can be found again at erasure without keeping the plain id: the
     * {@link #userKey} with a {@value #SUBJECT_KEY_PREFIX} prefix.
     */
    public String subjectKey(String userId) {
        return SUBJECT_KEY_PREFIX + userKey(KvKeys.sha256(userId));
    }

    /** The prefix of {@link #subjectKey}: never a plain user id. */
    public static final String SUBJECT_KEY_PREFIX = "k1:";

    /** HKDF label of the vault-derived tombstone key. Never change it. */
    static final String VAULT_KEY_LABEL = "eddi-gdpr-erasure-tombstone-key|v1";
    /** Meta name of the random tombstone salt, used when there is no vault. */
    static final String SALT_META = "tombstone-salt";
    /**
     * Meta name of the key check value: the mode and an HMAC of a constant under
     * the key, written by the first node to use a key.
     */
    static final String KEY_CHECK_META = "tombstone-key-check";
    private static final byte[] KEY_CHECK_PLAINTEXT = "eddi-gdpr-tombstone-key-check".getBytes(StandardCharsets.UTF_8);
    private static final long MISMATCH_LOG_INTERVAL_MILLIS = 60_000;

    /**
     * The resolved key: its mode, the check value it was accepted under, and one
     * Mac per thread (Mac is not thread-safe).
     */
    private record TombstoneKey(String mode, String check, ThreadLocal<Mac> macs) {
    }

    /**
     * The tombstone key, re-checked against the stored check value on every use.
     * The derived key is kept (deriving it reads the vault), but whether it is
     * still <em>the</em> key is not assumed: when the stored check value no longer
     * matches it — an operator replaced or deleted the row to adopt a new key —
     * this node drops it and resolves again, which fails closed unless its own key
     * is the stored one. So a running node never keeps hashing users with a key the
     * stored check value no longer names. The re-check itself only reads; but when
     * the stored check value is <b>absent</b> (the row was deleted while this node
     * ran), the node resolves its key again, and {@link #resolveKey} inserts this
     * node's own check value — the old one — through {@code putMetaIfAbsent}. That
     * would undo an operator's reset, which is why the documented reset stops every
     * node before it deletes the row.
     */
    private TombstoneKey key() {
        TombstoneKey current = tombstoneKey;
        if (current != null) {
            Optional<String> stored = store.meta(KEY_CHECK_META);
            if (stored.isPresent() && stored.get().equals(current.check())) {
                return current;
            }
            synchronized (this) {
                if (tombstoneKey == current) {
                    tombstoneKey = null;
                }
            }
        }
        synchronized (this) {
            if (tombstoneKey == null) {
                tombstoneKey = resolveKey();
            }
            return tombstoneKey;
        }
    }

    /**
     * The key, from outside the database when the secrets vault is configured — a
     * subkey (HKDF, {@value #VAULT_KEY_LABEL}) of the vault's KEK-wrapped
     * deployment key, which survives KEK rotation and is never stored — otherwise a
     * random salt kept in the tombstone store (which protects a dump of the
     * tombstone table alone, not one of the whole database).
     * <p>
     * Either way the first node to use a key records a check value, and every node
     * compares its key with it: a key that changed (a vault configured or removed
     * later, a restored vault metadata store, a lost salt row) would make every
     * existing tombstone silently stop matching, so it fails closed instead — every
     * guarded write is skipped, every erasure step that needs the key fails — and
     * says so (ERROR, {@code eddi_gdpr_guard_key_mismatch_total}). A failed
     * resolution is not cached, so the guard recovers as soon as the operator
     * restored the key; a successful one is, and {@link #key} re-checks it against
     * the stored check value on every use.
     */
    private TombstoneKey resolveKey() {
        byte[] raw = vaultSubkey();
        String mode = "vault";
        if (raw == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            raw = Base64.getDecoder().decode(store.putMetaIfAbsent(SALT_META, Base64.getEncoder().encodeToString(random)));
            mode = "salt";
        }
        String check = mode + ":" + HexFormat.of().formatHex(newMac(raw).doFinal(KEY_CHECK_PLAINTEXT));
        String stored = store.putMetaIfAbsent(KEY_CHECK_META, check);
        if (!check.equals(stored)) {
            metrics.keyMismatch();
            long now = System.currentTimeMillis();
            long last = lastMismatchLog.get();
            if (now - last >= MISMATCH_LOG_INTERVAL_MILLIS && lastMismatchLog.compareAndSet(last, now)) {
                LOGGER.errorf("GDPR: the erasure tombstone key (%s) is not the one the stored tombstones were written with (%s). "
                        + "Guarded writes are skipped and erasures fail until it is restored: restore the vault master key and "
                        + "metadata, or the '%s' row, that were in use. Only if the old key is gone for good, delete the '%s' row "
                        + "of the erasure meta store; tombstones written under the old key then stop matching.", mode,
                        stored.substring(0, Math.max(0, stored.indexOf(':'))), SALT_META, KEY_CHECK_META);
            }
            throw new IllegalStateException("The erasure tombstone key changed (now " + mode + "); see the ERROR log for the operator action");
        }
        final byte[] keyBytes = raw;
        return new TombstoneKey(mode, check, ThreadLocal.withInitial(() -> newMac(keyBytes)));
    }

    /** The vault subkey, or null when no vault is configured. */
    private byte[] vaultSubkey() {
        if (vaultKey != null) {
            return vaultKey.get();
        }
        if (vault == null || !vault.isResolvable() || !vault.get().isAvailable()) {
            return null;
        }
        // An available vault that fails here propagates: falling back to the salt
        // would quietly switch keys.
        return vault.get().deploymentSubkey(VAULT_KEY_LABEL);
    }

    private static Mac newMac(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private Iterable<AsyncErasureWriter> writers() {
        if (writerList != null) {
            return writerList;
        }
        return writers == null ? List.of() : writers;
    }

    private AsyncErasureWriter writer(String name) {
        for (AsyncErasureWriter writer : writers()) {
            if (writer.name().equals(name)) {
                return writer;
            }
        }
        return null;
    }
}
