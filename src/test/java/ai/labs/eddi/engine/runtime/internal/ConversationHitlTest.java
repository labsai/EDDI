/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.ConversationNotReadyException;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Conversation HITL Tests")
class ConversationHitlTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IConversation.IConversationOutputRenderer outputRenderer;
    private IExecutableWorkflow workflow;
    private ILifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("conv1", "agent1", 1, "user1");
        propertiesHandler = mock(IPropertiesHandler.class);
        outputRenderer = mock(IConversation.IConversationOutputRenderer.class);
        workflow = mock(IExecutableWorkflow.class);
        lifecycleManager = mock(ILifecycleManager.class);

        when(workflow.getLifecycleManager()).thenReturn(lifecycleManager);
        when(workflow.getWorkflowId()).thenReturn("wf1");
        when(propertiesHandler.getUserMemoryStore()).thenReturn(null);
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler, outputRenderer);
    }

    private HitlDecision decision(HitlVerdict verdict) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        return d;
    }

    private HitlDecision decision(HitlVerdict verdict, String note, String decidedBy) {
        var d = new HitlDecision();
        d.setVerdict(verdict);
        d.setNote(note);
        d.setDecidedBy(decidedBy);
        return d;
    }

    @Nested
    @DisplayName("AWAITING_HUMAN blocks say()")
    class AwaitingHumanBlocksSayTests {

        @Test
        @DisplayName("say() throws ConversationNotReadyException when AWAITING_HUMAN")
        void sayBlockedWhenAwaitingHuman() {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            var conv = createConversation();

            var ex = assertThrows(ConversationNotReadyException.class,
                    () -> conv.say("hello", Map.of()));
            assertTrue(ex.getMessage().contains("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("rerun() also blocked when AWAITING_HUMAN")
        void rerunBlockedWhenAwaitingHuman() {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            var conv = createConversation();

            assertThrows(ConversationNotReadyException.class,
                    () -> conv.rerun(Map.of()));
        }
    }

    @Nested
    @DisplayName("Pause during say()")
    class PauseDuringSayTests {

        @Test
        @DisplayName("pause during say() sets state to AWAITING_HUMAN")
        void pauseSetsAwaitingHuman() throws Exception {
            memory.setConversationState(ConversationState.READY);

            // Lifecycle throws ConversationPauseException
            doThrow(new ConversationPauseException("wf1", 2, "needs approval"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("approve this", Map.of());

            // After pause, state should be AWAITING_HUMAN (set in finally block won't
            // override because the state is no longer IN_PROGRESS)
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());

            // Verify HITL bookmark was recorded
            assertEquals("wf1", memory.getHitlPausedWorkflowId());
            assertEquals(2, memory.getHitlPausedAbsoluteTaskIndex());
            assertEquals("needs approval", memory.getHitlPauseReason());
            assertNotNull(memory.getHitlPausedAt());
        }

        @Test
        @DisplayName("pause during say() skips postConversationLifecycleTasks — state stays AWAITING_HUMAN")
        void pauseSkipsPostTasks() throws Exception {
            memory.setConversationState(ConversationState.READY);

            doThrow(new ConversationPauseException("wf1", 1, "human review"))
                    .when(lifecycleManager).executeLifecycle(any(), any());

            var conv = createConversation();
            conv.say("check this", Map.of());

            // State should be AWAITING_HUMAN, NOT READY
            // If postConversationLifecycleTasks ran, removeOldInvalidProperties would
            // have cleared step-scoped properties. We verify via state:
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());
        }
    }

    @Nested
    @DisplayName("resume()")
    class ResumeTests {

        @Test
        @DisplayName("resume with APPROVED calls executeLifecycleFromIndex")
        void resumeApprovedCallsFromIndex() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED), Map.of());

            // Should call executeLifecycleFromIndex with resumeFromIndex = 2 + 1 = 3
            verify(lifecycleManager).executeLifecycleFromIndex(memory, 3);
        }

        @Test
        @DisplayName("resume with APPROVED stores decision verdict in memory")
        void resumeApprovedStoresDecision() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED, "looks good", "admin"), Map.of());

            // Verify decision data was stored in current step
            var step = memory.getCurrentStep();
            assertNotNull(step.getLatestData("hitl:decision_verdict"));
            assertEquals("APPROVED", step.getLatestData("hitl:decision_verdict").getResult());
            assertNotNull(step.getLatestData("hitl:decision_note"));
            assertEquals("looks good", step.getLatestData("hitl:decision_note").getResult());
            assertNotNull(step.getLatestData("hitl:decision_by"));
            assertEquals("admin", step.getLatestData("hitl:decision_by").getResult());
        }

        @Test
        @DisplayName("resume with REJECTED skips executeLifecycleFromIndex")
        void resumeRejectedSkipsExecution() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED), Map.of());

            // REJECTED should NOT call executeLifecycleFromIndex or executeLifecycle
            verify(lifecycleManager, never()).executeLifecycleFromIndex(any(), anyInt());
            verify(lifecycleManager, never()).executeLifecycle(any(), any());
        }

        @Test
        @DisplayName("resume with REJECTED clears HITL bookmark")
        void resumeRejectedClearsBookmark() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(2);
            memory.setHitlPausedAt(java.time.Instant.now());
            memory.setHitlPauseReason("some reason");

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.REJECTED), Map.of());

            assertNull(memory.getHitlPausedWorkflowId());
            assertEquals(-1, memory.getHitlPausedAbsoluteTaskIndex());
            assertNull(memory.getHitlPausedAt());
            assertNull(memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("resume when not AWAITING_HUMAN throws ConversationNotReadyException")
        void resumeNotAwaitingThrows() {
            memory.setConversationState(ConversationState.READY);

            var conv = createConversation();

            var ex = assertThrows(ConversationNotReadyException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED), Map.of()));
            assertTrue(ex.getMessage().contains("AWAITING_HUMAN"));
        }

        @Test
        @DisplayName("resume when IN_PROGRESS throws ConversationNotReadyException")
        void resumeInProgressThrows() {
            memory.setConversationState(ConversationState.IN_PROGRESS);

            var conv = createConversation();

            assertThrows(ConversationNotReadyException.class,
                    () -> conv.resume(decision(HitlVerdict.APPROVED), Map.of()));
        }

        @Test
        @DisplayName("resume re-pause keeps AWAITING_HUMAN state")
        void resumeRePauseKeepsAwaitingHuman() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(1);

            // The resumed pipeline hits another PAUSE
            doThrow(new ConversationPauseException("wf1", 3, "second approval needed"))
                    .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED), Map.of());

            // Should be AWAITING_HUMAN again (from the second pause)
            assertEquals(ConversationState.AWAITING_HUMAN, memory.getConversationState());

            // New bookmark should be set for the second pause point
            assertEquals("wf1", memory.getHitlPausedWorkflowId());
            assertEquals(3, memory.getHitlPausedAbsoluteTaskIndex());
            assertEquals("second approval needed", memory.getHitlPauseReason());
        }

        @Test
        @DisplayName("resume with APPROVED sets state to READY after completion")
        void resumeApprovedSetsReadyAfterCompletion() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED), Map.of());

            // After successful resume without another pause/stop, state should be READY
            assertEquals(ConversationState.READY, memory.getConversationState());
        }

        @Test
        @DisplayName("resume with APPROVED renders output")
        void resumeRendersOutput() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED), Map.of());

            verify(outputRenderer).renderOutput(memory);
        }

        @Test
        @DisplayName("resume catches ConversationStopException and ends conversation")
        void resumeStopEndsConversation() throws Exception {
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            memory.setHitlPausedWorkflowId("wf1");
            memory.setHitlPausedAbsoluteTaskIndex(0);

            doThrow(new ConversationStopException())
                    .when(lifecycleManager).executeLifecycleFromIndex(any(), anyInt());

            var conv = createConversation();
            conv.resume(decision(HitlVerdict.APPROVED), Map.of());

            assertEquals(ConversationState.ENDED, memory.getConversationState());
        }
    }
}
