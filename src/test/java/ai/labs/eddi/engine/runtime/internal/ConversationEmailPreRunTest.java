/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.security.EmailApprovalGate;
import ai.labs.eddi.engine.security.TurnOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * The email approval gate's pause before the agent runs (email plan v3 §8.1,
 * {@code firstContact} / {@code everyTurn}; §14 item 3): a new conversation
 * pauses before {@code CONVERSATION_START} — no task runs, so no model is
 * called and nothing is spent — and a continued one before its pipeline; the
 * resume then runs the whole turn, and a rejection answers with
 * {@code rejectedReply}.
 */
class ConversationEmailPreRunTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "email:int1:alice@example.com");
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);
        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
    }

    private Conversation conversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    private static Map<String, Context> mailContext(boolean preRun, String rejectedReply) {
        Map<String, Context> context = new LinkedHashMap<>();
        context.put(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL));
        context.put(EmailApprovalGate.CONTEXT_KEY,
                EmailApprovalGate.contextValue("everyTurn", List.of(), preRun, rejectedReply, "A mail waits for approval."));
        return context;
    }

    private static HitlDecision decision(HitlVerdict verdict) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        return decision;
    }

    @Test
    @DisplayName("everyTurn: a new conversation pauses before CONVERSATION_START, with no task run")
    void newConversationPausesBeforeStart() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);

        conversation().init(mailContext(true, null));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        assertEquals("wf1", memory.getHitlPausedWorkflowId());
        assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
        assertEquals("RULE", memory.getHitlPauseType());
        assertEquals("A mail waits for approval.", memory.getHitlPauseReason());
        // No task ran: no LLM task, so no model call and no cost.
        verify(lifecycleManager, never()).executeLifecycle(any(), any());
        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_COST));
        assertNotNull(memory.getCurrentStep().getData(EmailApprovalGate.PRE_RUN_MARKER));
    }

    @Test
    @DisplayName("the start context's own taint is enough: CONVERSATION_START runs before the memory records it")
    void taintFromStartContext() throws Exception {
        conversation().init(mailContext(true, null));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        verify(lifecycleManager, never()).executeLifecycle(any(), any());
    }

    @Test
    @DisplayName("an untainted conversation with a gate in its context runs CONVERSATION_START")
    void untaintedRuns() throws Exception {
        Map<String, Context> context = mailContext(true, null);
        context.remove(TurnOrigin.CONTEXT_KEY);

        conversation().init(context);

        assertEquals(ConversationState.READY, memory.getConversationState());
        verify(lifecycleManager).executeLifecycle(any(), any());
    }

    @Test
    @DisplayName("a mail turn without preRun runs its pipeline")
    void noPreRunRuns() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);
        conversation().init(mailContext(false, null));

        verify(lifecycleManager).executeLifecycle(any(), any());
    }

    @Test
    @DisplayName("everyTurn: a continued conversation pauses before its pipeline, with the mail's input on the step")
    void continuedConversationPausesBeforePipeline() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.setConversationState(ConversationState.READY);

        conversation().say("please refund order 7", mailContext(true, null));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
        verify(lifecycleManager, never()).executeLifecycle(any(), any());
        assertEquals("please refund order 7", memory.getCurrentStep().getConversationOutput().get(MemoryKeys.INPUT.key()));
    }

    @Test
    @DisplayName("approval runs the whole turn from its first task")
    void approvalRunsWholeTurn() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);
        conversation().init(mailContext(true, null));

        var decision = decision(HitlVerdict.APPROVED);
        decision.setEditedAnswer("an edit has nothing to replace");
        conversation().resume(decision);

        verify(lifecycleManager).executeLifecycleFromIndex(any(), eq(0));
        assertEquals(ConversationState.READY, memory.getConversationState());
        assertNull(memory.getCurrentStep().getData(EmailApprovalGate.PRE_RUN_MARKER));
    }

    @Test
    @DisplayName("rejection ends the turn with rejectedReply and runs nothing")
    void rejectionAnswersWithRejectedReply() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);
        conversation().init(mailContext(true, "Sorry, not this time."));

        conversation().resume(decision(HitlVerdict.REJECTED));

        verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
        verify(lifecycleManager, never()).executeLifecycle(any(), any());
        var output = memory.getCurrentStep().getConversationOutput();
        assertTrue(String.valueOf(output.get(MemoryKeys.OUTPUT_PREFIX)).contains("Sorry, not this time."));
        assertEquals(EmailApprovalGate.REJECTED_OUTPUT_VALUE, output.get(EmailApprovalGate.REJECTED_OUTPUT_KEY));
        assertFalse(String.valueOf(output.get(MemoryKeys.OUTPUT_PREFIX)).contains("rejected by a human reviewer"),
                "not the generic rule-pause text");
        assertEquals(ConversationState.READY, memory.getConversationState());
    }

    @Test
    @DisplayName("rejection without rejectedReply (trigger mode) answers nothing")
    void rejectionWithoutReply() throws Exception {
        memory.setOrigin(TurnOrigin.EMAIL);
        conversation().init(mailContext(true, null));

        conversation().resume(decision(HitlVerdict.REJECTED));

        var output = memory.getCurrentStep().getConversationOutput();
        assertNull(output.get(MemoryKeys.OUTPUT_PREFIX));
        assertEquals(EmailApprovalGate.REJECTED_OUTPUT_VALUE, output.get(EmailApprovalGate.REJECTED_OUTPUT_KEY));
    }
}
