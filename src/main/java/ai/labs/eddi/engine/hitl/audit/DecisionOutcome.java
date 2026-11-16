/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;

/**
 * What one decided unit of a HITL pause ended as — the {@code decisionOutcome}
 * of the {@code hitl.approval} audit contract (schema 2).
 * <p>
 * Part of a public contract consumed by approval analytics: values are only
 * ever added, never renamed. A HUMAN_TURN resolution (a group's human member
 * speaking) is not an approval and has no outcome ({@code null}).
 *
 * @since 6.7.0
 */
public enum DecisionOutcome {
    /** A human approved the unit as it was. */
    APPROVED,
    /**
     * A human approved the unit with a real change: edited answer, task result,
     * synthesis or tool arguments.
     */
    APPROVED_EDITED,
    /** A human rejected the unit. */
    REJECTED,
    /** The system approved it ({@code decidedBy system:*}, e.g. a timeout). */
    AUTO_APPROVED,
    /** The system rejected it ({@code decidedBy system:*}). */
    AUTO_REJECTED,
    /** The pause was cancelled or aborted — nobody approved or rejected it. */
    ABORTED;

    /**
     * Value of a top-level {@code decisionOutcome} when the units of one decision
     * disagree. Never a unit's outcome.
     */
    public static final String MIXED = "MIXED";

    /**
     * The outcome of one unit — the single place the mapping lives.
     *
     * @param verdict
     *            the unit's verdict; ignored when {@code cancelled}
     * @param decidedBy
     *            who decided; {@code system:*} marks an automated decision
     * @param edited
     *            whether an approval changed the unit (an edit identical to the
     *            original is not a change)
     * @param cancelled
     *            whether the pause was cancelled/aborted instead of decided
     */
    public static DecisionOutcome of(HitlVerdict verdict, String decidedBy, boolean edited, boolean cancelled) {
        if (cancelled) {
            return ABORTED;
        }
        boolean automated = decidedBy != null && decidedBy.startsWith("system:");
        if (verdict == HitlVerdict.APPROVED) {
            if (automated) {
                return AUTO_APPROVED;
            }
            return edited ? APPROVED_EDITED : APPROVED;
        }
        return automated ? AUTO_REJECTED : REJECTED;
    }
}
