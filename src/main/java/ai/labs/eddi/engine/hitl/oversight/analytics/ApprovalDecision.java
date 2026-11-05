/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.HitlChannel;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;

import java.time.Instant;
import java.util.Locale;

/**
 * One decided HITL unit as approval analytics stores it — a projection of a
 * {@link HitlDecisionUnit} into the small, indexed {@code approval_decisions}
 * store. The audit ledger stays the evidence; this row can always be rebuilt
 * from it.
 *
 * @param id
 *            {@code <decisionId>#<unitIndex>} — unique, so inserting a unit
 *            twice (the event and a backfill) stores it once
 * @param category
 *            the unit's approval category; a schema-1 (pre-6.7) entry, which
 *            has none, gets a {@code legacy:} category and
 *            {@code legacy = true}
 * @param deciderKind
 *            derived at write time from {@code decidedBy}
 * @param timedOut
 *            the system decided because the pause timed out
 * @since 6.7.0
 */
public record ApprovalDecision(String id, String decisionId, int unitIndex, String surface, String pauseType, String unitKind,
        String unitId, String agentId, Integer agentVersion, String groupId, String environment, String conversationId, String userId,
        String category, String categorySource, String outcome, String reasonCode, Integer editChars, Double editRatio, String decidedBy,
        DeciderKind deciderKind, String channel, Instant pausedAt, Instant decidedAt, Long latencyMs, boolean timedOut, String toolName,
        boolean legacy) {

    /** Who decided, as far as evidence is concerned. */
    public enum DeciderKind {
        /** A named human principal (REST, Manager, Slack user, MCP user). */
        HUMAN_VERIFIED,
        /** The system ({@code system:*}): a timeout, a no-progress guard… */
        SYSTEM,
        /**
         * No identity was recorded ({@code unknown}, {@code anonymous},
         * {@code unverified:*}).
         */
        UNVERIFIED;

        /** The kind of a decider. */
        public static DeciderKind of(String decidedBy, boolean automated) {
            if (automated || decidedBy != null && decidedBy.startsWith("system:")) {
                return SYSTEM;
            }
            if (decidedBy == null || decidedBy.isBlank() || "unknown".equals(decidedBy) || "anonymous".equals(decidedBy)
                    || decidedBy.startsWith("unverified:")) {
                return UNVERIFIED;
            }
            return HUMAN_VERIFIED;
        }
    }

    /** The row id of a unit. */
    public static String idOf(String decisionId, int unitIndex) {
        return decisionId + "#" + unitIndex;
    }

    /**
     * The row of one unit.
     *
     * @param unitIndex
     *            the unit's position in its decision
     */
    public static ApprovalDecision of(HitlDecisionUnit u, int unitIndex) {
        boolean legacy = u.approvalCategory() == null;
        String category = legacy ? legacyCategory(u) : u.approvalCategory();
        HitlChannel channel = u.channel() != null ? u.channel() : HitlChannel.of(u.decidedBy(), null);
        boolean timedOut = channel == HitlChannel.TIMEOUT || "TIMEOUT_POLICY".equals(u.automationReason());
        Integer editChars = u.edit() != null ? u.edit().charsChanged() : null;
        Double editRatio = u.edit() != null ? u.edit().changeRatio() : null;
        DecisionOutcome outcome = u.decisionOutcome();
        return new ApprovalDecision(idOf(u.decisionId(), unitIndex), u.decisionId(), unitIndex, u.surface(), u.pauseType(),
                u.unitKind() != null ? u.unitKind().name() : null, u.unitId(), u.agentId(), u.agentVersion(), u.groupId(), u.environment(),
                u.conversationId(), u.userId(), category,
                u.approvalCategorySource() != null ? u.approvalCategorySource().name() : null, outcome != null ? outcome.name() : null,
                u.reasonCode(), editChars, editRatio, u.decidedBy(), DeciderKind.of(u.decidedBy(), u.automated()), channel.name(),
                u.pausedAt(), u.decidedAt(), u.latencyMs(), timedOut, u.toolName(), legacy);
    }

    /**
     * The category of a unit written before categories existed. Never a tool
     * pattern, so a legacy row is never promotion evidence.
     */
    static String legacyCategory(HitlDecisionUnit u) {
        if (u.toolName() != null) {
            return "legacy:tool:" + u.toolName();
        }
        if ("group".equals(u.surface() != null ? u.surface().toLowerCase(Locale.ROOT) : null) && u.groupId() != null) {
            return "legacy:group:" + u.groupId();
        }
        if (u.agentId() != null) {
            return "legacy:rule:" + u.agentId();
        }
        return "legacy:unknown";
    }

    /**
     * Whether a human decided ({@code APPROVED}, {@code APPROVED_EDITED},
     * {@code REJECTED}).
     */
    public boolean humanOutcome() {
        return "APPROVED".equals(outcome) || "APPROVED_EDITED".equals(outcome) || "REJECTED".equals(outcome);
    }

    /** The row without the two personal fields, as the JSON payload stores it. */
    public ApprovalDecision withoutPersonalFields() {
        return withPersonalFields(null, null);
    }

    /** The row with its personal fields replaced. */
    public ApprovalDecision withPersonalFields(String newUserId, String newDecidedBy) {
        return new ApprovalDecision(id, decisionId, unitIndex, surface, pauseType, unitKind, unitId, agentId, agentVersion, groupId,
                environment, conversationId, newUserId, category, categorySource, outcome, reasonCode, editChars, editRatio, newDecidedBy,
                deciderKind, channel, pausedAt, decidedAt, latencyMs, timedOut, toolName, legacy);
    }
}
