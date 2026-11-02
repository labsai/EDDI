/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.continuation;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContinuationFireHandlerTest {

    private static final String CONVERSATION_ID = "0123456789abcdef89abcdef";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private IConversationService conversationService;
    private IConversationMemoryStore memoryStore;
    private SimpleMeterRegistry meters;
    private ContinuationFireHandler handler;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        memoryStore = mock(IConversationMemoryStore.class);
        meters = new SimpleMeterRegistry();
        handler = new ContinuationFireHandler(conversationService, memoryStore,
                new ConversationContinuations(mock(IScheduleStore.class), meters));
    }

    private static Map<String, Object> metadata(int number) {
        return ConversationContinuations.metadata(CONVERSATION_ID, number, 5);
    }

    private static ConversationMemorySnapshot stored(ConversationState state, Integer... ranNumbers) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(state);
        for (Integer n : ranNumbers) {
            var step = new ConversationStepSnapshot();
            var workflow = new WorkflowRunSnapshot();
            // As stored: the number may come back as any Number type.
            workflow.getLifecycleTasks().add(new ResultSnapshot(ConversationContinuations.STEP_NUMBER, n.longValue(), null, new Date(), null,
                    false));
            step.getWorkflows().add(workflow);
            snapshot.getConversationSteps().add(step);
        }
        return snapshot;
    }

    /**
     * The turn answers with this state (or as skipped). A completed turn is also
     * stored, as the real step runner does after the reply: the stored conversation
     * then carries the continuation's marker.
     */
    private void turnSettles(ConversationState state, boolean skipped) throws Exception {
        turnSettles(state, skipped, true);
    }

    private void turnSettles(ConversationState state, boolean skipped, boolean storedAfterwards) throws Exception {
        doAnswer(inv -> {
            ConversationResponseHandler responseHandler = inv.getArgument(6);
            InputData input = inv.getArgument(4);
            var answer = new SimpleConversationMemorySnapshot();
            answer.setConversationState(state);
            if (skipped) {
                responseHandler.onSkipped(answer);
            } else {
                if (storedAfterwards) {
                    doReturn(stored(state, numberOf(input))).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
                }
                responseHandler.onComplete(answer);
            }
            return null;
        }).when(conversationService).say(eq(CONVERSATION_ID), any(), any(), any(), any(InputData.class), anyBoolean(), any());
    }

    /** The continuation number the fire put on the turn's input. */
    private static Integer numberOf(InputData input) {
        return input.getContinuationNumber() != null ? input.getContinuationNumber() : 1;
    }

    private double refused(String reason) {
        var counter = meters.find(ConversationContinuations.METRIC_REFUSED).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private double run() {
        var counter = meters.find(ConversationContinuations.METRIC_RUN).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("runs the turn")
    class Runs {

        @Test
        @DisplayName("as an engine-marked continuation: designer input, reserved context, engine-only number, automatic key")
        void inputOfTheTurn() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false);

            var result = handler.fire(metadata(3), null, "carry on", TIMEOUT);

            assertEquals(FireStatus.COMPLETED.name(), result.status());
            var input = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq(CONVERSATION_ID), eq(false), eq(true), any(), input.capture(), eq(false), any());
            assertEquals("carry on", input.getValue().getInput());
            assertEquals(3, input.getValue().getContinuationNumber());
            assertEquals(ContinuationFireHandler.IDEMPOTENCY_KEY_PREFIX + "3", input.getValue().getIdempotencyKey());
            var context = input.getValue().getContext().get(ConversationContinuations.CONTEXT_KEY);
            assertEquals(Map.of("number", 3, "max", 5), context.getValue());
            assertEquals(1.0, run());
        }

        @Test
        @DisplayName("a turn that answered but was not stored fails, so the schedule retries")
        void answeredButNotStored() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false, false);

            var result = handler.fire(metadata(1), null, "", TIMEOUT);

            assertEquals(FireStatus.FAILED.name(), result.status());
            assertEquals(0.0, run(), "not counted as a run");
        }

        @Test
        @DisplayName("an unset input is an empty one")
        void emptyInput() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false);

            handler.fire(metadata(1), null, null, TIMEOUT);

            var input = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(any(), any(), any(), any(), input.capture(), anyBoolean(), any());
            assertEquals("", input.getValue().getInput());
        }

        @Test
        @DisplayName("a continuation turn that FAILED is retried (schedule backoff, then dead letter)")
        void failedTurnIsRetried() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.ERROR, false);

            assertEquals(FireStatus.FAILED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(0.0, run());
        }

        @Test
        @DisplayName("an interrupted continuation turn (cancel, watchdog) stops the chain instead of being retried")
        void interruptedStopsTheChain() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.EXECUTION_INTERRUPTED, false);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(1.0, refused(ConversationContinuations.REASON_INTERRUPTED));
        }

        @Test
        @DisplayName("a turn that does not finish in time is a failed fire: the retry either finds it recorded or runs it")
        void timeout() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            // say() accepts the turn but never answers.
            var result = handler.fire(metadata(1), null, "", Duration.ofMillis(50));
            assertEquals(FireStatus.FAILED.name(), result.status());
        }
    }

    @Nested
    @DisplayName("once only")
    class OnceOnly {

        @Test
        @DisplayName("fire twice: the second finds the first's marker in the stored conversation and runs nothing")
        void fireTwice() throws Exception {
            doReturn(stored(ConversationState.READY)).doReturn(stored(ConversationState.READY, 1)).when(memoryStore)
                    .loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());

            verify(conversationService, times(1)).say(any(), any(), any(), any(), any(), anyBoolean(), any());
            assertEquals(1.0, run());
            assertEquals(1.0, refused(ConversationContinuations.REASON_DUPLICATE));
        }

        @Test
        @DisplayName("a skipped duplicate (the other fire's turn committed first) completes once the marker is there")
        void skippedDuplicate() throws Exception {
            doReturn(stored(ConversationState.READY)).doReturn(stored(ConversationState.READY, 2)).when(memoryStore)
                    .loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, true);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(2), null, "", TIMEOUT).status());
            assertEquals(1.0, refused(ConversationContinuations.REASON_DUPLICATE));
            assertEquals(0.0, run());
        }

        @Test
        @DisplayName("a turn skipped because another turn was running is retried, not lost")
        void skippedWhileBusy() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.IN_PROGRESS, true);

            assertEquals(FireStatus.FAILED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
        }

        @Test
        @DisplayName("a marker of another continuation does not stop this one")
        void otherNumberRuns() throws Exception {
            doReturn(stored(ConversationState.READY, 1)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(2), null, "", TIMEOUT).status());
            verify(conversationService, times(1)).say(any(), any(), any(), any(), any(), anyBoolean(), any());
        }
    }

    @Nested
    @DisplayName("never fires for a conversation that cannot take it")
    class Lifecycle {

        @Test
        @DisplayName("a schedule whose user does not own the conversation runs nothing")
        void ownerMismatch() throws Exception {
            var stored = stored(ConversationState.READY);
            stored.setUserId("victim");
            doReturn(stored).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), "attacker", "", TIMEOUT).status());
            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService);
            assertEquals(2.0, refused(ConversationContinuations.REASON_OWNER_MISMATCH));
        }

        @Test
        @DisplayName("the owner's own schedule runs")
        void ownerMatches() throws Exception {
            var stored = stored(ConversationState.READY);
            stored.setUserId("owner");
            doReturn(stored).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            turnSettles(ConversationState.READY, false);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), "owner", "", TIMEOUT).status());
            assertEquals(1.0, run());
        }

        @Test
        @DisplayName("ENDED: no turn, schedule completes")
        void ended() throws Exception {
            doReturn(stored(ConversationState.ENDED)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService);
            assertEquals(1.0, refused(ConversationContinuations.REASON_ENDED));
        }

        @Test
        @DisplayName("AWAITING_HUMAN (HITL pause): no turn, schedule completes")
        void paused() throws Exception {
            doReturn(stored(ConversationState.AWAITING_HUMAN)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService);
            assertEquals(1.0, refused(ConversationContinuations.REASON_AWAITING_HUMAN));
        }

        @Test
        @DisplayName("a pause that commits after the check is refused by say() and still not run")
        void pausedInBetween() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doThrow(new IConversationService.ConversationAwaitingApprovalException("paused")).when(conversationService)
                    .say(any(), any(), any(), any(), any(), anyBoolean(), any());

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(1.0, refused(ConversationContinuations.REASON_AWAITING_HUMAN));
        }

        @Test
        @DisplayName("deleted or erased (GDPR): no turn, schedule completes")
        void gone() throws Exception {
            doThrow(new IResourceStore.ResourceNotFoundException("gone")).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService);
            assertEquals(1.0, refused(ConversationContinuations.REASON_GONE));
        }

        @Test
        @DisplayName("ended between check and turn: say() refuses, nothing is retried")
        void endedInBetween() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doThrow(new IConversationService.ConversationEndedException("ended")).when(conversationService)
                    .say(any(), any(), any(), any(), any(), anyBoolean(), any());

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
        }

        @Test
        @DisplayName("GDPR Art. 18 restriction: not run, not retried")
        void restricted() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doThrow(new ProcessingRestrictedException("restricted")).when(conversationService)
                    .say(any(), any(), any(), any(), any(), anyBoolean(), any());

            assertEquals(FireStatus.COMPLETED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(1.0, refused(ConversationContinuations.REASON_RESTRICTED));
        }

        @Test
        @DisplayName("undeployed agent: never run on it; retried so a redeploy resumes the job, then dead-lettered")
        void undeployed() throws Exception {
            doReturn(stored(ConversationState.READY)).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doThrow(new IConversationService.AgentNotReadyException("not deployed")).when(conversationService)
                    .say(any(), any(), any(), any(), any(), anyBoolean(), any());

            assertEquals(FireStatus.FAILED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            assertEquals(1.0, refused(ConversationContinuations.REASON_UNDEPLOYED));
            assertEquals(0.0, run());
        }

        @Test
        @DisplayName("a store failure is a failed fire (retried)")
        void storeFailure() throws Exception {
            doThrow(new IResourceStore.ResourceStoreException("down")).when(memoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            assertEquals(FireStatus.FAILED.name(), handler.fire(metadata(1), null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("a schedule without a conversation or number completes without doing anything")
        void malformed() {
            assertEquals(FireStatus.COMPLETED.name(), handler.fire(Map.of(), null, "", TIMEOUT).status());
            assertEquals(FireStatus.COMPLETED.name(), handler.fire(null, null, "", TIMEOUT).status());
            verifyNoInteractions(conversationService, memoryStore);
        }
    }
}
