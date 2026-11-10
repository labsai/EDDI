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
 * <b>Atomicity contract.</b> {@link #addSpend} and {@link #applyAdjustment} are
 * single storage-level operations (a {@code $inc} / {@code INSERT ... ON
 * CONFLICT}), safe across nodes. Every method throws
 * {@link TeamBudgetAccountingUnavailableException} when the database cannot
 * answer; a store never answers 0.0 or "allow" for an outage.
 */
public interface ITeamBudgetStore {

    /** Bound on {@link TeamBudgetMonth#adjustments()} kept per month. */
    int MAX_ADJUSTMENTS = 100;
    /** Bound on {@link TeamBudgetMonth#appliedTransferIds()} kept per month. */
    int MAX_TRANSFER_IDS = 200;

    /** What {@link #applyAdjustment} did. */
    enum AdjustOutcome {
        APPLIED,
        /** A debit that would leave the cap below the month's spend. */
        REFUSED,
        /** The {@code transferId} was already applied; nothing changed. */
        DUPLICATE
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

    /** Removes months whose retention has passed; returns how many. */
    int deleteExpired(Instant now);
}
