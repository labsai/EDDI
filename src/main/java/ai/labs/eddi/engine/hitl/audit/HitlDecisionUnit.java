/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.engine.hitl.review.ApprovalCategories;

import java.time.Instant;

/**
 * One decided unit of a HITL decision, self-contained: everything approval
 * analytics needs to store one row, with the decision's context repeated on
 * every unit. Produced by {@link HitlApprovalAudit#parse} from a
 * {@code hitl.approval} audit entry (schema 1 or 2) and carried by the
 * {@link HitlDecisionRecorded} event.
 * <p>
 * A unit is one thing a human (or the system) said yes or no to: the model's
 * answer at a RULE pause, one gated tool call, a group phase or one group task.
 * Public contract: components are only ever added.
 *
 * @param decisionId
 *            id of the {@code hitl.approval} audit entry (also the key a
 *            {@code hitl.edit.refused} entry points back with)
 * @param schema
 *            1 for an entry written before 6.7 (fields it lacks are null), 2
 *            after
 * @param surface
 *            {@code regular} or {@code group}
 * @param pauseType
 *            {@code RULE}, {@code TOOL_CALL}, {@code PHASE} or {@code TASK};
 *            null when a schema-1 entry did not record it
 * @param conversationId
 *            the conversation (regular) or group conversation (group)
 * @param agentId
 *            the agent (regular); null for a group decision
 * @param agentVersion
 *            the pinned agent version (regular); null otherwise
 * @param groupId
 *            the group (group); null for a regular decision
 * @param userId
 *            the conversation's owner (pseudonymised after a GDPR erasure)
 * @param environment
 *            the deployment environment, when known
 * @param pauseId
 *            {@code HitlDecision.pauseIdOf(pausedAt)}; null when unknown
 * @param pausedAt
 *            when the pause began; null when unknown
 * @param decidedAt
 *            when the decision was recorded
 * @param latencyMs
 *            {@code decidedAt - pausedAt}; null when pausedAt is unknown
 * @param decidedBy
 *            who decided ({@code slack:U1}, {@code mcp:alice},
 *            {@code system:timeout}, a user id…)
 * @param automated
 *            whether {@code decidedBy} is {@code system:*}
 * @param channel
 *            where the decision was made
 * @param automationReason
 *            why the system decided ({@code TIMEOUT_POLICY},
 *            {@code NO_PROGRESS}, …); null for a human decision
 * @param timeoutPolicy
 *            the effective timeout policy of the pause, when known
 * @param unitKind
 *            what the unit is
 * @param unitId
 *            {@code "answer"}, the tool call id, the phase name or the task id
 * @param decisionOutcome
 *            the outcome; never null for a unit
 * @param approvalCategory
 *            the unit's category; null for schema 1
 * @param approvalCategorySource
 *            {@code CONFIG} or {@code DERIVED}; null for schema 1
 * @param reasonCode
 *            the reviewer's reason code for this unit, falling back to the
 *            decision's; null when none
 * @param toolName
 *            TOOL_CALL only
 * @param argsDigest
 *            TOOL_CALL only: SHA-256 of the arguments the decision applied to
 * @param edit
 *            what the approver changed; null when nothing was edited (and for
 *            schema-1 amendments, whose size was never recorded)
 * @since 6.7.0
 */
public record HitlDecisionUnit(String decisionId, int schema, String surface, String pauseType, String conversationId, String agentId,
        Integer agentVersion, String groupId, String userId, String environment, String pauseId, Instant pausedAt, Instant decidedAt,
        Long latencyMs, String decidedBy, boolean automated, HitlChannel channel, String automationReason, String timeoutPolicy,
        UnitKind unitKind, String unitId, DecisionOutcome decisionOutcome, String approvalCategory,
        ApprovalCategories.Source approvalCategorySource, String reasonCode, String toolName, String argsDigest, EditSummary edit) {

    /** What a unit is. Values are only ever added. */
    public enum UnitKind {
        /** The model's answer at a behavior-rule (RULE) pause. */
        ANSWER,
        /** One gated tool call of a TOOL_CALL pause. */
        TOOL_CALL,
        /** A group phase at a PHASE pause. */
        PHASE,
        /** One task of a group TASK pause. */
        TASK
    }
}
