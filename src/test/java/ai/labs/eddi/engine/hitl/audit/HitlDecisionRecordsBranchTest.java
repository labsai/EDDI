/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories.ApprovalCategory;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories.Source;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords.GroupPause;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords.RegularPause;
import ai.labs.eddi.engine.hitl.review.HitlDecisionRecords.TaskOutcome;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit records of decisions and cancellations: which units a pause holds,
 * how each is categorised, and when an edit is recorded.
 */
class HitlDecisionRecordsBranchTest {

    private static final Instant PAUSED = Instant.parse("2026-11-05T10:00:00Z");

    private static HitlDecision decision(HitlVerdict verdict, String decidedBy) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setDecidedBy(decidedBy);
        return decision;
    }

    private static PendingToolCall call(String id, String tool, String args, String gate, String matchedRule) {
        var call = new PendingToolCall();
        call.setCallId(id);
        call.setToolName(tool);
        call.setArgumentsRaw(args);
        call.setGateReason(gate);
        call.setMatchedRule(matchedRule);
        return call;
    }

    private static PendingToolCallBatch batch(PendingToolCall... calls) {
        var batch = new PendingToolCallBatch();
        batch.setCalls(new ArrayList<>(Arrays.asList(calls)));
        return batch;
    }

    private static ToolApprovalsConfig.ApprovalRule rule(String match, String category) {
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch(match);
        rule.setApprovalCategory(category);
        return rule;
    }

    @Test
    void aToolCallDecisionHasOneUnitPerCallWithItsOwnVerdictAndEdit() {
        var batch = batch(call("c1", "send_mail", "{\"to\":\"a@x\"}", "send_*", null), null, call("c2", "lookup", null, "lookup", null));
        var decision = decision(HitlVerdict.APPROVED, "user:alice");
        var amended = new ToolCallDecision();
        amended.setAmendedArguments("{\"to\":\"b@x\"}");
        var rejected = new ToolCallDecision();
        rejected.setVerdict(HitlVerdict.REJECTED);
        rejected.setAmendedArguments("{\"q\":\"ignored\"}");
        rejected.setReasonCode("wrong");
        decision.setToolDecisions(Map.of("c1", amended, "c2", rejected));

        var record = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", "production", decision, true, batch, null, null,
                List.of(), null);
        var units = record.getUnits();
        assertEquals(2, units.size(), "a null call holds no unit");
        assertEquals(UnitKind.TOOL_CALL, units.get(0).kind());
        assertEquals(DecisionOutcome.APPROVED_EDITED, units.get(0).outcome());
        assertEquals(1, units.get(0).edit().fieldsChanged());
        assertEquals(EditMetrics.sha256Hex("{\"to\":\"b@x\"}"), units.get(0).argsDigest(), "the arguments that ran are digested");
        assertEquals(DecisionOutcome.REJECTED, units.get(1).outcome());
        assertNull(units.get(1).edit(), "a rejected call's amendment is not an edit");
        assertEquals("wrong", units.get(1).reasonCode());
        assertEquals(EditMetrics.sha256Hex("{\"q\":\"ignored\"}"), units.get(1).argsDigest());
        assertEquals(new ApprovalCategory("tool:send_*", Source.DERIVED), record.category, "the first call's category");
        assertEquals("TOOL_CALL", record.pauseType);
        assertNull(record.pausedAt, "an unknown pause has no start");
    }

    @Test
    void theGoverningRuleNamesTheCategoryOfTheWholePause() {
        var batch = batch(call("c1", "pay", "{}", "pay_*", "pay_*"));
        var approvals = new ToolApprovalsConfig();
        approvals.setRules(List.of(rule("pay_*", "payments")));
        batch.setEffectiveToolApprovals(approvals);
        batch.setEffectiveRule(rule("pay_*", "payments"));
        var record = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, decision(HitlVerdict.APPROVED, "user:a"), true,
                batch, null, RegularPause.UNKNOWN, null, null);
        assertEquals(new ApprovalCategory("payments", Source.CONFIG), record.category);
        assertEquals(DecisionOutcome.APPROVED, record.getUnits().getFirst().outcome());
        assertNull(record.getUnits().getFirst().edit());
    }

    @Test
    void aToolCallPauseWithoutABatchHasNoUnits() {
        var record = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, decision(HitlVerdict.REJECTED, "user:a"), true,
                null, null, null, null, null);
        assertTrue(record.getUnits().isEmpty());
        assertTrue(HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, decision(HitlVerdict.REJECTED, "user:a"), true,
                new PendingToolCallBatch(), null, null, null, null).getUnits().isEmpty());
    }

    @Test
    void aRulePauseRecordsTheAnswerEditOnlyWhenApprovedWithADraft() {
        var approved = decision(HitlVerdict.APPROVED, "user:a");
        approved.setEditedAnswer("Edited answer");
        var record = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, approved, false, null, null,
                new RegularPause(PAUSED, "WAIT_INDEFINITELY", "Draft answer"), null, "STOP");
        var unit = record.getUnits().getFirst();
        assertEquals(UnitKind.ANSWER, unit.kind());
        assertEquals(DecisionOutcome.APPROVED_EDITED, unit.outcome());
        assertEquals("Edited answer", unit.edit().editedText());
        assertEquals("STOP", record.legacy.get("onRejection"));
        assertEquals(PAUSED, record.pausedAt);

        var noDraft = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, approved, false, null, null,
                RegularPause.UNKNOWN, null, null);
        assertNull(noDraft.getUnits().getFirst().edit());

        var same = decision(HitlVerdict.APPROVED, "user:a");
        same.setEditedAnswer("Draft answer");
        var unchanged = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, same, false, null, null,
                new RegularPause(PAUSED, null, "Draft answer"), null, null);
        assertEquals(DecisionOutcome.APPROVED, unchanged.getUnits().getFirst().outcome(), "an edit that changes nothing is no edit");

        var noVerdict = HitlDecisionRecords.regularDecision("conv-1", "agent-1", 2, "user-1", null, decision(null, "user:a"), false, null, null,
                null, null, null);
        assertEquals("UNKNOWN", noVerdict.verdict);
    }

    @Test
    void aCancelledToolCallPauseAbortsEveryCall() {
        var record = HitlDecisionRecords.regularCancellation("conv-1", "agent-1", 2, "user-1", null, "TOOL_CALL",
                batch(call("c1", "a", "{}", "a", null), null, call("c2", "b", "{}", "b", null)), null, PAUSED, "ABORT", "cancel", "user:x");
        assertEquals(2, record.getUnits().size());
        assertTrue(record.getUnits().stream().allMatch(u -> u.outcome() == DecisionOutcome.ABORTED));
        assertEquals(new ApprovalCategory("tool:a", Source.DERIVED), record.category);
        assertEquals(HitlDecisionRecord.VERDICT_CANCELLED, record.verdict);
    }

    @Test
    void aCancelledRulePauseOrToolPauseWithoutABatchAbortsTheAnswer() {
        var rule = HitlDecisionRecords.regularCancellation("conv-1", "agent-1", 2, "user-1", null, "RULE", null, null, PAUSED, null, "cancel",
                "user:x");
        assertEquals(UnitKind.ANSWER, rule.getUnits().getFirst().kind());
        assertEquals(new ApprovalCategory("rule:agent-1", Source.DERIVED), rule.category);
        var toolWithoutBatch = HitlDecisionRecords.regularCancellation("conv-1", "agent-1", 2, "user-1", null, "TOOL_CALL", null, null, PAUSED,
                null, "cancel", "user:x");
        assertEquals(UnitKind.ANSWER, toolWithoutBatch.getUnits().getFirst().kind());
    }

    @Test
    void aTaskDecisionWithoutPerTaskOutcomesDecidesEveryAwaitingTask() {
        var pause = new GroupPause("TASK", "Build", PAUSED, "WAIT_INDEFINITELY", List.of("t1", "t2"), "tasks need approval");
        var record = HitlDecisionRecords.groupDecision("gc-1", "g-1", "user-1", decision(HitlVerdict.APPROVED, "user:a"), null, pause, null, null);
        assertEquals(List.of("t1", "t2"), record.getUnits().stream().map(HitlDecisionRecord.Unit::unitId).toList());
        assertTrue(record.getUnits().stream().allMatch(u -> u.kind() == UnitKind.TASK && u.outcome() == DecisionOutcome.APPROVED));
        assertEquals(new ApprovalCategory("group:g-1:task:Build", Source.DERIVED), record.category);
        assertEquals("Build", record.legacy.get("phaseName"));
    }

    @Test
    void explicitTaskOutcomesLeaveTheOtherTasksUndecided() {
        var pause = new GroupPause("TASK", "Build", PAUSED, null, List.of("t1", "t2"), null);
        Map<String, TaskOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("t1", new TaskOutcome(HitlVerdict.APPROVED, null, EditMetrics.ofText(EditSummary.Kind.TASK_RESULT, "old", "new")));
        outcomes.put("t2", new TaskOutcome(HitlVerdict.REJECTED, "scope", EditMetrics.ofText(EditSummary.Kind.TASK_RESULT, "same", "same")));
        var record = HitlDecisionRecords.groupDecision("gc-1", "g-1", "user-1", decision(HitlVerdict.APPROVED, "user:a"), null, pause, outcomes,
                null);
        var units = record.getUnits();
        assertEquals(DecisionOutcome.APPROVED_EDITED, units.get(0).outcome());
        assertEquals(DecisionOutcome.REJECTED, units.get(1).outcome());
        assertNull(units.get(1).edit(), "an unchanged result is not recorded as an edit");
        assertEquals("scope", units.get(1).reasonCode());

        var noAwaiting = new GroupPause("TASK", "Build", PAUSED, null, null, null);
        assertTrue(HitlDecisionRecords.groupDecision("gc-1", "g-1", "user-1", decision(HitlVerdict.APPROVED, "user:a"), null, noAwaiting, null,
                null).getUnits().isEmpty());
    }

    @Test
    void aPhaseDecisionRecordsTheSynthesisEdit() {
        var pause = new GroupPause("PHASE", "Synthesis", PAUSED, null, null, null);
        var edited = HitlDecisionRecords.groupDecision("gc-1", "g-1", "user-1", decision(HitlVerdict.APPROVED, "user:a"), null, pause, null,
                EditMetrics.ofText(EditSummary.Kind.SYNTHESIS, "draft", "final"));
        assertEquals(UnitKind.PHASE, edited.getUnits().getFirst().kind());
        assertEquals("Synthesis", edited.getUnits().getFirst().unitId());
        assertEquals(DecisionOutcome.APPROVED_EDITED, edited.getUnits().getFirst().outcome());
        assertEquals(HitlDecisionRecord.SURFACE_GROUP, edited.surface);

        var unknownPause = HitlDecisionRecords.groupDecision("gc-1", "g-1", "user-1", decision(null, "user:a"), null, null, null, null);
        assertEquals("UNKNOWN", unknownPause.verdict);
        assertNull(unknownPause.pausedAt);
        assertEquals(new ApprovalCategory("group:g-1:phase:unknown", Source.DERIVED), unknownPause.category);
    }

    @Test
    void groupCancellationsAbortWhatThePauseHeld() {
        var humanTurn = HitlDecisionRecords.groupCancellation("gc-1", "g-1", "user-1", null,
                new GroupPause("HUMAN_TURN", "Discuss", PAUSED, null, null, null), "cancel", "user:x");
        assertTrue(humanTurn.getUnits().isEmpty(), "a human turn holds no decision units");
        assertEquals("", humanTurn.legacy.get("pauseReason"));

        var task = HitlDecisionRecords.groupCancellation("gc-1", "g-1", "user-1", null,
                new GroupPause("TASK", "Build", PAUSED, null, List.of("t1"), "why"), "cancel", "user:x");
        assertEquals(List.of("t1"), task.getUnits().stream().map(HitlDecisionRecord.Unit::unitId).toList());
        assertEquals("why", task.legacy.get("pauseReason"));
        assertTrue(HitlDecisionRecords.groupCancellation("gc-1", "g-1", "user-1", null,
                new GroupPause("TASK", "Build", PAUSED, null, null, null), "cancel", "user:x").getUnits().isEmpty());

        var phase = HitlDecisionRecords.groupCancellation("gc-1", "g-1", "user-1", null, null, "abort", "system:timeout");
        assertEquals(UnitKind.PHASE, phase.getUnits().getFirst().kind());
        assertEquals(DecisionOutcome.ABORTED, phase.getUnits().getFirst().outcome());
        assertNull(phase.pausedAt);
    }
}
