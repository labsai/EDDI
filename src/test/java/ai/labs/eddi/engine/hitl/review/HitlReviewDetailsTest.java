/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.review;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HitlReviewDetails — review fields of approval-status")
class HitlReviewDetailsTest {

    private static ConversationMemorySnapshot snapshot(boolean withDraft, boolean refused) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        var results = new ArrayList<ResultSnapshot>();
        if (withDraft) {
            var candidate = new HitlRevisionCandidate();
            candidate.setDraft("{\"password\":\"fixturefixture\"} draft");
            results.add(new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, candidate.toMap(), null, new Date(), "wf", false));
        }
        if (refused) {
            results.add(new ResultSnapshot(HitlReviewDetails.LAST_EDIT_REFUSED_KEY, Map.of("reason", "SCREENED_OUT"), null, new Date(), "wf", false));
        }
        var run = new WorkflowRunSnapshot();
        run.setLifecycleTasks(results);
        var step = new ConversationStepSnapshot();
        step.setWorkflows(List.of(run));
        snapshot.setConversationSteps(List.of(step));
        return snapshot;
    }

    @Test
    @DisplayName("edits allowed: the redacted draft, its format and the cap; plus category, vocabulary and the last refusal")
    void editable() {
        var hitl = new AgentConfiguration.HitlConfig();
        var review = new HitlReviewConfig();
        review.setAllowAnswerEdits(true);
        review.setMaxEditChars(500);
        hitl.setReview(review);
        var details = new LinkedHashMap<String, Object>();

        HitlReviewDetails.forRule(details, snapshot(true, true), hitl);

        @SuppressWarnings("unchecked")
        var editable = (Map<String, Object>) details.get("editableAnswer");
        assertFalse(String.valueOf(editable.get("draft")).contains("fixturefixture"), "served through the secret filter");
        assertEquals("text", editable.get("format"));
        assertEquals(500, editable.get("maxChars"));
        assertEquals("rule:agent-1", details.get("approvalCategory"));
        assertEquals(7, ((List<?>) details.get("reasonCodes")).size());
        assertEquals(Map.of("reason", "SCREENED_OUT"), details.get("lastEditRefused"));
    }

    @Test
    @DisplayName("edits off, or no draft: no editableAnswer")
    void notEditable() {
        var details = new LinkedHashMap<String, Object>();
        HitlReviewDetails.forRule(details, snapshot(true, false), null);
        assertFalse(details.containsKey("editableAnswer"));
        assertFalse(details.containsKey("lastEditRefused"));

        var hitl = new AgentConfiguration.HitlConfig();
        var review = new HitlReviewConfig();
        review.setAllowAnswerEdits(true);
        hitl.setReview(review);
        var noDraft = new LinkedHashMap<String, Object>();
        HitlReviewDetails.forRule(noDraft, snapshot(false, false), hitl);
        assertFalse(noDraft.containsKey("editableAnswer"));
    }

    @Test
    @DisplayName("tool pauses: category per call")
    void toolCalls() {
        var cfg = new ToolApprovalsConfig();
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch("http.post:*");
        rule.setApprovalCategory("github.reply");
        cfg.setRules(List.of(rule));
        var batch = new PendingToolCallBatch();
        batch.setEffectiveToolApprovals(cfg);
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("c1");
        call.setGateReason("http.post:*");
        call.setMatchedRule("http.post:*");
        batch.setCalls(List.of(call));
        var view = new LinkedHashMap<String, Object>();
        view.put("callId", "c1");
        var details = new LinkedHashMap<String, Object>();
        details.put("calls", List.of(view));

        HitlReviewDetails.forToolCall(details, batch, null);

        assertEquals("github.reply", view.get("approvalCategory"));
        assertTrue(details.containsKey("reasonCodes"));
    }
}
