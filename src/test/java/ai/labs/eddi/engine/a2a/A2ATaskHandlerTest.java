/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.a2a.A2AModels.A2ABusyException;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.CancelResult;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.ChunkEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.FinalEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.StreamEvent;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.TaskEvent;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.CancelOutcome;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link A2ATaskHandler}: how a conversation turn becomes an A2A
 * task state, that a task never answers with another turn's output, the
 * in-flight bound, re-deriving tasks from the conversation store, and — since
 * task and context ids are payload — isolation between peers.
 */
class A2ATaskHandlerTest {

    private static final String PEER_A = "peer-a";
    private static final String PEER_B = "peer-b";
    private static final String AGENT = "agent-123";

    private IConversationService conversationService;
    private ICacheFactory cacheFactory;
    private CachedA2ATaskStore taskStore;
    private AgentCardService agentCardService;
    private A2AInFlightLimiter limiter;
    private SimpleMeterRegistry meterRegistry;
    private A2ATaskHandler handler;
    private final AtomicInteger conversations = new AtomicInteger();

    /** Every conversation the stubbed service "persisted", keyed by id. */
    private final Map<String, SimpleConversationMemorySnapshot> stored = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, Object>getCache(anyString())).thenAnswer(invocation -> new MapCache<>());
        taskStore = new CachedA2ATaskStore(cacheFactory);
        agentCardService = mock(AgentCardService.class);
        when(agentCardService.getAgentCard(anyString())).thenReturn(mock(A2AModels.AgentCard.class));
        meterRegistry = new SimpleMeterRegistry();
        limiter = new A2AInFlightLimiter(8, meterRegistry);
        when(conversationService.startConversation(eq(Environment.production), anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> {
                    String id = "conv" + conversations.incrementAndGet();
                    var snapshot = new SimpleConversationMemorySnapshot();
                    snapshot.setUserId(invocation.getArgument(2));
                    snapshot.setAgentId(invocation.getArgument(1));
                    snapshot.setConversationState(ConversationState.READY);
                    stored.put(id, snapshot);
                    return new ConversationResult(id, URI.create("/conversations/" + id));
                });
        when(conversationService.readConversation(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            var snapshot = stored.get((String) invocation.getArgument(0));
            if (snapshot == null) {
                throw new ResourceNotFoundException("no such conversation");
            }
            return snapshot;
        });
        handler = handlerFor(PEER_A);
    }

    private A2ATaskHandler handlerFor(String principalName) {
        return handlerFor(principalName, 60, Optional.empty());
    }

    private A2ATaskHandler handlerFor(String principalName, int agentTimeout, Optional<Integer> override) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn((Principal) () -> principalName);
        return new A2ATaskHandler(conversationService, taskStore, identity, agentCardService, limiter, meterRegistry, agentTimeout, override);
    }

    private static SendRequest send(String text) {
        return new SendRequest(text, "m1", null, null, null, false, null, Dialect.V1_0);
    }

    private static SendRequest sendInContext(String text, String contextId) {
        return new SendRequest(text, "m1", contextId, null, null, false, null, Dialect.V1_0);
    }

    private static SendRequest legacy(String text, String taskId, String contextId) {
        return new SendRequest(text, null, contextId, null, taskId, false, null, Dialect.LEGACY);
    }

    /** An output of the turn that ran {@code input} for task {@code taskId}. */
    private static ConversationOutput output(String taskId, String text) {
        var output = new ConversationOutput();
        if (text != null) {
            output.put("output", List.of(Map.of("type", "text", "text", text)));
        }
        output.put("context", Map.of(A2ATaskHandler.CONTEXT_TASK_ID, taskId));
        return output;
    }

    private static String taskIdOf(InputData input) {
        return input.getContext().get(A2ATaskHandler.CONTEXT_TASK_ID).getValue().toString();
    }

    /**
     * Answers every say with a turn ending in {@code state}, whose output carries
     * the turn's own task id and {@code text}; the conversation is "persisted" so a
     * later lookup can find it.
     */
    private void sayCompletes(ConversationState state, String text) throws Exception {
        doAnswer(invocation -> {
            String conversationId = invocation.getArgument(2);
            InputData input = invocation.getArgument(6);
            ConversationResponseHandler responseHandler = invocation.getArgument(8);
            var snapshot = stored.get(conversationId);
            snapshot.setConversationState(state);
            var outputs = new ArrayList<>(snapshot.getConversationOutputs());
            outputs.add(output(taskIdOf(input), text));
            snapshot.setConversationOutputs(outputs);
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(state);
            turn.setConversationOutputs(List.of(outputs.getLast()));
            responseHandler.onComplete(turn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    /** A say that is accepted and never reports back. */
    private void sayNeverReturns() throws Exception {
        doNothing().when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    // ==================== how a turn becomes a task ====================

    @Nested
    @DisplayName("send — the task's state is its own turn's outcome")
    class Send {

        @Test
        @DisplayName("a completed turn: completed, its answer as the artifact, a server-issued context and task id")
        void completed() throws Exception {
            sayCompletes(ConversationState.READY, "Hello from EDDI!");

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.completed, task.state());
            assertEquals("Hello from EDDI!", task.artifacts().getFirst().parts().getFirst().text());
            assertEquals("conv1", task.contextId(), "a context the server opens is its conversation");
            assertTrue(task.id().startsWith("conv1_"), task.id());
            assertEquals(2, task.history().size());
            assertEquals(1.0, meterRegistry.counter("eddi.a2a.tasks", "state", "completed").count());
        }

        @Test
        @DisplayName("an ERROR turn is failed — not completed — and carries no answer and no internal detail")
        void errorIsFailed() throws Exception {
            sayCompletes(ConversationState.ERROR, null);

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.failed, task.state());
            assertNull(task.artifacts());
            assertEquals(A2ATaskHandler.STATUS_FAILED, task.status().message().parts().getFirst().text());
        }

        @Test
        @DisplayName("a HITL pause is input-required, with the agent's pause announcement as the status message")
        void pauseIsInputRequired() throws Exception {
            sayCompletes(ConversationState.AWAITING_HUMAN, "I need your approval before I can run calculate.");

            A2ATask task = handler.send(AGENT, send("usetool"));

            assertEquals(TaskState.input_required, task.state());
            assertNull(task.artifacts(), "a pause announcement is not an answer");
            assertEquals("I need your approval before I can run calculate.", task.status().message().parts().getFirst().text());
        }

        @Test
        @DisplayName("an interrupted turn is canceled")
        void interruptedIsCanceled() throws Exception {
            sayCompletes(ConversationState.EXECUTION_INTERRUPTED, null);

            assertEquals(TaskState.canceled, handler.send(AGENT, send("Hi")).state());
        }

        @Test
        @DisplayName("an output that carries another task's id is never handed back as this task's answer")
        void anotherTurnsOutputIsNotTheAnswer() throws Exception {
            doAnswer(invocation -> {
                ConversationResponseHandler responseHandler = invocation.getArgument(8);
                var turn = new SimpleConversationMemorySnapshot();
                turn.setConversationState(ConversationState.READY);
                turn.setConversationOutputs(List.of(output("some-earlier-task", "the PREVIOUS answer")));
                responseHandler.onComplete(turn);
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertFalse(String.valueOf(task).contains("PREVIOUS"), "stale answer returned: " + task);
        }

        @Test
        @DisplayName("a skipped turn on a paused conversation is input-required and never returns the previous answer")
        void skippedWhilePaused() throws Exception {
            doAnswer(invocation -> {
                ConversationResponseHandler responseHandler = invocation.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
                snapshot.setConversationOutputs(List.of(output("previous-task", "the PREVIOUS answer")));
                responseHandler.onSkipped(snapshot);
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.input_required, task.state());
            assertEquals(A2ATaskHandler.STATUS_SKIPPED_AWAITING_APPROVAL, task.status().message().parts().getFirst().text());
            assertFalse(String.valueOf(task).contains("PREVIOUS"));
        }

        @Test
        @DisplayName("a skipped turn on a busy conversation is rejected")
        void skippedWhileBusy() throws Exception {
            doAnswer(invocation -> {
                ConversationResponseHandler responseHandler = invocation.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationState(ConversationState.IN_PROGRESS);
                snapshot.setConversationOutputs(List.of(output("previous-task", "the PREVIOUS answer")));
                responseHandler.onSkipped(snapshot);
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.rejected, task.state());
            assertFalse(String.valueOf(task).contains("PREVIOUS"));
        }

        @Test
        @DisplayName("a conversation already paused at submit is input-required, not an internal error")
        void pausedAtSubmit() throws Exception {
            doThrow(new IConversationService.ConversationAwaitingApprovalException("paused"))
                    .when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            assertEquals(TaskState.input_required, handler.send(AGENT, send("Hi")).state());
        }

        @Test
        @DisplayName("a turn that fails to start is a failed task, and the exception text stays in the log")
        void failureToStartIsFailed() throws Exception {
            doThrow(new IllegalStateException("mongodb://admin:s3cr3t@db"))
                    .when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.failed, task.state());
            assertFalse(String.valueOf(task).contains("s3cr3t"));
        }

        @Test
        @DisplayName("a store failure while settling still answers the waiting peer, instead of leaving it to time out")
        void storeFailureWhileSettlingStillAnswers() throws Exception {
            sayCompletes(ConversationState.READY, "ok");
            taskStore = spy(taskStore);
            doThrow(new IllegalStateException("store down")).when(taskStore).findTask(anyString(), anyString());
            handler = handlerFor(PEER_A, 60, Optional.of(5));

            long started = System.currentTimeMillis();
            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(TaskState.failed, task.state());
            assertTrue(System.currentTimeMillis() - started < 4000, "must not wait out the task timeout");
            assertEquals(0, limiter.inFlight());
        }

        @Test
        @DisplayName("every turn carries its task id in the input context")
        void turnCarriesItsTaskId() throws Exception {
            sayCompletes(ConversationState.READY, "ok");

            A2ATask task = handler.send(AGENT, send("Hi"));

            var input = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(eq(Environment.production), eq(AGENT), eq("conv1"), any(), any(), any(), input.capture(), anyBoolean(),
                    any());
            assertEquals(task.id(), taskIdOf(input.getValue()));
            assertEquals("Hi", input.getValue().getInput());
        }

        @Test
        @DisplayName("returnImmediately answers working without waiting for the turn")
        void returnImmediately() throws Exception {
            sayNeverReturns();

            A2ATask task = handler.send(AGENT, new SendRequest("Hi", "m", null, null, null, true, null, Dialect.V1_0));

            assertEquals(TaskState.working, task.state());
        }

        @Test
        @DisplayName("a turn that outlives the wait answers working rather than an error")
        void outlivesTheWait() throws Exception {
            sayNeverReturns();
            handler = handlerFor(PEER_A, 60, Optional.of(1));

            assertEquals(TaskState.working, handler.send(AGENT, send("Hi")).state());
        }
    }

    @Nested
    @DisplayName("contexts")
    class Contexts {

        @Test
        @DisplayName("a second message in a context continues its conversation")
        void contextReusesTheConversation() throws Exception {
            sayCompletes(ConversationState.READY, "ok");

            A2ATask first = handler.send(AGENT, send("one"));
            A2ATask second = handler.send(AGENT, sendInContext("two", first.contextId()));

            assertEquals(first.contextId(), second.contextId());
            assertNotEquals(first.id(), second.id());
            verify(conversationService, times(1)).startConversation(any(), anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("a server-issued context still resolves after the task store forgot it — it is the conversation id")
        void issuedContextSurvivesALostStore() throws Exception {
            sayCompletes(ConversationState.READY, "ok");
            A2ATask first = handler.send(AGENT, send("one"));
            taskStore = new CachedA2ATaskStore(cacheFactory); // a fresh, empty store — a restart
            handler = handlerFor(PEER_A);

            handler.send(AGENT, sendInContext("two", first.contextId()));

            verify(conversationService, times(1)).startConversation(any(), anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("an ended context conversation is replaced by a fresh one rather than failing the task")
        void endedContextContinuesInAFreshConversation() throws Exception {
            sayCompletes(ConversationState.READY, "ok");
            A2ATask first = handler.send(AGENT, send("one"));
            doThrow(new IConversationService.ConversationEndedException("ended")).doAnswer(invocation -> {
                ConversationResponseHandler responseHandler = invocation.getArgument(8);
                InputData input = invocation.getArgument(6);
                var turn = new SimpleConversationMemorySnapshot();
                turn.setConversationState(ConversationState.READY);
                turn.setConversationOutputs(List.of(output(taskIdOf(input), "fresh answer")));
                responseHandler.onComplete(turn);
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            A2ATask second = handler.send(AGENT, sendInContext("two", first.contextId()));

            assertEquals(TaskState.completed, second.state());
            assertEquals("fresh answer", second.artifacts().getFirst().parts().getFirst().text());
            assertEquals(Optional.of("conv2"), taskStore.findContextConversation(PEER_A, first.contextId()));
        }

        @Test
        @DisplayName("a legacy task keeps the peer's id, and no context is invented for it")
        void legacyKeepsItsContract() throws Exception {
            sayCompletes(ConversationState.READY, "ok");

            A2ATask task = handler.send(AGENT, legacy("Hi", "task-1", null));

            assertEquals("task-1", task.id());
            assertNull(task.contextId());
        }
    }

    @Nested
    @DisplayName("continuing a task")
    class ContinueTask {

        @Test
        @DisplayName("a message naming a non-terminal task continues it in its conversation, under its id")
        void continuesAnInputRequiredTask() throws Exception {
            sayCompletes(ConversationState.AWAITING_HUMAN, "approve?");
            A2ATask paused = handler.send(AGENT, send("usetool"));
            sayCompletes(ConversationState.READY, "done");

            A2ATask continued = handler.send(AGENT, new SendRequest("more", "m2", null, paused.id(), null, false, null, Dialect.V1_0));

            assertEquals(paused.id(), continued.id());
            assertEquals(TaskState.completed, continued.state());
        }

        @Test
        void aTerminalTaskTakesNoFurtherMessages() throws Exception {
            sayCompletes(ConversationState.READY, "done");
            A2ATask done = handler.send(AGENT, send("Hi"));

            var e = assertThrows(InvalidA2ARequestException.class,
                    () -> handler.send(AGENT, new SendRequest("more", "m2", null, done.id(), null, false, null, Dialect.V1_0)));
            assertEquals(A2AModels.ERROR_UNSUPPORTED_OPERATION, e.getCode());
        }

        @Test
        void anUnknownTaskIsTaskNotFound() {
            var e = assertThrows(InvalidA2ARequestException.class,
                    () -> handler.send(AGENT, new SendRequest("more", "m2", null, "nope", null, false, null, Dialect.V1_0)));
            assertEquals(A2AModels.ERROR_TASK_NOT_FOUND, e.getCode());
        }
    }

    // ==================== refusals before any turn ====================

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an agent that was never opted into A2A is refused, and no conversation is started")
        void notA2aEnabled() throws Exception {
            when(agentCardService.getAgentCard(AGENT)).thenReturn(null);

            assertThrows(InvalidA2ARequestException.class, () -> handler.send(AGENT, send("Hi")));
            verify(conversationService, never()).startConversation(any(), anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("input above the size cap is invalid params and never reaches the agent")
        void oversizedInput() throws Exception {
            doThrow(new IConversationService.InputTooLargeException(10, 5))
                    .when(conversationService).requireInputWithinLimit(any());

            assertThrows(InvalidA2ARequestException.class, () -> handler.send(AGENT, send("Hi")));
            verify(conversationService, never()).startConversation(any(), anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("blank text is refused")
        void blankText() {
            assertThrows(InvalidA2ARequestException.class, () -> handler.send(AGENT, send("  ")));
        }
    }

    // ==================== the in-flight bound ====================

    @Nested
    @DisplayName("in-flight bound")
    class Bound {

        @Test
        @DisplayName("a send beyond the bound is refused before any conversation is started; the slot frees when the turn settles")
        void excessIsRefused() throws Exception {
            limiter = new A2AInFlightLimiter(1, meterRegistry);
            handler = handlerFor(PEER_A);
            AtomicReference<ConversationResponseHandler> pending = new AtomicReference<>();
            AtomicReference<String> pendingTask = new AtomicReference<>();
            doAnswer(invocation -> {
                pending.set(invocation.getArgument(8));
                pendingTask.set(taskIdOf(invocation.getArgument(6)));
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());

            handler.send(AGENT, new SendRequest("first", "m", null, null, null, true, null, Dialect.V1_0));
            assertEquals(1, limiter.inFlight());

            assertThrows(A2ABusyException.class, () -> handler.send(AGENT, send("second")));
            verify(conversationService, times(1)).startConversation(any(), anyString(), anyString(), anyMap());

            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.READY);
            turn.setConversationOutputs(List.of(output(pendingTask.get(), "ok")));
            pending.get().onComplete(turn);
            assertEquals(0, limiter.inFlight());
        }

        @Test
        @DisplayName("a refused request releases its slot")
        void refusedRequestReleasesItsSlot() {
            when(agentCardService.getAgentCard(AGENT)).thenReturn(null);

            assertThrows(InvalidA2ARequestException.class, () -> handler.send(AGENT, send("Hi")));

            assertEquals(0, limiter.inFlight());
        }

        @Test
        @DisplayName("the limiter's lease releases a slot no turn ever reported back on")
        void leaseReleases() throws Exception {
            var small = new A2AInFlightLimiter(1, null);
            var permit = small.tryAcquire(1);
            assertNotNull(permit);
            assertNull(small.tryAcquire(1));
            long deadline = System.currentTimeMillis() + 5000;
            while (!permit.isReleased() && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertTrue(permit.isReleased());
            assertEquals(0, small.inFlight());
            permit.release(); // idempotent
            assertEquals(0, small.inFlight());
        }

        @Test
        @DisplayName("a normal release cancels the lease timer instead of leaving it queued")
        void releaseCancelsTheLeaseTimer() {
            var small = new A2AInFlightLimiter(4, null);
            var first = small.tryAcquire(3600);
            var second = small.tryAcquire(3600);
            assertEquals(2, small.pendingLeases());

            first.release();
            second.release();

            assertEquals(0, small.pendingLeases());
            small.shutdown();
        }

        @Test
        void nonPositiveCapacityFallsBackToTheDefault() {
            assertEquals(A2AInFlightLimiter.DEFAULT_MAX_CONCURRENT, new A2AInFlightLimiter(0, null).capacity());
        }
    }

    // ==================== get ====================

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        void unknownTaskIsNull() {
            assertNull(handler.get("nope", null));
        }

        @Test
        @DisplayName("a completed task reads back completed, with its answer")
        void completedReadsBack() throws Exception {
            sayCompletes(ConversationState.READY, "answer");
            A2ATask task = handler.send(AGENT, send("Hi"));

            A2ATask read = handler.get(task.id(), null);

            assertEquals(TaskState.completed, read.state());
            assertEquals("answer", read.artifacts().getFirst().parts().getFirst().text());
        }

        @Test
        @DisplayName("historyLength 0 omits the history")
        void historyLengthZero() throws Exception {
            sayCompletes(ConversationState.READY, "answer");
            A2ATask task = handler.send(AGENT, send("Hi"));

            assertNull(handler.get(task.id(), 0).history());
            assertEquals(1, handler.get(task.id(), 1).history().size());
        }

        @Test
        @DisplayName("after a restart a task this server issued is re-derived from its conversation")
        void derivedAfterRestart() throws Exception {
            sayCompletes(ConversationState.READY, "remembered");
            A2ATask task = handler.send(AGENT, send("Hi"));
            taskStore = new CachedA2ATaskStore(cacheFactory);
            handler = handlerFor(PEER_A);

            A2ATask read = handler.get(task.id(), null);

            assertNotNull(read);
            assertEquals(TaskState.completed, read.state());
            assertEquals("remembered", read.artifacts().getFirst().parts().getFirst().text());
        }

        @Test
        @DisplayName("an input-required task becomes completed once the approval resolved and the turn finished")
        void resumedPauseCompletes() throws Exception {
            sayCompletes(ConversationState.AWAITING_HUMAN, "approve?");
            A2ATask task = handler.send(AGENT, send("usetool"));
            // The reviewer approves; the resumed turn finishes in the same step.
            var conversation = stored.get(task.contextId());
            conversation.setConversationState(ConversationState.READY);
            conversation.setConversationOutputs(List.of(output(task.id(), "42")));

            A2ATask read = handler.get(task.id(), null);

            assertEquals(TaskState.completed, read.state());
            assertEquals("42", read.artifacts().getFirst().parts().getFirst().text());
        }

        @Test
        @DisplayName("an earlier task of a context reads completed once a later turn ran")
        void laterTurnCompletesEarlierTask() throws Exception {
            sayCompletes(ConversationState.READY, "first answer");
            A2ATask first = handler.send(AGENT, send("one"));
            handler.send(AGENT, sendInContext("two", first.contextId()));
            taskStore = new CachedA2ATaskStore(cacheFactory);
            handler = handlerFor(PEER_A);

            A2ATask read = handler.get(first.id(), null);

            assertEquals(TaskState.completed, read.state());
            assertEquals("first answer", read.artifacts().getFirst().parts().getFirst().text());
        }
    }

    // ==================== cancel ====================

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void unknownTaskIsNotFound() {
            assertEquals(CancelResult.NOT_FOUND, handler.cancel("nope").result());
        }

        @Test
        @DisplayName("a terminal task is not cancelable and its conversation is left alone")
        void terminalIsNotCancelable() throws Exception {
            sayCompletes(ConversationState.READY, "done");
            A2ATask task = handler.send(AGENT, send("Hi"));

            assertEquals(CancelResult.NOT_CANCELABLE, handler.cancel(task.id()).result());
            verify(conversationService, never()).cancelConversation(anyString(), any(), any());
            verify(conversationService, never()).endConversation(anyString());
        }

        @Test
        @DisplayName("a running task is cancelled — the turn stops, the conversation (the context) is not ended")
        void runningIsCanceled() throws Exception {
            AtomicReference<ConversationResponseHandler> pending = new AtomicReference<>();
            doAnswer(invocation -> {
                pending.set(invocation.getArgument(8));
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
            when(conversationService.cancelConversation(anyString(), any(), any())).thenReturn(CancelOutcome.CANCELLED);
            A2ATask task = handler.send(AGENT, new SendRequest("slow", "m", null, null, null, true, null, Dialect.V1_0));

            var outcome = handler.cancel(task.id());

            assertEquals(CancelResult.CANCELED, outcome.result());
            assertEquals(TaskState.canceled, outcome.task().state());
            verify(conversationService).cancelConversation(eq("conv1"), eq(ControlSignal.CANCEL_GRACEFUL), eq("a2a:" + PEER_A));
            verify(conversationService, never()).endConversation(anyString());

            // The turn finishes after the cancel: its late outcome does not un-cancel the
            // task.
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.READY);
            turn.setConversationOutputs(List.of(output(task.id(), "late")));
            pending.get().onComplete(turn);
            assertEquals(TaskState.canceled, handler.get(task.id(), null).state());
            assertEquals(CancelResult.NOT_CANCELABLE, handler.cancel(task.id()).result());
        }

        @Test
        @DisplayName("a task queued behind a sibling's running turn is not cancelable — the sibling's turn is left alone")
        void queuedTaskDoesNotCancelItsSibling() throws Exception {
            List<ConversationResponseHandler> pending = new ArrayList<>();
            List<String> taskIds = new ArrayList<>();
            doAnswer(invocation -> {
                pending.add(invocation.getArgument(8));
                taskIds.add(taskIdOf(invocation.getArgument(6)));
                return null;
            }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
            when(conversationService.cancelConversation(anyString(), any(), any())).thenReturn(CancelOutcome.CANCELLED);
            A2ATask running = handler.send(AGENT, new SendRequest("first", "m", null, null, null, true, null, Dialect.V1_0));
            A2ATask queued = handler.send(AGENT, new SendRequest("second", "m", running.contextId(), null, null, true, null, Dialect.V1_0));

            var outcome = handler.cancel(queued.id());

            assertEquals(CancelResult.NOT_CANCELABLE, outcome.result());
            verify(conversationService, never()).cancelConversation(anyString(), any(), any());

            // Once the turn ahead has settled, the queued task is the running one and
            // cancels.
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.READY);
            turn.setConversationOutputs(List.of(output(taskIds.getFirst(), "ok")));
            pending.getFirst().onComplete(turn);
            assertEquals(CancelResult.CANCELED, handler.cancel(queued.id()).result());
        }

        @Test
        @DisplayName("the head of the queue — the running turn — is cancelable")
        void runningHeadIsCancelable() throws Exception {
            sayNeverReturns();
            when(conversationService.cancelConversation(anyString(), any(), any())).thenReturn(CancelOutcome.CANCELLED);
            A2ATask running = handler.send(AGENT, new SendRequest("first", "m", null, null, null, true, null, Dialect.V1_0));
            handler.send(AGENT, new SendRequest("second", "m", running.contextId(), null, null, true, null, Dialect.V1_0));

            assertEquals(CancelResult.CANCELED, handler.cancel(running.id()).result());
        }

        @Test
        @DisplayName("a task whose turn settled between the read and the cancel is not cancelable")
        void nothingToCancel() throws Exception {
            sayNeverReturns();
            when(conversationService.cancelConversation(anyString(), any(), any())).thenReturn(CancelOutcome.NOTHING_TO_CANCEL);
            A2ATask task = handler.send(AGENT, new SendRequest("x", "m", null, null, null, true, null, Dialect.V1_0));

            assertEquals(CancelResult.NOT_CANCELABLE, handler.cancel(task.id()).result());
        }
    }

    // ==================== streaming ====================

    @Nested
    @DisplayName("stream")
    class Stream {

        @Test
        @DisplayName("task first, tokens as chunks, the settled task last")
        void eventsInOrder() throws Exception {
            doAnswer(invocation -> {
                InputData input = invocation.getArgument(6);
                StreamingResponseHandler streaming = invocation.getArgument(7);
                streaming.onToken("Hel");
                streaming.onToken("lo");
                var turn = new SimpleConversationMemorySnapshot();
                turn.setConversationState(ConversationState.READY);
                turn.setConversationOutputs(List.of(output(taskIdOf(input), "Hello")));
                streaming.onComplete(turn);
                return null;
            }).when(conversationService).sayStreaming(any(), anyString(), anyString(), any(), any(), any(), any(), any());
            List<StreamEvent> events = new ArrayList<>();

            handler.stream(AGENT, send("Hi"), events::add);

            assertInstanceOf(TaskEvent.class, events.get(0));
            assertEquals(TaskState.working, ((TaskEvent) events.get(0)).task().state());
            assertFalse(((ChunkEvent) events.get(1)).append());
            assertTrue(((ChunkEvent) events.get(2)).append());
            var last = assertInstanceOf(FinalEvent.class, events.get(3));
            assertEquals(TaskState.completed, last.task().state());
            assertEquals("Hello", last.task().artifacts().getFirst().parts().getFirst().text());
            assertEquals(0, limiter.inFlight());
        }

        @Test
        @DisplayName("a streaming error settles the task as failed")
        void errorFails() throws Exception {
            doAnswer(invocation -> {
                StreamingResponseHandler streaming = invocation.getArgument(7);
                streaming.onError(new RuntimeException("boom"));
                return null;
            }).when(conversationService).sayStreaming(any(), anyString(), anyString(), any(), any(), any(), any(), any());
            List<StreamEvent> events = new ArrayList<>();

            handler.stream(AGENT, send("Hi"), events::add);

            assertEquals(TaskState.failed, ((FinalEvent) events.getLast()).task().state());
        }
    }

    // ==================== peers ====================

    @Nested
    @DisplayName("peer isolation and ownership")
    class Peers {

        @Test
        @DisplayName("a conversation is owned by the calling peer's principal")
        void ownedByPeer() throws Exception {
            sayCompletes(ConversationState.READY, "ok");

            handler.send(AGENT, send("Hi"));

            verify(conversationService).startConversation(eq(Environment.production), eq(AGENT), eq(PEER_A), anyMap());
        }

        @Test
        @DisplayName("an anonymous peer still gets a non-null owner no OIDC principal can equal")
        void anonymousPeer() throws Exception {
            sayCompletes(ConversationState.READY, "ok");
            SecurityIdentity identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(true);
            handler = new A2ATaskHandler(conversationService, taskStore, identity, agentCardService, limiter, meterRegistry, 60,
                    Optional.empty());

            handler.send(AGENT, send("Hi"));

            verify(conversationService).startConversation(eq(Environment.production), eq(AGENT), eq(A2ATaskHandler.ANONYMOUS_PEER), anyMap());
        }

        @Test
        @DisplayName("peer B can neither read nor cancel peer A's task — not even by re-deriving it")
        void peerBCannotReachPeerATask() throws Exception {
            sayCompletes(ConversationState.READY, "secret answer");
            A2ATask task = handler.send(AGENT, send("Hi"));
            A2ATaskHandler peerB = handlerFor(PEER_B);

            assertNull(peerB.get(task.id(), null));
            assertEquals(CancelResult.NOT_FOUND, peerB.cancel(task.id()).result());
        }

        @Test
        @DisplayName("peer B cannot join peer A's conversation by reusing its context id")
        void peerBCannotReuseContext() throws Exception {
            sayCompletes(ConversationState.READY, "ok");
            A2ATask task = handler.send(AGENT, send("Hi"));

            handlerFor(PEER_B).send(AGENT, sendInContext("Hi", task.contextId()));

            verify(conversationService).startConversation(eq(Environment.production), eq(AGENT), eq(PEER_B), anyMap());
        }

        @Test
        @DisplayName("scoped keys stay distinct when ids contain the separator")
        void scopedKeyIsInjective() {
            assertNotEquals(CachedA2ATaskStore.scopedKey("a|b", "c"), CachedA2ATaskStore.scopedKey("a", "b|c"));
        }
    }

    // ==================== small pieces ====================

    @Test
    void conversationIdOfServerIssuedTaskIds() {
        assertEquals("conv1", A2ATaskHandler.conversationIdOf("conv1_0123456789abcdef"));
        assertNull(A2ATaskHandler.conversationIdOf("legacy-task"));
        assertNull(A2ATaskHandler.conversationIdOf("_x"));
        assertNull(A2ATaskHandler.conversationIdOf(null));
    }

    @Test
    void outcomeOf_aNullSnapshotIsAnEmptyCompletion() {
        var outcome = A2ATaskHandler.outcomeOf(null, "t");
        assertEquals(TaskState.completed, outcome.state());
        assertEquals("", outcome.responseText());
    }

    @Test
    void toTask_onlyACompletedTaskCarriesAnArtifact() {
        var failed = new A2ATaskRecord("t", "c", "conv", AGENT, TaskState.failed, "why", "ignored", "hi", Instant.now());
        assertNull(A2ATaskHandler.toTask(failed, null).artifacts());
        var done = new A2ATaskRecord("t", "c", "conv", AGENT, TaskState.completed, null, "answer", "hi", Instant.now());
        assertEquals("answer", A2ATaskHandler.toTask(done, null).artifacts().getFirst().parts().getFirst().text());
    }

    // ==================== turn timeout ====================

    /**
     * The turn budget was a hard-coded 60 seconds, so an agent with a tool loop or
     * a model cascade timed out on the A2A surface only. The REST surface has
     * always used the operator's own {@code systemRuntime.agentTimeoutInSeconds},
     * so inheriting it here is the smaller of the two possible defaults.
     */
    @Nested
    @DisplayName("turn timeout")
    class TaskTimeoutTests {

        @Test
        void inheritsTheAgentTimeout() {
            assertEquals(600, handlerFor(PEER_A, 600, Optional.empty()).taskTimeoutSeconds());
        }

        @Test
        void theOverrideWins() {
            assertEquals(45, handlerFor(PEER_A, 600, Optional.of(45)).taskTimeoutSeconds());
        }

        @Test
        void nonPositiveOverrideFallsBack() {
            assertEquals(120, handlerFor(PEER_A, 120, Optional.of(0)).taskTimeoutSeconds());
            assertEquals(120, handlerFor(PEER_A, 120, Optional.of(-5)).taskTimeoutSeconds());
        }

        @Test
        void nonPositiveInheritedTimeoutFallsBack() {
            for (int bad : new int[]{0, -1, Integer.MIN_VALUE}) {
                assertEquals(A2ATaskHandler.DEFAULT_TASK_TIMEOUT_SECONDS, handlerFor(PEER_A, bad, Optional.empty()).taskTimeoutSeconds());
            }
        }

        @Test
        void overrideWinsOverABrokenInheritedValue() {
            assertEquals(45, handlerFor(PEER_A, 0, Optional.of(45)).taskTimeoutSeconds());
        }
    }

    // ─── Test helper: simple ConcurrentHashMap-based ICache ─────

    private static class MapCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {

        @Override
        public String getCacheName() {
            return "test-cache";
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit unit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
            return putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
            putAll(map);
        }

        @Override
        public V replace(K key, V value, long lifespan, TimeUnit unit) {
            return replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
            return replace(key, oldValue, value);
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return putIfAbsent(key, value);
        }
    }
}
