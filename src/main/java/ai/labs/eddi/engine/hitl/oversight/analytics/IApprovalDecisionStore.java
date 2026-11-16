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
     * Sets the two personal fields of one row, but only if they still hold the
     * expected values (compare-and-set), and sets or clears its pending mark. A row
     * that changed meanwhile (an erasure pseudonymised it) is left as it is. Only
     * this row is touched.
     *
     * @param pending
     *            whether the row stays pending
     * @return whether the row was updated
     */
    boolean updateIdentities(String id, String expectedUserId, String expectedDecidedBy, String userId, String decidedBy, boolean pending);

    /** Up to {@code limit} pending rows. */
    List<ApprovalDecision> pendingCheck(int limit);

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
     * Removes the user from every row: their conversations' rows lose the
     * {@code userId}, and their own decisions get {@code decidedBy = pseudonym}.
     * The rows themselves stay — they hold no content, and analytics stay correct.
     *
     * @return rows changed
     */
    long eraseUser(String userId, String pseudonym);
}
