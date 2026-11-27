/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import java.time.Instant;
import java.util.List;

/**
 * The approval-decision facts approval analytics and earned autonomy read: one
 * row per decided HITL unit, keyed so that a unit stored twice is stored once.
 * MongoDB ({@code approval_decisions} collection) and PostgreSQL
 * ({@code approval_decisions} table) implementations, selected by
 * {@code DataStoreProducers}.
 * <p>
 * Not the evidence — the audit ledger is. A row lost to a failed write is
 * repaired by a rebuild from the ledger.
 *
 * @since 6.7.0
 */
public interface IApprovalDecisionStore {

    /**
     * Stores a row unless one with its id exists.
     *
     * @return whether it was inserted
     */
    boolean insertIfAbsent(ApprovalDecision decision);

    /**
     * Stores a row <em>pending</em> unless one with its id exists. A pending row is
     * left out of {@link #query}, {@link #count} and {@link #readByUser} until
     * {@link #updateIdentities} settles it; {@link #pendingCheck} finds it again
     * after an interruption. The projector stores rows this way with their
     * identities masked, so what rests in the store is the erased form.
     *
     * @return whether it was inserted
     */
    boolean insertPendingIfAbsent(ApprovalDecision decision);

    /**
     * The settlement version a row is inserted with. Every write of a row's
     * identities ({@link #updateIdentities}, {@link #eraseUser}) increments it.
     */
    long INITIAL_VERSION = 0;

    /**
     * Sets the two personal fields of one row and sets or clears its pending mark,
     * but only if the row is still at {@code expectedVersion} (compare-and-set),
     * and increments the version. A version, not the identity values, is compared:
     * an erasure can return a row to the very values a stale settlement expects,
     * but never to its version. Only this row is touched.
     *
     * @param pending
     *            whether the row stays pending
     * @return whether the row was updated (its version is then
     *         {@code expectedVersion + 1})
     */
    boolean updateIdentities(String id, long expectedVersion, String userId, String decidedBy, boolean pending);

    /** A pending row and its settlement version. */
    record Pending(ApprovalDecision row, long version) {
    }

    /** Up to {@code limit} pending rows. */
    List<Pending> pendingCheck(int limit);

    /**
     * Matching settled rows (pending ones are left out), newest {@code decidedAt}
     * first.
     */
    List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit);

    /** How many settled rows match. */
    long count(DecisionFilter filter);

    /** Deletes rows decided before the cutoff (retention). */
    long deleteDecidedBefore(Instant cutoff);

    // === GDPR ===

    /**
     * Settled rows of conversations owned by the user, or decided by them. Pending
     * rows are left out: they hold masked identities, or for the moment between the
     * two checks of a settlement, an identity not yet confirmed.
     */
    List<ApprovalDecision> readByUser(String userId, int limit);

    /**
     * Removes the user from every row, pending ones included, and increments the
     * version of each row it changes: their conversations' rows lose the
     * {@code userId}, and their own decisions get {@code decidedBy = pseudonym}.
     * The rows themselves stay — they hold no content, and analytics stay correct.
     *
     * @return rows changed
     */
    long eraseUser(String userId, String pseudonym);
}
