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
import ai.labs.eddi.engine.security.EmailReplyReview;
import ai.labs.eddi.engine.security.TurnOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reply review (email plan v3 §8.2): a mail turn the email channel armed pauses
 * after its whole pipeline, with the whole reply as the draft; the decision
 * runs no task and records what the email channel sends — the turn's text, the
 * approver's, or nothing.
 */
class ConversationEmailReplyReviewTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow first;
    private IExecutableWorkflow last;
    private ILifecycleManager firstManager;
    private ILifecycleManager lastManager;
    private final AtomicReference<String> answer = new AtomicReference<>("Your refund is on its way.");

    @BeforeEach
    void setUp() throws Exception {
        memory = new ConversationMemory("conv1", "agent1", 1, "email:int1:alice@example.com");
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        first = mock(IExecutableWorkflow.class);
        last = mock(IExecutableWorkflow.class);
        firstManager = mock(ILifecycleManager.class);
        lastManager = mock(ILifecycleManager.class);
        when(first.getLifecycleManager()).thenReturn(firstManager);
        when(first.getWorkflowId()).thenReturn("wf1");
        when(last.getLifecycleManager()).thenReturn(lastManager);
        when(last.getWorkflowId()).thenReturn("wf2");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
        // The last workflow answers.
        Answer<Void> answers = invocation -> {
            String text = answer.get();
            if (text != null) {
                memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(text));
            }
            return null;
        };
        doAnswer(answers).when(lastManager).executeLifecycle(any(), any());
        doAnswer(answers).when(lastManager).executeLifecycleFromIndex(any(), anyInt());
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.setConversationState(ConversationState.READY);
    }

    private Conversation conversation() {
        return new Conversation(List.of(first, last), memory, propertiesHandler, outputRenderer);
    }

    private static Map<String, Context> mailTurn(boolean armed) {
        Map<String, Context> context = new LinkedHashMap<>();
        context.put(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL));
        context.put("mail", new Context(Context.ContextType.object, Map.of("messageId", "<m1@example.com>", "subject", "Refund")));
        if (armed) {
            context.put(EmailReplyReview.CONTEXT_KEY, EmailReplyReview.contextValue("always", "The reply waits for review."));
        }
        return context;
    }

    private static HitlDecision decision(HitlVerdict verdict, String edit) {
        var decision = new HitlDecision();
        decision.setVerdict(verdict);
        decision.setEditedAnswer(edit);
        decision.setDecidedBy("reviewer@example.com");
        return decision;
    }

    private Object output(String key) {
        return memory.getCurrentStep().getConversationOutput().get(key);
    }

    @Test
    @DisplayName("an armed mail turn pauses after its last workflow, the whole reply as the draft, the sender told nothing yet")
    void armedTurnPausesAtTheEnd() throws Exception {
        conversation().say("please refund order 7", mailTurn(true));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        assertEquals("RULE", memory.getHitlPauseType());
        assertEquals("wf2", memory.getHitlPausedWorkflowId(), "after the last workflow");
        assertEquals("The reply waits for review.", memory.getHitlPauseReason());
        verify(firstManager).executeLifecycle(any(), any());
        verify(lastManager).executeLifecycle(any(), any());
        assertEquals("Your refund is on its way.", EmailReplyReview.draftOf(memory));
        assertEquals(EmailReplyReview.PENDING, output(EmailReplyReview.OUTPUT_KEY));
    }

    @Test
    @DisplayName("the draft is the whole reply: for a first mail it includes what CONVERSATION_START said")
    void draftIncludesTheStartTurn() throws Exception {
        memory.setConversationState(ConversationState.READY);
        answer.set("Hello, I am the refund desk.");
        Map<String, Context> start = new LinkedHashMap<>();
        start.put(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, TurnOrigin.EMAIL));
        conversation().init(start);
        assertEquals(ConversationState.READY, memory.getConversationState(), "the start turn is not reviewed: it answers no mail");

        answer.set("Your refund is on its way.");
        conversation().say("please refund order 7", mailTurn(true));

        assertEquals("Hello, I am the refund desk.\n\nYour refund is on its way.", EmailReplyReview.draftOf(memory));
    }

    @Test
    @DisplayName("an untainted turn with the key does not wait")
    void untaintedDoesNotWait() throws Exception {
        // A conversation no mail started: no origin on the memory, none on the step.
        memory = new ConversationMemory("conv2", "agent1", 1, "user-1");
        memory.setConversationState(ConversationState.READY);
        Map<String, Context> context = mailTurn(true);
        context.remove(TurnOrigin.CONTEXT_KEY);

        conversation().say("hi", context);

        assertEquals(ConversationState.READY, memory.getConversationState());
        assertNull(memory.getCurrentStep().getData(EmailReplyReview.MARKER));
    }

    @Test
    @DisplayName("a mail turn the channel did not arm does not wait")
    void unarmedDoesNotWait() throws Exception {
        conversation().say("hi", mailTurn(false));

        assertEquals(ConversationState.READY, memory.getConversationState());
        assertNull(output(EmailReplyReview.OUTPUT_KEY));
    }

    @Test
    @DisplayName("a turn with nothing to send does not wait")
    void nothingToSendDoesNotWait() throws Exception {
        answer.set(null);

        conversation().say("hi", mailTurn(true));

        assertEquals(ConversationState.READY, memory.getConversationState());
    }

    @Test
    @DisplayName("an approval runs no task and records approved")
    void approvalRunsNothing() throws Exception {
        conversation().say("please refund order 7", mailTurn(true));

        conversation().resume(decision(HitlVerdict.APPROVED, null));

        assertEquals(ConversationState.READY, memory.getConversationState());
        assertEquals(EmailReplyReview.APPROVED, output(EmailReplyReview.OUTPUT_KEY));
        assertNull(output(EmailReplyReview.TEXT_OUTPUT_KEY));
        assertNull(memory.getCurrentStep().getData(EmailReplyReview.MARKER), "the pending review is gone");
        verify(lastManager, never()).executeLifecycleFromIndex(any(), anyInt());
        verify(lastManager, times(1)).executeLifecycle(any(), any());
    }

    @Test
    @DisplayName("an approval with an edit records the approver's text, the draft stays in the turn's own output")
    void approvalWithEdit() throws Exception {
        conversation().say("please refund order 7", mailTurn(true));

        conversation().resume(decision(HitlVerdict.APPROVED, "Your refund of 20 EUR was sent today."));

        assertEquals(EmailReplyReview.EDITED, output(EmailReplyReview.OUTPUT_KEY));
        assertEquals("Your refund of 20 EUR was sent today.", output(EmailReplyReview.TEXT_OUTPUT_KEY));
        assertTrue(String.valueOf(output(MemoryKeys.OUTPUT_PREFIX)).contains("Your refund is on its way."));
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) memory.getCurrentStep().getData(EmailReplyReview.DECISION_KEY).getResult();
        assertEquals(EmailReplyReview.EDITED, record.get("decision"));
        assertNotNull(record.get("editedSha256"));
        assertEquals("reviewer@example.com", record.get("decidedBy"));
        assertTrue(record.values().stream().noneMatch(value -> String.valueOf(value).contains("20 EUR")), "digests, never the text");
    }

    @Test
    @DisplayName("an edit identical to the draft is a plain approval")
    void identicalEditIsApproval() throws Exception {
        conversation().say("please refund order 7", mailTurn(true));

        conversation().resume(decision(HitlVerdict.APPROVED, "Your refund is on its way."));

        assertEquals(EmailReplyReview.APPROVED, output(EmailReplyReview.OUTPUT_KEY));
        assertNull(output(EmailReplyReview.TEXT_OUTPUT_KEY));
    }

    @Test
    @DisplayName("a rejection records rejected, adds no output and runs nothing")
    void rejection() throws Exception {
        conversation().say("please refund order 7", mailTurn(true));

        conversation().resume(decision(HitlVerdict.REJECTED, null));

        assertEquals(ConversationState.READY, memory.getConversationState());
        assertEquals(EmailReplyReview.REJECTED, output(EmailReplyReview.OUTPUT_KEY));
        assertTrue(!String.valueOf(output(MemoryKeys.OUTPUT_PREFIX)).contains("rejected by a human reviewer"),
                "not the generic rule-pause text: nothing is mailed");
        verify(lastManager, never()).executeLifecycleFromIndex(any(), anyInt());
    }

    @Test
    @DisplayName("a turn held by the email gate before it ran reaches its review once the hold is approved")
    void preRunApprovalReachesReview() throws Exception {
        Map<String, Context> context = mailTurn(true);
        context.put(EmailApprovalGate.CONTEXT_KEY, EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "A mail waits."));
        conversation().say("please refund order 7", context);
        assertNotNull(memory.getCurrentStep().getData(EmailApprovalGate.PRE_RUN_MARKER));
        assertNull(EmailReplyReview.draftOf(memory), "nothing ran yet");

        conversation().resume(decision(HitlVerdict.APPROVED, null));

        assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState(), "the reply now waits for its own review");
        assertEquals("Your refund is on its way.", EmailReplyReview.draftOf(memory));
        verify(lastManager).executeLifecycle(any(), any());
        verify(firstManager).executeLifecycleFromIndex(any(), eq(0));

        conversation().resume(decision(HitlVerdict.APPROVED, "Approved text."));
        assertEquals(ConversationState.READY, memory.getConversationState());
        assertEquals("Approved text.", output(EmailReplyReview.TEXT_OUTPUT_KEY));
    }
}
