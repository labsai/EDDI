/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import java.util.List;

/**
 * CDI event fired right after a {@code hitl.approval} audit entry with at least
 * one unit was submitted to the audit ledger.
 * <p>
 * Fired with {@code Event.fireAsync}, so observe it with
 * {@code @ObservesAsync}: nothing an observer does runs on the decision's
 * critical path, and a failing observer cannot fail the decision. Delivery is
 * best-effort (an event is not durable) — an observer that must not miss a
 * decision rebuilds from the ledger with {@link HitlApprovalAudit#parse}, which
 * yields the same units.
 * <p>
 * Not fired for HUMAN_TURN resolutions (a group's human member speaking), which
 * have no units, nor when the audit ledger is disabled.
 *
 * @param auditEntryId
 *            id of the audit entry (equal to every unit's
 *            {@link HitlDecisionUnit#decisionId()})
 * @param units
 *            the decided units, never empty
 * @since 6.7.0
 */
public record HitlDecisionRecorded(String auditEntryId, List<HitlDecisionUnit> units) {

    public HitlDecisionRecorded {
        units = units != null ? List.copyOf(units) : List.of();
    }
}
