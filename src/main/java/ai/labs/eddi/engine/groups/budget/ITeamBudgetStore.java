/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Per-team monthly spend and adjustments (F6): one document per
 * {@code (groupId, month)}, month as {@code YYYY-MM} in UTC.
 * <p>
 * <b>Atomicity contract.</b> {@link #addSpend}, {@link #applyAdjustment} and
 * {@link #setAdjustment} are single storage-level operations (a conditional
 * update, or a locked row), safe across nodes. Every method throws
 * {@link TeamBudgetAccountingUnavailableException} when the database cannot
 * answer; a store never answers 0.0 or "allow" for an outage.
 */
public interface ITeamBudgetStore {

    /** Bound on {@link TeamBudgetMonth#adjustments()} kept per month. */
    int MAX_ADJUSTMENTS = 100;
    /** Bound on {@link TeamBudgetMonth#appliedTransferIds()} kept per month. */
    int MAX_TRANSFER_IDS = 200;

    /** What {@link #applyAdjustment} or {@link #setAdjustment} did. */
    enum AdjustOutcome {
        APPLIED,
        /** A change that would leave the cap below the month's spend. */
        REFUSED,
        /** The {@code transferId} was already applied; nothing changed. */
        DUPLICATE,
        /**
         * {@link #setAdjustment} only: the stored adjustment is no longer the expected
         * one; nothing changed.
         */
        CONFLICT
    }

    /**
     * Adds {@code usd} to the month's spend, creating the document if absent.
     *
     * @return the month's spend after the increment
     */
    double addSpend(String groupId, String month, double usd, Instant expiresAt);

    Optional<TeamBudgetMonth> read(String groupId, String month);

    /** The months present for the given groups; absent months are omitted. */
    List<TeamBudgetMonth> readMany(Collection<String> groupIds, String month);

    /** A group's most recent months, newest first, at most {@code months}. */
    List<TeamBudgetMonth> history(String groupId, int months);

    /**
     * Changes the month's adjustment by {@code deltaUsd}. A negative delta is
     * applied only if {@code baseUsd + adjustment + delta >= spent} (a cap never
     * drops below what was spent) -- one conditional update, so two concurrent
     * debits cannot both pass. A non-null {@code transferId} makes the call
     * idempotent.
     */
    AdjustOutcome applyAdjustment(String groupId, String month, double deltaUsd, double baseUsd, TeamBudgetMonth.Adjustment entry,
                                  String transferId, Instant expiresAt);

    /**
     * Replaces the month's adjustment with {@code newAdjustmentUsd}: a
     * compare-and-set, applied only while the stored adjustment still equals
     * {@code expectedAdjustmentUsd} (0.0 for a month not stored yet) and
     * {@code baseUsd + newAdjustmentUsd >= spent}. A cap computed from a snapshot
     * therefore never lands on top of a change made after the snapshot was read --
     * which a delta through {@link #applyAdjustment} would.
     *
     * @return {@link AdjustOutcome#APPLIED}; {@link AdjustOutcome#CONFLICT} when
     *         the adjustment changed since it was read; or
     *         {@link AdjustOutcome#REFUSED} when the cap would drop below the spend
     */
    AdjustOutcome setAdjustment(String groupId, String month, double expectedAdjustmentUsd, double newAdjustmentUsd, double baseUsd,
                                TeamBudgetMonth.Adjustment entry, Instant expiresAt);

    /** Removes months whose retention has passed; returns how many. */
    int deleteExpired(Instant now);
}
