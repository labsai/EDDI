/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HitlDecisionValidator — editedAnswer and reasonCode, before the CAS")
class HitlDecisionValidatorTest {

    private static ConversationMemorySnapshot ruleSnapshot(String draft, String format) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setHitlPauseType("RULE");
        var run = new WorkflowRunSnapshot();
        var results = new ArrayList<ResultSnapshot>();
        if (draft != null) {
            var candidate = new HitlRevisionCandidate();
            candidate.setDraft(draft);
            candidate.setAnswerFormat(format);
            candidate.setWorkflowId("wf");
            candidate.setLifecycleIndex(1);
            results.add(new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, candidate.toMap(), null, new Date(), "wf", false));
        }
        run.setLifecycleTasks(results);
        var step = new ConversationStepSnapshot();
        step.setWorkflows(List.of(run));
        snapshot.setConversationSteps(List.of(step));
        return snapshot;
    }

    private static AgentConfiguration.HitlConfig editsAllowed(int maxChars) {
        var hitl = new AgentConfiguration.HitlConfig();
        var review = new HitlReviewConfig();
        review.setAllowAnswerEdits(true);
        review.setMaxEditChars(maxChars);
        hitl.setReview(review);
        return hitl;
    }

    private static HitlDecision approveWith(String edit) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setEditedAnswer(edit);
        return decision;
    }

    @Test
    void validEdit() {
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(approveWith("Better.\nTwo lines\twith tab"), ruleSnapshot("draft", "TEXT"),
                editsAllowed(100)));
    }

    @Test
    void editOnRejection() {
        var decision = approveWith("x");
        decision.setVerdict(HitlVerdict.REJECTED);
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(decision, ruleSnapshot("d", "TEXT"), editsAllowed(100)));
    }

    @Test
    void editOnToolPause() {
        var snapshot = ruleSnapshot("d", "TEXT");
        snapshot.setHitlPauseType("TOOL_CALL");
        var e = assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("x"), snapshot, editsAllowed(100)));
        assertTrue(e.getMessage().contains("amendedArguments"));
    }

    @Test
    void editsNotAllowed() {
        assertThrows(IllegalArgumentException.class,
                () -> HitlDecisionValidator.validate(approveWith("x"), ruleSnapshot("d", "TEXT"), new AgentConfiguration.HitlConfig()));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("x"), ruleSnapshot("d", "TEXT"), null));
    }

    @Test
    void noAnswerToEdit() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> HitlDecisionValidator.validate(approveWith("x"), ruleSnapshot(null, null), editsAllowed(100)));
        assertTrue(e.getMessage().contains("no model answer"));
    }

    @Test
    void textRules() {
        var snapshot = ruleSnapshot("d", "TEXT");
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("   "), snapshot, editsAllowed(100)));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("abcdef"), snapshot, editsAllowed(5)));
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(approveWith("abcde"), snapshot, editsAllowed(5)));
        assertThrows(IllegalArgumentException.class,
                () -> HitlDecisionValidator.validate(approveWith("a" + (char) 1 + "b"), snapshot, editsAllowed(100)));
    }

    @Test
    void jsonFormat() {
        var snapshot = ruleSnapshot("{\"a\":1}", HitlRevisionCandidate.FORMAT_JSON);
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(approveWith("{\"a\":2}"), snapshot, editsAllowed(100)));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("[1]"), snapshot, editsAllowed(100)));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(approveWith("not json"), snapshot, editsAllowed(100)));
        assertThrows(IllegalArgumentException.class,
                () -> HitlDecisionValidator.validate(approveWith("{\"a\":2} trailing text"), snapshot, editsAllowed(100)), "trailing tokens");
    }

    @Test
    void reasonCodes() {
        var snapshot = ruleSnapshot("d", "TEXT");
        var reject = new HitlDecision();
        reject.setVerdict(HitlVerdict.REJECTED);
        reject.setReasonCode("FALSE_CLAIM");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(reject, snapshot, null), "built-in vocabulary");

        reject.setReasonCode("MADE_UP");
        var unknown = assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(reject, snapshot, null));
        assertTrue(unknown.getMessage().contains("FALSE_CLAIM"), "names the valid codes");

        var configured = new AgentConfiguration.HitlConfig();
        var review = new HitlReviewConfig();
        review.setReasonCodes(Map.of("MADE_UP", "Custom"));
        configured.setReview(review);
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(reject, snapshot, configured));
        reject.setReasonCode("FALSE_CLAIM");
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(reject, snapshot, configured),
                "a configured vocabulary replaces the built-in one");

        var plainApproval = new HitlDecision();
        plainApproval.setVerdict(HitlVerdict.APPROVED);
        plainApproval.setReasonCode("TONE");
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(plainApproval, snapshot, null));

        var editWithReason = approveWith("x");
        editWithReason.setReasonCode("TONE");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(editWithReason, snapshot, editsAllowed(100)));
    }

    @Test
    void anEditIdenticalToTheDraftTakesNoReasonCode() {
        var snapshot = ruleSnapshot("the draft", "TEXT");
        var same = approveWith("the draft");
        same.setReasonCode("TONE");
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(same, snapshot, editsAllowed(100)),
                "an unchanged edit is a plain approval, which takes no reason code");

        var changed = approveWith("the draft, corrected");
        changed.setReasonCode("TONE");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(changed, snapshot, editsAllowed(100)));
    }

    @Test
    void perCallReasonCodes() {
        var snapshot = ruleSnapshot(null, null);
        snapshot.setHitlPauseType("TOOL_CALL");
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        var call = new ToolCallDecision();
        call.setVerdict(HitlVerdict.APPROVED);
        call.setReasonCode("TONE");
        decision.setToolDecisions(Map.of("c1", call));
        assertTrue(HitlDecisionValidator.hasReviewFields(decision));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(decision, snapshot, null),
                "a reason on a plain per-call approval");
        call.setAmendedArguments("{}");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(decision, snapshot, null));
        call.setAmendedArguments(null);
        call.setVerdict(HitlVerdict.REJECTED);
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(decision, snapshot, null));
        decision.setReasonCode("OFF_POLICY");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(decision, snapshot, null), "top-level code explains the per-call rejection");
    }

    @Test
    void amendedArgumentsIdenticalToTheOriginalTakeNoReasonCode() {
        var snapshot = ruleSnapshot(null, null);
        snapshot.setHitlPauseType("TOOL_CALL");
        var stored = new PendingToolCallBatch.PendingToolCall();
        stored.setCallId("c1");
        stored.setArgumentsRaw("{\"text\":\"hi\"}");
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(stored));
        snapshot.setHitlPendingToolCalls(batch);

        var call = new ToolCallDecision();
        call.setVerdict(HitlVerdict.APPROVED);
        call.setAmendedArguments("{\"text\":\"hi\"}");
        call.setReasonCode("TONE");
        var perCall = new HitlDecision();
        perCall.setVerdict(HitlVerdict.APPROVED);
        perCall.setToolDecisions(Map.of("c1", call));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(perCall, snapshot, null),
                "identical arguments are a plain approval: no per-call reason code");

        call.setReasonCode(null);
        var decisionLevel = new HitlDecision();
        decisionLevel.setVerdict(HitlVerdict.APPROVED);
        decisionLevel.setReasonCode("TONE");
        decisionLevel.setToolDecisions(Map.of("c1", call));
        assertThrows(IllegalArgumentException.class, () -> HitlDecisionValidator.validate(decisionLevel, snapshot, null),
                "nor a decision-level one");

        call.setAmendedArguments("{\"text\":\"hello\"}");
        call.setReasonCode("TONE");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(perCall, snapshot, null), "a real amendment may carry a code");
        assertDoesNotThrow(() -> HitlDecisionValidator.validate(decisionLevel, snapshot, null));
    }

    @Test
    void noReviewFields() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        assertFalse(HitlDecisionValidator.hasReviewFields(decision));
        assertFalse(HitlDecisionValidator.hasReviewFields(null));
    }
}
