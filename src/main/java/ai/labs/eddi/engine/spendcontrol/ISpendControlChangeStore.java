/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;

import java.util.List;
import java.util.Optional;

/**
 * Stores proposed spending-control changes ({@link SpendControlChange}).
 * MongoDB ({@code spendcontrolchanges}) and PostgreSQL
 * ({@code spend_control_changes}); each row keeps the change as JSON next to
 * its id, status and proposal time.
 */
public interface ISpendControlChangeStore {

    /** Inserts a new change. */
    void create(SpendControlChange change);

    Optional<SpendControlChange> read(String id);

    /**
     * Changes newest first.
     *
     * @param status
     *            only this status, or null for all
     * @param limit
     *            at most this many
     */
    List<SpendControlChange> list(Status status, int limit);

    /** How many changes have this status. */
    long count(Status status);

    /**
     * Replaces a change only while it still has {@code expected} status — the claim
     * that makes approving, rejecting and expiring mutually exclusive across nodes.
     *
     * @return whether it was replaced
     */
    boolean compareAndSetStatus(String id, Status expected, SpendControlChange next);

    /**
     * Deletes decided changes proposed before the cutoff (retention). Pending ones
     * are never deleted here; they expire first. Nor are APPLYING ones: an approval
     * interrupted mid-write stays visible until someone looks at it.
     *
     * @return how many were deleted
     */
    long deleteDecidedBefore(long proposedBeforeMillis);
}
