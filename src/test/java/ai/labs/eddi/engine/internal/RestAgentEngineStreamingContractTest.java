/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The events a client of {@code POST /agents/{id}/stream} receives under the
 * streaming contract, on the shared transport: {@code done.finishReason}, the
 * {@code cancelled} event, typed and {@code retryable} errors, the per-agent
 * disconnect policy, the node's stream cap and the release of every stream.
 */
@DisplayName("RestAgentEngineStreaming — the streaming contract on the wire")
class RestAgentEngineStreamingContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IConversationService conversationService;
    private RestAgentEngineStreaming streaming;
    private FakeSse.Sink sink;
    private final FakeSse sse = new FakeSse();

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        streaming = new RestAgentEngineStreaming(conversationService, mock(ConversationAccessGuard.class), true, 0);
        streaming.sseStreams = SseStreams.unmetered();
        streaming.sseStreams.clientGoneExecutor = Runnable::run;
        sink = new FakeSse.Sink(false);
    }

    @AfterEach
    void stopPools() {
        // Every SseStreams a test builds owns a disconnect pool.
        streaming.sseStreams.shutdown();
    }

    private IConversationService.StreamingResponseHandler start() throws Exception {
        var input = new InputData();
        input.setInput("Hello");
        streaming.sayStreaming("conv-1", false, false, List.of(), input, sink, sse);
        var handler = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(eq("conv-1"), any(), any(), any(), any(), handler.capture());
        return handler.getValue();
    }

    private JsonNode lastData(String eventName) throws Exception {
        var events = sink.events.stream().filter(e -> eventName.equals(e.name())).toList();
        assertFalse(events.isEmpty(), "no " + eventName + " event in " + sink.events);
        return MAPPER.readTree(String.valueOf(events.getLast().data()).trim());
    }

    private static SimpleConversationMemorySnapshot snapshot(ConversationState state, String finishReason) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(state);
        var output = new ConversationOutput();
        output.put("output", List.of("answer"));
        if (finishReason != null) {
            output.put(MemoryKeys.TURN_FINISH_REASON, finishReason);
        }
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    @Test
    @DisplayName("B4: done carries finishReason when the answer was cut off")
    void doneCarriesFinishReason() throws Exception {
        start().onComplete(snapshot(ConversationState.READY, "length"));

        assertEquals("length", lastData("done").get("finishReason").asText());
    }

    @Test
    @DisplayName("a plain complete answer has no finishReason in done")
    void plainAnswerHasNoFinishReason() throws Exception {
        start().onComplete(snapshot(ConversationState.READY, null));

        assertNull(lastData("done").get("finishReason"));
    }

    @Test
    @DisplayName("B9: a cancelled turn ends with a cancelled event, and done does not present the discarded outputs")
    void cancelledTurn() throws Exception {
        var handler = start();
        handler.onToken("partial");
        handler.onCancelRequested();
        handler.onComplete(snapshot(ConversationState.EXECUTION_INTERRUPTED, null));

        var cancelled = lastData(RestAgentEngineStreaming.EVENT_CANCELLED);
        assertEquals("EXECUTION_INTERRUPTED", cancelled.get("conversationState").asText());
        assertFalse(cancelled.get("persisted").asBoolean());
        assertNull(lastData("done").get("conversationOutputs"), "a cancelled turn's outputs were never stored");
        var names = sink.events.stream().map(FakeSse.Event::name).toList();
        assertTrue(names.indexOf(RestAgentEngineStreaming.EVENT_CANCELLED) < names.indexOf("done"), names.toString());
    }

    @Test
    @DisplayName("every error event names a stable code and whether retrying makes sense")
    void errorEventsCarryCodeAndRetryable() throws Exception {
        var handler = start();
        handler.onError(new IllegalStateException("store exploded at mongodb://secret-host"));

        var error = lastData("error");
        assertEquals(RestAgentEngineStreaming.INTERNAL_ERROR_CODE, error.get("code").asText());
        assertFalse(error.get("retryable").asBoolean());
        assertFalse(error.toString().contains("secret-host"));
        assertEquals("true", streaming.typedError("x", "conversation_busy").replaceAll(".*\"retryable\":(\\w+).*", "$1"));
        assertEquals("false", streaming.typedError("x", "conversation_ended").replaceAll(".*\"retryable\":(\\w+).*", "$1"));
    }

    @Test
    @DisplayName("every terminal path releases the stream's slot")
    void terminalPathsReleaseTheStream() throws Exception {
        for (ConversationState state : List.of(ConversationState.READY, ConversationState.ERROR, ConversationState.EXECUTION_INTERRUPTED)) {
            setUp();
            start().onComplete(snapshot(state, null));
            assertEquals(0, streaming.sseStreams.totalActive(), state + " left the stream registered");
            assertTrue(sink.isClosed());
        }
        setUp();
        start().onError(new IllegalStateException("boom"));
        assertEquals(0, streaming.sseStreams.totalActive());

        // A client that vanished mid-turn: released at once, the turn cancelled once.
        setUp();
        var handler = start();
        handler.onToken("a");
        sink.close();
        handler.onToken("b");
        assertEquals(0, streaming.sseStreams.totalActive());
        verify(conversationService).cancelConversation("conv-1", ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    @DisplayName("a node at its stream cap refuses with 503 before touching the sink or starting the turn")
    void streamCapRefuses() throws Exception {
        streaming.sseStreams = new SseStreams(1, SseStreams.DEFAULT_MAX_PENDING_EVENTS, SseStreams.DEFAULT_MAX_PENDING_BYTES, null);
        streaming.sseStreams.open(SseStreams.Kind.GROUP, "busy", new FakeSse.Sink(false), sse, 0, 0, null, null);
        var input = new InputData();
        input.setInput("Hello");

        var refused = assertThrows(WebApplicationException.class,
                () -> streaming.sayStreaming("conv-1", false, false, List.of(), input, sink, sse));

        assertEquals(503, refused.getResponse().getStatus());
        assertTrue(sink.events.isEmpty());
        verify(conversationService, never()).sayStreaming(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("B8/S4: the agent's streamingClientDisconnect arrives with the turn and overrides the default — no store read")
    void perAgentDisconnectPolicy() throws Exception {
        var handler = start();
        handler.onTurnStarted(AgentConfiguration.STREAMING_DISCONNECT_FINISH);
        handler.onToken("a");
        sink.close();
        handler.onToken("b");

        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        verify(conversationService, never()).getConversationMemoryTail(anyString());
        assertEquals(0, streaming.sseStreams.totalActive());
    }

    @Test
    @DisplayName("an agent that sets no policy follows the deployment default (cancel)")
    void unsetPolicyFollowsTheDefault() throws Exception {
        var handler = start();
        handler.onTurnStarted(null);
        sink.close();
        handler.onToken("b");

        verify(conversationService).cancelConversation("conv-1", ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
        verify(conversationService, never()).getConversationMemoryTail(anyString());
    }

    @Test
    @DisplayName("an unknown policy falls back to the deployment default")
    void policyFallback() {
        assertTrue(RestAgentEngineStreaming.resolveCancelOnDisconnect("CANCEL", false));
        assertFalse(RestAgentEngineStreaming.resolveCancelOnDisconnect(" finish ", true));
        assertTrue(RestAgentEngineStreaming.resolveCancelOnDisconnect("sometimes", true));
        assertFalse(RestAgentEngineStreaming.resolveCancelOnDisconnect(null, false));
    }

    @Test
    @DisplayName("composition: a client that leaves after generation finished does not cancel or discard the turn")
    void disconnectAfterGenerationFinishedKeepsTheTurn() throws Exception {
        var handler = start();
        handler.onTurnStarted(AgentConfiguration.STREAMING_DISCONNECT_CANCEL);
        handler.onToken("answer");
        handler.onGenerationFinished();
        sink.close();
        handler.onTaskComplete(new TaskId("t"), "llm", 1, Map.of());

        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        assertEquals(0, streaming.sseStreams.totalActive(), "the vanished client's stream is still released");
    }

    @Test
    @DisplayName("S5: an interrupted turn nobody cancelled ends with a retryable turn_timeout error, never 'cancelled'")
    void interruptedWithoutCancelIsATimeout() throws Exception {
        var handler = start();
        handler.onToken("partial");
        handler.onComplete(snapshot(ConversationState.EXECUTION_INTERRUPTED, null));

        var names = sink.events.stream().map(FakeSse.Event::name).toList();
        assertFalse(names.contains(RestAgentEngineStreaming.EVENT_CANCELLED), names.toString());
        assertFalse(names.contains("done"), "the turn was discarded: there is no outcome to present");
        var error = lastData("error");
        assertEquals(RestAgentEngineStreaming.TURN_TIMEOUT_CODE, error.get("code").asText());
        assertTrue(error.get("retryable").asBoolean());
        assertTrue(sink.isClosed());
        assertEquals(0, streaming.sseStreams.totalActive());
    }

    @Test
    @DisplayName("S7: the stream slot is taken before the access check, which then reads nothing when the node is full")
    void slotBeforeAccessCheck() throws Exception {
        var accessGuard = mock(ConversationAccessGuard.class);
        streaming = new RestAgentEngineStreaming(conversationService, accessGuard, true, 0);
        streaming.sseStreams = new SseStreams(1, SseStreams.DEFAULT_MAX_PENDING_EVENTS, SseStreams.DEFAULT_MAX_PENDING_BYTES, null);
        streaming.sseStreams.open(SseStreams.Kind.GROUP, "busy", new FakeSse.Sink(false), sse, 0, 0, null, null);
        var input = new InputData();
        input.setInput("Hello");

        assertThrows(WebApplicationException.class, () -> streaming.sayStreaming("conv-1", false, false, List.of(), input, sink, sse));

        verify(accessGuard, never()).requireConversationTurnAccess(anyString());
    }

    @Test
    @DisplayName("S7: a turn the access check refuses gives its slot back and writes nothing")
    void refusedTurnReleasesItsSlot() {
        var accessGuard = mock(ConversationAccessGuard.class);
        doThrow(new ForbiddenException()).when(accessGuard).requireConversationTurnAccess("conv-1");
        streaming = new RestAgentEngineStreaming(conversationService, accessGuard, true, 0);
        streaming.sseStreams = SseStreams.unmetered();
        var input = new InputData();
        input.setInput("Hello");

        assertThrows(ForbiddenException.class,
                () -> streaming.sayStreaming("conv-1", false, false, List.of(), input, sink, sse));

        assertEquals(0, streaming.sseStreams.totalActive());
        assertTrue(sink.events.isEmpty());
    }

    @Test
    @DisplayName("S9: an Error thrown while starting the turn still ends the stream and frees its slot")
    void errorOnStartReleases() throws Exception {
        doThrow(new StackOverflowError()).when(conversationService)
                .sayStreaming(anyString(), any(), any(), any(), any(), any());
        var input = new InputData();
        input.setInput("Hello");

        streaming.sayStreaming("conv-1", false, false, List.of(), input, sink, sse);

        assertEquals(RestAgentEngineStreaming.INTERNAL_ERROR_CODE, lastData("error").get("code").asText());
        assertTrue(sink.isClosed());
        assertEquals(0, streaming.sseStreams.totalActive());
    }

    @Test
    @DisplayName("S4: thousands of clients vanishing at once cancel through a bounded pool, without a store read each")
    void massDisconnectIsBounded() throws Exception {
        int streams = 2_000;
        int concurrency = 4;
        streaming.sseStreams = new SseStreams(SseStreams.DEFAULT_MAX_CONCURRENT, SseStreams.DEFAULT_MAX_PENDING_EVENTS,
                SseStreams.DEFAULT_MAX_PENDING_BYTES, SseStreams.DEFAULT_MAX_PENDING_BYTES_TOTAL, concurrency, null);
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var cancels = new CountDownLatch(streams);
        // The store is slow: every queued cancel waits until the gate opens.
        var storeGate = new CountDownLatch(1);
        doAnswer(invocation -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            storeGate.await(60, TimeUnit.SECONDS);
            running.decrementAndGet();
            cancels.countDown();
            return null;
        }).when(conversationService).cancelConversation(anyString(), any(), anyString());
        // The in-memory part, as ConversationService does it: the live turn's flag.
        Map<String, ConversationMemory> live = new ConcurrentHashMap<>();
        for (int i = 0; i < streams; i++) {
            live.put("conv-" + i, new ConversationMemory("conv-" + i, 0));
        }
        doAnswer(invocation -> {
            live.get(invocation.<String>getArgument(0)).setCancelled(true);
            return true;
        }).when(conversationService).signalLocalInFlight(anyString());

        var handlers = new ArrayList<IConversationService.StreamingResponseHandler>();
        var sinks = new ArrayList<FakeSse.Sink>();
        for (int i = 0; i < streams; i++) {
            var each = new FakeSse.Sink(false);
            var input = new InputData();
            input.setInput("Hello");
            streaming.sayStreaming("conv-" + i, false, false, List.of(), input, each, sse);
            sinks.add(each);
        }
        var captor = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
        verify(conversationService, times(streams)).sayStreaming(anyString(), any(), any(), any(), any(),
                captor.capture());
        handlers.addAll(captor.getAllValues());
        for (int i = 0; i < streams; i++) {
            handlers.get(i).onTurnStarted(null);
            sinks.get(i).close();
            handlers.get(i).onToken("x");
        }

        // S4: while the store work still queues, every turn has already stopped.
        assertTrue(live.values().stream().allMatch(ConversationMemory::isCancelled),
                "each turn's cancel flag is up before its store work runs");
        assertTrue(cancels.getCount() > 0, "the store work is still queued behind the bounded pool");
        storeGate.countDown();

        assertTrue(cancels.await(60, TimeUnit.SECONDS), "every turn is cancelled");
        assertTrue(peak.get() <= concurrency, "at most " + concurrency + " cancels ran together, saw " + peak.get());
        assertEquals(0, streaming.sseStreams.totalActive());
        verify(conversationService, never()).getConversationMemoryTail(anyString());
        streaming.sseStreams.shutdown();
    }

    @Test
    @DisplayName("S1: a terminal claimed by the shutdown is not followed by the turn's own end")
    void oneTerminalEventPerStream() throws Exception {
        var handler = start();
        handler.onToken("partial");
        streaming.sseStreams.shutdown();
        handler.onComplete(snapshot(ConversationState.READY, null));
        handler.onError(new IllegalStateException("late"));

        var names = sink.events.stream().map(FakeSse.Event::name).toList();
        assertEquals(1, names.stream().filter(n -> n.equals("error") || n.equals("done")).count(), names.toString());
        assertEquals(SseStreams.SERVER_SHUTDOWN_CODE, lastData("error").get("code").asText());
        assertFalse(lastData("error").get("retryable").asBoolean(), "the turn may still be stored");
        assertEquals(0, streaming.sseStreams.totalActive());
    }

    @Test
    @DisplayName("S3: a turn stopped by a lost cluster lease ends with a typed, retryable error")
    void leaseLossIsTypedAndRetryable() throws Exception {
        var handler = start();
        handler.onToken("partial");
        handler.onError(new ConversationStepRunner.TurnLeaseLostException("conv-1", 7L));

        var error = lastData("error");
        assertEquals(RestAgentEngineStreaming.TURN_TIMEOUT_CODE, error.get("code").asText());
        assertTrue(error.get("retryable").asBoolean(), "the turn was discarded, never stored");
        assertNull(error.get("correlationId"), "not an internal error");
        assertEquals(0, streaming.sseStreams.totalActive());
    }

    @Test
    @DisplayName("S4: a disconnect raises the turn's cancel flag on the noticing thread, before the queued store work")
    void disconnectRaisesTheFlagAtOnce() throws Exception {
        var queued = new ArrayList<Runnable>();
        streaming.sseStreams.clientGoneExecutor = queued::add;
        var handler = start();
        handler.onTurnStarted(null);
        sink.close();
        handler.onToken("x");

        verify(conversationService).signalLocalInFlight("conv-1");
        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        queued.forEach(Runnable::run);
        verify(conversationService).cancelConversation("conv-1", ControlSignal.CANCEL_GRACEFUL,
                RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
    }

    @Test
    @DisplayName("finishReasonOf reads the last output only")
    void finishReasonOf() {
        var older = new ConversationOutput();
        older.put(MemoryKeys.TURN_FINISH_REASON, "length");
        var latest = new ConversationOutput();
        latest.put("output", Map.of());
        assertNull(RestAgentEngineStreaming.finishReasonOf(List.of(older, latest)));
        assertEquals("length", RestAgentEngineStreaming.finishReasonOf(List.of(latest, older)));
        assertNull(RestAgentEngineStreaming.finishReasonOf(null));
    }

    @Test
    @DisplayName("S10: 'empty' is reported only when the whole turn shows nothing")
    void emptyOnlyForTheWholeTurn() {
        var silent = new ConversationOutput();
        silent.put(MemoryKeys.TURN_FINISH_REASON, MemoryKeys.TURN_FINISH_REASON_EMPTY);
        silent.put("output", List.of());
        assertEquals(MemoryKeys.TURN_FINISH_REASON_EMPTY, RestAgentEngineStreaming.finishReasonOf(List.of(silent)));

        var answeredByAnotherTask = new ConversationOutput();
        answeredByAnotherTask.put(MemoryKeys.TURN_FINISH_REASON, MemoryKeys.TURN_FINISH_REASON_EMPTY);
        answeredByAnotherTask.put("output", List.of("Hello from the output set"));
        assertNull(RestAgentEngineStreaming.finishReasonOf(List.of(answeredByAnotherTask)));

        var quickRepliesOnly = new ConversationOutput();
        quickRepliesOnly.put(MemoryKeys.TURN_FINISH_REASON, MemoryKeys.TURN_FINISH_REASON_EMPTY);
        quickRepliesOnly.put("quickReplies", List.of(Map.of("value", "yes")));
        assertNull(RestAgentEngineStreaming.finishReasonOf(List.of(quickRepliesOnly)));
    }
}
