/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The durable side of the erasure guarantee — MongoDB
 * ({@code erasuretombstones}, {@code erasurependingremovals},
 * {@code erasuremeta}) or PostgreSQL ({@code erasure_tombstones},
 * {@code erasure_pending_removals}, {@code erasure_meta}).
 * <p>
 * <b>Tombstones</b> record that a user (by a keyed hash, never the id) or a
 * conversation (by its id) was erased, and when. They are written
 * <em>before</em> anything is deleted and kept for
 * {@code eddi.gdpr.tombstone-retention}, so an asynchronous writer that runs
 * late — on any node — can tell that the row it is about to write, or has just
 * written, belongs to erased data.
 * <p>
 * <b>Pending removals</b> are rows a writer wrote, found to be erased and could
 * not remove at once. They stay until the writer removed them; an erasure whose
 * user or conversation still has one reports its step as failed.
 * <p>
 * Every method may throw on a store failure. Callers treat a failure as "not
 * known to be safe": a guarded write is skipped, an erasure step is reported as
 * failed.
 *
 * @see ErasureGuard
 */
public interface IErasureTombstoneStore {

    /** What a tombstone stands for. */
    enum Kind {
        /** A user, keyed by {@link ErasureGuard#userKey}. */
        USER,
        /** A conversation, keyed by its id. */
        CONVERSATION
    }

    /**
     * A row that must still be removed.
     *
     * @param writer
     *            the {@link AsyncErasureWriter#name()} that wrote it
     * @param rowId
     *            the writer's own id of the row
     * @param userKey
     *            the keyed user hash of the row's user, or null
     * @param conversationId
     *            the row's conversation, or null
     */
    record PendingRemoval(String writer, String rowId, String userKey, String conversationId, Instant createdAt) {
    }

    /**
     * Records the tombstones, or moves an existing one's {@code erasedAt} and
     * {@code expiresAt} later (never earlier). Idempotent.
     */
    void putTombstones(Kind kind, Collection<String> keys, Instant erasedAt, Instant expiresAt);

    /**
     * When the tombstone was (last) written, or empty when there is none or it has
     * expired at {@code now}.
     */
    Optional<Instant> erasedAt(Kind kind, String key, Instant now);

    /** Deletes the tombstones expired at {@code now}; returns how many. */
    long deleteExpiredTombstones(Instant now);

    /**
     * The deployment's tombstone salt: the stored one, or {@code candidate} if none
     * is stored yet — an insert-if-absent, so every node adopts the first one
     * written. Never changes once written.
     */
    String salt(String candidate);

    /** Records a pending removal; a second record of the same row is a no-op. */
    void addPendingRemoval(PendingRemoval removal);

    /**
     * Pending removals, oldest first, at most {@code limit}, of {@code writer}
     * (null: every writer): all of them when {@code userKey} and
     * {@code conversationId} are both null, otherwise those whose user key or
     * conversation matches either non-null one.
     */
    List<PendingRemoval> pendingRemovals(String writer, String userKey, String conversationId, int limit);

    /** How many {@link #pendingRemovals} would return without a limit. */
    long countPendingRemovals(String writer, String userKey, String conversationId);

    /** Forgets a pending removal; {@code true} when it existed. */
    boolean removePendingRemoval(String writer, String rowId);
}
