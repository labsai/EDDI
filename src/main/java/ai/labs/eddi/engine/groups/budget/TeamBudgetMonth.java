/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import java.time.Instant;
import java.util.List;

/**
 * One team's month: what it spent and how its cap was moved (F6).
 *
 * @param spentUsd
 *            priced spend charged to the team this month
 * @param adjustmentUsd
 *            the current-month delta over the configured base cap (may be
 *            negative after a transfer out)
 * @param appliedTransferIds
 *            bounded; makes a retried transfer a no-op
 * @param adjustments
 *            bounded history, oldest first
 */
public record TeamBudgetMonth(String groupId, String month, double spentUsd, double adjustmentUsd, List<String> appliedTransferIds,
        List<Adjustment> adjustments) {

    public TeamBudgetMonth {
        appliedTransferIds = appliedTransferIds == null ? List.of() : List.copyOf(appliedTransferIds);
        adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
    }

    /**
     * @param kind
     *            {@code SET}, {@code TRANSFER_OUT} or {@code TRANSFER_IN}
     * @param actor
     *            the admin principal
     * @param capAfterUsd
     *            the effective cap right after this entry
     * @param reason
     *            free text; shown on the history endpoint only, never in template
     *            values
     * @param counterparty
     *            the other team of a transfer, or null
     */
    public record Adjustment(Instant at, String actor, String kind, double deltaUsd, double capAfterUsd, String reason, String counterparty,
            String transferId) {
    }
}
