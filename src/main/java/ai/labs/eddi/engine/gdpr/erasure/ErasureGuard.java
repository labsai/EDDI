/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.Kind;
import ai.labs.eddi.engine.gdpr.erasure.IErasureTombstoneStore.PendingRemoval;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

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
 * the write is skipped (or removed again). Nothing is cached — a cache that
 * missed a tombstone written before a barrier completed would break the
 * guarantee — so a guarded write costs one or two indexed lookups, on the
 * writer's background thread.
 */
@ApplicationScoped
public class ErasureGuard {

    private static final Logger LOGGER = Logger.getLogger(ErasureGuard.class);

    static final String TOMBSTONE_RETENTION_PROPERTY = "eddi.gdpr.tombstone-retention";
    static final String DRAIN_TIMEOUT_PROPERTY = "eddi.gdpr.drain-timeout";
    static final String CLOCK_SKEW_PROPERTY = "eddi.gdpr.tombstone-clock-skew";

    /** How many pending removals one retry pass handles. */
    static final int RETRY_BATCH = 500;

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
    private volatile byte[] salt;
    /** The writers when built without CDI; otherwise null. */
    private Iterable<AsyncErasureWriter> writerList;

    /** Cluster mode: the conversation-delete barrier on every other node. */
    @Inject
    IClusterRpc clusterRpc;

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
        this.store = store;
        this.writers = writers;
        this.metrics = metrics;
        this.retention = retention;
        this.drainTimeout = drainTimeout;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    // ------------------------------------------------------------ write side

    /**
     * Whether an asynchronous writer may write a row of {@code userId} and
     * {@code conversationId} captured at {@code capturedAt}. {@code false} when
     * either was erased — the user: at or after {@code capturedAt}, give or take
     * {@code eddi.gdpr.tombstone-clock-skew}; the conversation: ever — or when that
     * cannot be checked (fail closed).
     *
     * @param capturedAt
     *            when the content was captured; null is treated as "before any
     *            erasure"
     */
    public boolean beforeWrite(String userId, String conversationId, Instant capturedAt) {
        try {
            if (erased(userId, conversationId, capturedAt)) {
                metrics.writeSkipped(ErasureMetrics.REASON_TOMBSTONE);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.warnf("Erasure tombstones could not be read; skipping a guarded write: %s", e.toString());
            metrics.writeSkipped(ErasureMetrics.REASON_CHECK_FAILED);
            return false;
        }
    }

    /**
     * Called by a writer right after it stored a row. When the row's user or
     * conversation has been erased meanwhile (or that cannot be checked), the row
     * is removed through {@code remover}; if that fails, a durable pending removal
     * is recorded and retried later.
     *
     * @return {@code true} when the row's content must be treated as gone (removed
     *         or pending removal) — the writer must not deliver or use it further
     */
    public boolean afterWrite(String writer, String rowId, String userId, String conversationId, Instant capturedAt,
                              RowRemover remover) {
        boolean erased;
        try {
            erased = erased(userId, conversationId, capturedAt);
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
            recordPending(writer, rowId, userId, conversationId);
            return true;
        }
    }

    private void recordPending(String writer, String rowId, String userId, String conversationId) {
        try {
            store.addPendingRemoval(new PendingRemoval(writer, rowId, userId == null ? null : userKey(KvKeys.sha256(userId)), conversationId,
                    clock.instant()));
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

    boolean erased(String userId, String conversationId, Instant capturedAt) {
        Instant now = clock.instant();
        if (conversationId != null && store.erasedAt(Kind.CONVERSATION, conversationId, now).isPresent()) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        Optional<Instant> erasedAt = store.erasedAt(Kind.USER, userKey(KvKeys.sha256(userId)), now);
        return erasedAt.isPresent() && (capturedAt == null || !capturedAt.isAfter(erasedAt.get().plus(clockSkew)));
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
        Instant now = clock.instant();
        store.putTombstones(Kind.CONVERSATION, List.of(conversationId), now, now.plus(retention));
    }

    /**
     * The drain barrier on this node: waits, up to {@code eddi.gdpr.drain-timeout}
     * per writer, until every writer finished the work it had queued before this
     * call.
     *
     * @return the names of the writers that did not drain in time (or failed to);
     *         empty when all did
     */
    public List<String> drainLocal() {
        List<String> undrained = new ArrayList<>();
        long started = System.nanoTime();
        for (AsyncErasureWriter writer : writers()) {
            try {
                if (!writer.awaitDrained(drainTimeout)) {
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
        try {
            tombstoneConversation(conversationId);
        } catch (RuntimeException e) {
            throw new ErasureIncompleteException("could not record the erasure tombstone: " + e);
        }
        List<String> problems = new ArrayList<>();
        List<String> undrained = drainLocal();
        if (!undrained.isEmpty()) {
            problems.add("async writer(s) not drained on this node: " + undrained);
        }
        problems.addAll(drainCluster(conversationId));
        // The barrier must hold before the delete: a row still queued could land after
        // it. Without it, nothing is deleted here and the whole delete is retried.
        if (!problems.isEmpty()) {
            throw new ErasureIncompleteException(String.join("; ", problems));
        }
        for (AsyncErasureWriter writer : writers()) {
            try {
                retryPending(writer.name(), null, conversationId);
                writer.eraseConversation(conversationId);
                long left = writer.countForConversation(conversationId);
                long pending = store.countPendingRemovals(writer.name(), null, conversationId);
                if (left > 0 || pending > 0) {
                    problems.add(writer.name() + ": " + left + " row(s) and " + pending + " pending removal(s) remain");
                }
            } catch (Exception e) {
                problems.add(writer.name() + ": " + e);
            }
        }
        if (!problems.isEmpty()) {
            throw new ErasureIncompleteException(String.join("; ", problems));
        }
    }

    /** The conversation-delete barrier on every other node. */
    List<String> drainCluster(String conversationId) {
        if (clusterRpc == null || !clusterRpc.isClustered()) {
            return List.of();
        }
        try {
            int expected = clusterRpc.expectedPeers();
            var replies = clusterRpc.callAll(IClusterRpc.ERASURE_DRAIN, Map.of("conversationId", conversationId),
                    clusterTimeout.plus(drainTimeout), expected);
            return clusterVerdict(replies, expected);
        } catch (RuntimeException e) {
            return List.of("cluster drain failed: " + e);
        }
    }

    /**
     * What a scatter of drain requests leaves unconfirmed: nodes that did not
     * answer, and answers without {@code drained=true} — including a node of an
     * older release, which does not drain at all.
     */
    public static List<String> clusterVerdict(Map<String, Map<String, Object>> replies, int expected) {
        List<String> problems = new ArrayList<>();
        if (replies.size() < expected) {
            problems.add((expected - replies.size()) + " of " + expected + " node(s) did not confirm the drain");
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
     * Retries pending removals — of one writer and user or conversation (an
     * erasure's verification), or all of them (the maintenance pass, with every
     * argument null).
     *
     * @return how many were cleared
     */
    public int retryPending(String writer, String userKey, String conversationId) {
        int cleared = 0;
        for (PendingRemoval removal : store.pendingRemovals(writer, userKey, conversationId, RETRY_BATCH)) {
            AsyncErasureWriter owner = writer(removal.writer());
            if (owner == null) {
                // Left pending on purpose: no bean can remove it, and dropping the record
                // would let the erasure report success over a row that may still exist.
                LOGGER.errorf("GDPR: a pending removal names the unknown async writer '%s'; it stays pending", sanitize(removal.writer()));
                continue;
            }
            try {
                owner.removeRow(removal.rowId());
                store.removePendingRemoval(removal.writer(), removal.rowId());
                metrics.pendingRemoval(ErasureMetrics.PENDING_CLEARED);
                cleared++;
            } catch (Exception e) {
                LOGGER.warnf("GDPR: pending removal of a %s row still fails: %s", removal.writer(), e.toString());
            }
        }
        return cleared;
    }

    /** Deletes tombstones past their retention; returns how many. */
    public long deleteExpiredTombstones() {
        return store.deleteExpiredTombstones(clock.instant());
    }

    // ------------------------------------------------------------ helpers

    /**
     * The tombstone key of a user: HMAC-SHA256 of {@code KvKeys.sha256(userId)}
     * (the form the cluster stop carries) under the deployment's tombstone salt. A
     * plain hash of an e-mail address would tell anyone with a candidate list and
     * database access whom the deployment erased.
     */
    public String userKey(String userIdHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(userIdHash.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private byte[] salt() {
        byte[] current = salt;
        if (current == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            // Insert-if-absent: every node adopts the first salt written, and it never
            // changes, so caching it is safe (unlike a tombstone verdict).
            current = Base64.getDecoder().decode(store.salt(Base64.getEncoder().encodeToString(random)));
            salt = current;
        }
        return current;
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
