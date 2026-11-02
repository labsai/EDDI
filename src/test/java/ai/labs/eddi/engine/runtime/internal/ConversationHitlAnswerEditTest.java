/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.hitl.HitlRejectionAction;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.review.HitlReviewDetails;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionPolicy;
import ai.labs.eddi.engine.lifecycle.model.ReviewerEditRequest;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Conversation — approve a RULE pause with an edited answer")
class ConversationHitlAnswerEditTest {

    private ConversationMemory memory;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;
    private List<AuditEntry> audited;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("64f0c0ffee00000000000001", "agent1", 1, "user1");
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        audited = new ArrayList<>();
        memory.setAuditCollector(audited::add);
    }

    private Conversation conversation() {
        var propertiesHandler = mock(IPropertiesHandler.class);
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
        return new Conversation(List.of(workflow), memory, propertiesHandler, mock(IConversation.IConversationOutputRenderer.class));
    }

    private void pausedWithDraft(Instant pausedAt) {
        memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.SKIP, 0, true, true));
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.setHitlPausedWorkflowId("wf1");
        memory.setHitlPausedAbsoluteTaskIndex(3);
        memory.setHitlPausedAt(pausedAt);
        memory.setHitlPauseType("RULE");
        memory.setHitlPauseReason("check the reply");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ACTIONS.key(), List.of("answer", IConversation.PAUSE_CONVERSATION)));
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId("wf1");
        candidate.setLifecycleIndex(1);
        candidate.setLlmTaskId("writer");
        candidate.setLlmTaskIndex(0);
        candidate.setDraft("The release is Friday.");
        candidate.setAddedToOutput(true);
        candidate.setOutputMark(0);
        memory.getCurrentStep().storeData(new Data<>(HitlRevisionCandidate.STEP_KEY, candidate.toMap()));
    }

    private static HitlDecision approveWith(String edit) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy("alice");
        decision.setEditedAnswer(edit);
        decision.setDecisionId("decision-1");
        return decision;
    }

    @Test
    @DisplayName("applied: the task replaces the draft, the pipeline continues after the pause, the turn is marked edited")
    void applied() throws Exception {
        pausedWithDraft(Instant.now());
        var request = ArgumentCaptor.forClass(ReviewerEditRequest.class);
        when(lifecycleManager.replaceAnswerAt(eq(memory), eq(1), request.capture()))
                .thenReturn(ReviewerEditRequest.Outcome.applied("The release is Monday."));

        conversation().resume(approveWith("The release is Monday."));

        assertEquals("The release is Monday.", request.getValue().editedText());
        assertTrue(request.getValue().screen());
        assertEquals("The release is Friday.", request.getValue().candidate().getDraft());
        verify(lifecycleManager).executeLifecycleFromIndex(memory, 4);
        assertEquals("true", memory.getCurrentStep().getConversationOutput().get(ReviewerEditStep.EDITED_OUTPUT_KEY));
        assertNotNull(memory.getCurrentStep().getData(ReviewerEditStep.APPLIED_STEP_KEY));
        assertEquals(ConversationState.READY, memory.getConversationState());
        assertTrue(audited.stream().noneMatch(e -> HitlApprovalAudit.EDIT_REFUSED_TASK_ID.equals(e.taskId())));
    }

    @Test
    @DisplayName("screened out: the turn pauses again on the same task with a new pause, and the refusal is recorded")
    void refusedRePauses() throws Exception {
        Instant firstPause = Instant.now().minusSeconds(60);
        pausedWithDraft(firstPause);
        when(lifecycleManager.replaceAnswerAt(eq(memory), eq(1), any()))
                .thenReturn(ReviewerEditRequest.Outcome.refused(HitlApprovalAudit.REFUSED_SCREENED_OUT, "pii"));

        conversation().resume(approveWith("call me at 555"));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        assertEquals("wf1", memory.getHitlPausedWorkflowId());
        assertEquals(3, memory.getHitlPausedAbsoluteTaskIndex());
        assertNotEquals(firstPause, memory.getHitlPausedAt(), "a new pause id");
        verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
        var kept = HitlRevisionCandidate.fromMap(memory.getCurrentStep().getData(HitlRevisionCandidate.STEP_KEY).getResult());
        assertEquals("The release is Friday.", kept.getDraft(), "the unchanged draft is up for review again");
        @SuppressWarnings("unchecked")
        var refused = (Map<String, Object>) memory.getCurrentStep().getData(HitlReviewDetails.LAST_EDIT_REFUSED_KEY).getResult();
        assertEquals("SCREENED_OUT", refused.get("reason"));
        assertEquals("pii", refused.get("guardrail"));
        AuditEntry entry = audited.stream().filter(e -> HitlApprovalAudit.EDIT_REFUSED_TASK_ID.equals(e.taskId())).findFirst().orElseThrow();
        assertEquals("decision-1", entry.output().get("decisionId"));
        assertNull(memory.getCurrentStep().getConversationOutput().get(ReviewerEditStep.EDITED_OUTPUT_KEY));
    }

    @Test
    @DisplayName("a plain approval never touches the answer")
    void plainApproval() throws Exception {
        pausedWithDraft(Instant.now());
        var decision = approveWith(null);

        conversation().resume(decision);

        verify(lifecycleManager, never()).replaceAnswerAt(any(), anyInt(), any());
        verify(lifecycleManager).executeLifecycleFromIndex(memory, 4);
    }

    @Test
    @DisplayName("a policy without edits (config changed) refuses as drift and pauses again")
    void noPolicyIsDrift() throws Exception {
        pausedWithDraft(Instant.now());
        memory.setHitlRevisionPolicy(null);

        conversation().resume(approveWith("x"));

        verify(lifecycleManager, never()).replaceAnswerAt(any(), anyInt(), any());
        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
    }
}
