/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.EditMetrics;
import ai.labs.eddi.engine.hitl.audit.EditSummary;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecord;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link HitlDecisionRecord} of each kind of HITL decision — the
 * mapping from what a resume or cancel knows to the audit contract's units.
 * Pure functions; the writers stay one call each.
 *
 * @since 6.7.0
 */
public final class HitlDecisionRecords {

    private HitlDecisionRecords() {
    }

    /** What the pause looked like before the decision consumed it. */
    public record RegularPause(Instant pausedAt, String timeoutPolicy, String ruleDraft) {
        public static final RegularPause UNKNOWN = new RegularPause(null, null, null);
    }

    /**
     * A regular conversation's decision.
     *
     * @param hitlConfig
     *            the agent's pinned HITL config (category), or null
     * @param legacyToolDecisions
     *            the pre-6.7 {@code toolDecisions} summary, kept verbatim
     * @param legacyOnRejection
     *            the pre-6.7 {@code onRejection} field, or null
     */
    public static HitlDecisionRecord regularDecision(String conversationId, String agentId, Integer agentVersion, String userId,
                                                     String environment, HitlDecision decision, boolean toolCallPause,
                                                     PendingToolCallBatch batch, AgentConfiguration.HitlConfig hitlConfig, RegularPause pause,
                                                     List<Map<String, Object>> legacyToolDecisions, String legacyOnRejection) {
        RegularPause facts = pause != null ? pause : RegularPause.UNKNOWN;
        HitlVerdict verdict = decision.getVerdict();
        var record = new HitlDecisionRecord()
                .conversation(conversationId, userId, environment)
                .agent(agentId, agentVersion)
                .pauseType(toolCallPause ? "TOOL_CALL" : "RULE")
                .pausedAt(facts.pausedAt())
                .verdict(verdict != null ? verdict.name() : "UNKNOWN")
                .decidedBy(decision.getDecidedBy())
                .channel(decision.getChannel())
                .timeoutPolicy(facts.timeoutPolicy())
                .reasonCode(decision.getReasonCode())
                .note(decision.getNote());
        if (toolCallPause) {
            record.legacy("pauseType", "TOOL_CALL").legacy("toolDecisions", legacyToolDecisions);
            addToolCallUnits(record, decision, batch);
        } else {
            record.legacy("onRejection", legacyOnRejection);
            var category = ApprovalCategories.forRule(hitlConfig, agentId);
            record.category(category);
            EditSummary edit = null;
            if (verdict == HitlVerdict.APPROVED && decision.getEditedAnswer() != null && facts.ruleDraft() != null) {
                edit = EditMetrics.ofText(EditSummary.Kind.ANSWER, facts.ruleDraft(), decision.getEditedAnswer());
            }
            boolean edited = edit != null && edit.changed();
            record.unit(new HitlDecisionRecord.Unit(UnitKind.ANSWER, "answer",
                    DecisionOutcome.of(verdict, decision.getDecidedBy(), edited, false), category, null, null, null, edited ? edit : null));
        }
        return record;
    }

    private static void addToolCallUnits(HitlDecisionRecord record, HitlDecision decision, PendingToolCallBatch batch) {
        if (batch == null || batch.getCalls() == null) {
            return;
        }
        ToolApprovalsConfig effective = batch.getEffectiveToolApprovals();
        Map<String, ToolCallDecision> perCall = decision.getToolDecisions() != null ? decision.getToolDecisions() : Map.of();
        ApprovalCategories.ApprovalCategory pauseCategory = null;
        for (PendingToolCallBatch.PendingToolCall call : batch.getCalls()) {
            if (call == null) {
                continue;
            }
            ToolCallDecision cd = perCall.get(call.getCallId());
            HitlVerdict verdict = cd != null && cd.getVerdict() != null ? cd.getVerdict() : decision.getVerdict();
            String amended = cd != null ? cd.getAmendedArguments() : null;
            EditSummary edit = amended != null && verdict == HitlVerdict.APPROVED ? EditMetrics.ofArguments(call.getArgumentsRaw(), amended) : null;
            boolean edited = edit != null && edit.changed();
            var category = ApprovalCategories.forToolCall(effective, call.getGateReason(), call.getMatchedRule());
            if (pauseCategory == null) {
                pauseCategory = category;
            }
            String argsForDigest = amended != null ? amended : call.getArgumentsRaw();
            record.unit(new HitlDecisionRecord.Unit(UnitKind.TOOL_CALL, call.getCallId(),
                    DecisionOutcome.of(verdict, decision.getDecidedBy(), edited, false), category, cd != null ? cd.getReasonCode() : null,
                    call.getToolName(), EditMetrics.sha256Hex(argsForDigest != null ? argsForDigest : ""), edited ? edit : null));
        }
        // The pause's own category: the governing rule's when the batch has one,
        // otherwise the first call's.
        ToolApprovalsConfig.ApprovalRule governing = batch.getEffectiveRule();
        if (governing != null && !batch.getCalls().isEmpty() && batch.getCalls().get(0) != null) {
            pauseCategory = ApprovalCategories.forToolCall(effective, batch.getCalls().get(0).getGateReason(), governing.getMatch());
        }
        record.category(pauseCategory);
    }

    /**
     * A cancelled (or aborted) regular pause: every unit the pause held is
     * {@code ABORTED}.
     */
    public static HitlDecisionRecord regularCancellation(String conversationId, String agentId, Integer agentVersion, String userId,
                                                         String environment, String pauseType, PendingToolCallBatch batch,
                                                         AgentConfiguration.HitlConfig hitlConfig, Instant pausedAt, String timeoutPolicy,
                                                         String mode, String cancelledBy) {
        boolean toolCallPause = "TOOL_CALL".equals(pauseType);
        var record = new HitlDecisionRecord()
                .conversation(conversationId, userId, environment)
                .agent(agentId, agentVersion)
                .pauseType(toolCallPause ? "TOOL_CALL" : "RULE")
                .pausedAt(pausedAt)
                .verdict(HitlDecisionRecord.VERDICT_CANCELLED)
                .decidedBy(cancelledBy)
                .timeoutPolicy(timeoutPolicy)
                .legacy("mode", mode);
        if (toolCallPause && batch != null && batch.getCalls() != null) {
            ApprovalCategories.ApprovalCategory first = null;
            for (PendingToolCallBatch.PendingToolCall call : batch.getCalls()) {
                if (call == null) {
                    continue;
                }
                var category = ApprovalCategories.forToolCall(batch.getEffectiveToolApprovals(), call.getGateReason(), call.getMatchedRule());
                first = first != null ? first : category;
                record.unit(new HitlDecisionRecord.Unit(UnitKind.TOOL_CALL, call.getCallId(), DecisionOutcome.ABORTED, category, null,
                        call.getToolName(), null, null));
            }
            record.category(first);
        } else {
            var category = ApprovalCategories.forRule(hitlConfig, agentId);
            record.category(category).unit(new HitlDecisionRecord.Unit(UnitKind.ANSWER, "answer", DecisionOutcome.ABORTED, category, null,
                    null, null, null));
        }
        return record;
    }

    // === Groups ===

    /** What a group pause looked like before the decision consumed it. */
    public record GroupPause(String pauseType, String phaseName, Instant pausedAt, String timeoutPolicy, List<String> awaitingTaskIds,
            String pauseReason) {
    }

    /** One task's decision inside a group TASK decision. */
    public record TaskOutcome(HitlVerdict verdict, String reasonCode, EditSummary edit) {
    }

    /**
     * A group decision.
     *
     * @param taskOutcomes
     *            per-task decisions (TASK pauses), in order; tasks of
     *            {@code pause.awaitingTaskIds} not listed take the top-level
     *            verdict
     * @param synthesisEdit
     *            the edit of a SYNTHESIS phase output, or null
     */
    public static HitlDecisionRecord groupDecision(String groupConversationId, String groupId, String userId, HitlDecision decision,
                                                   AgentGroupConfiguration.HitlConfig hitlConfig, GroupPause pause,
                                                   Map<String, TaskOutcome> taskOutcomes, EditSummary synthesisEdit) {
        HitlVerdict verdict = decision.getVerdict();
        String pauseType = pause != null ? pause.pauseType() : null;
        String phaseName = pause != null ? pause.phaseName() : null;
        var record = new HitlDecisionRecord()
                .surface(HitlDecisionRecord.SURFACE_GROUP)
                .conversation(groupConversationId, userId, null)
                .group(groupId)
                .pauseType(pauseType)
                .pausedAt(pause != null ? pause.pausedAt() : null)
                .verdict(verdict != null ? verdict.name() : "UNKNOWN")
                .decidedBy(decision.getDecidedBy())
                .channel(decision.getChannel())
                .timeoutPolicy(pause != null ? pause.timeoutPolicy() : null)
                .reasonCode(decision.getReasonCode())
                .note(decision.getNote())
                .legacy("phaseName", phaseName);
        if ("TASK".equals(pauseType)) {
            var category = ApprovalCategories.forGroupTask(hitlConfig, groupId, phaseName);
            record.category(category);
            Map<String, TaskOutcome> decided = new LinkedHashMap<>(taskOutcomes != null ? taskOutcomes : Map.of());
            // Explicit per-task decisions (taskApprovals) leave the other awaiting tasks
            // undecided: only an
            // approve-all or reject-all (no per-task outcomes) decides every awaiting task.
            if (decided.isEmpty() && pause.awaitingTaskIds() != null) {
                for (String taskId : pause.awaitingTaskIds()) {
                    decided.putIfAbsent(taskId, new TaskOutcome(verdict, null, null));
                }
            }
            decided.forEach((taskId, outcome) -> {
                boolean edited = outcome.edit() != null && outcome.edit().changed();
                record.unit(new HitlDecisionRecord.Unit(UnitKind.TASK, taskId,
                        DecisionOutcome.of(outcome.verdict(), decision.getDecidedBy(), edited, false), category, outcome.reasonCode(), null,
                        null, edited ? outcome.edit() : null));
            });
        } else {
            var category = ApprovalCategories.forGroupPhase(hitlConfig, groupId, phaseName);
            boolean edited = synthesisEdit != null && synthesisEdit.changed();
            record.category(category).unit(new HitlDecisionRecord.Unit(UnitKind.PHASE, phaseName,
                    DecisionOutcome.of(verdict, decision.getDecidedBy(), edited, false), category, null, null, null,
                    edited ? synthesisEdit : null));
        }
        return record;
    }

    /** A cancelled group pause. HUMAN_TURN pauses have no units. */
    public static HitlDecisionRecord groupCancellation(String groupConversationId, String groupId, String userId,
                                                       AgentGroupConfiguration.HitlConfig hitlConfig, GroupPause pause, String mode,
                                                       String cancelledBy) {
        String pauseType = pause != null ? pause.pauseType() : null;
        String phaseName = pause != null ? pause.phaseName() : null;
        var record = new HitlDecisionRecord()
                .surface(HitlDecisionRecord.SURFACE_GROUP)
                .conversation(groupConversationId, userId, null)
                .group(groupId)
                .pauseType(pauseType)
                .pausedAt(pause != null ? pause.pausedAt() : null)
                .verdict(HitlDecisionRecord.VERDICT_CANCELLED)
                .decidedBy(cancelledBy)
                .timeoutPolicy(pause != null ? pause.timeoutPolicy() : null)
                .legacy("mode", mode)
                .legacy("pauseReason", pause != null && pause.pauseReason() != null ? pause.pauseReason() : "")
                .legacy("phaseName", phaseName);
        if ("HUMAN_TURN".equals(pauseType)) {
            return record;
        }
        if ("TASK".equals(pauseType)) {
            var category = ApprovalCategories.forGroupTask(hitlConfig, groupId, phaseName);
            record.category(category);
            if (pause.awaitingTaskIds() != null) {
                for (String taskId : pause.awaitingTaskIds()) {
                    record.unit(new HitlDecisionRecord.Unit(UnitKind.TASK, taskId, DecisionOutcome.ABORTED, category, null, null, null, null));
                }
            }
            return record;
        }
        var category = ApprovalCategories.forGroupPhase(hitlConfig, groupId, phaseName);
        return record.category(category)
                .unit(new HitlDecisionRecord.Unit(UnitKind.PHASE, phaseName, DecisionOutcome.ABORTED, category, null, null, null, null));
    }
}
