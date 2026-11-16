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

    /** Matching rows, newest {@code decidedAt} first. */
    List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit);

    /** How many rows match. */
    long count(DecisionFilter filter);

    /** Deletes rows decided before the cutoff (retention). */
    long deleteDecidedBefore(Instant cutoff);

    // === GDPR ===

    /** Rows of conversations owned by the user, or decided by them. */
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
