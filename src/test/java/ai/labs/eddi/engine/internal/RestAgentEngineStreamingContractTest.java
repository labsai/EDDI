/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @DisplayName("B8: the agent's streamingClientDisconnect overrides the deployment default")
    void perAgentDisconnectPolicy() throws Exception {
        var agentStore = mock(IAgentStore.class);
        streaming.agentStore = agentStore;
        var stored = new ConversationMemorySnapshot();
        stored.setAgentId("agent-1");
        stored.setAgentVersion(2);
        when(conversationService.getConversationMemoryTail("conv-1")).thenReturn(ConversationMemoryTail.ofFullSnapshot(stored));
        var agent = new AgentConfiguration();
        agent.setStreamingClientDisconnect(AgentConfiguration.STREAMING_DISCONNECT_FINISH);
        when(agentStore.read("agent-1", 2)).thenReturn(agent);

        var handler = start();
        handler.onToken("a");
        sink.close();
        handler.onToken("b");

        verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        assertFalse(streaming.cancelOnDisconnectFor("conv-1", true), "finish lets the turn run and persist");
        agent.setStreamingClientDisconnect(null);
        assertTrue(streaming.cancelOnDisconnectFor("conv-1", true), "unset follows the deployment default");
        assertFalse(streaming.cancelOnDisconnectFor("conv-1", false));
    }

    @Test
    @DisplayName("an unknown or unreadable policy falls back to the deployment default")
    void policyFallback() throws Exception {
        assertTrue(RestAgentEngineStreaming.resolveCancelOnDisconnect("CANCEL", false));
        assertFalse(RestAgentEngineStreaming.resolveCancelOnDisconnect(" finish ", true));
        assertTrue(RestAgentEngineStreaming.resolveCancelOnDisconnect("sometimes", true));
        assertFalse(RestAgentEngineStreaming.resolveCancelOnDisconnect(null, false));
        // No agent store (unit construction) or a failing read: the default.
        assertTrue(streaming.cancelOnDisconnectFor("conv-1", true));
        streaming.agentStore = mock(IAgentStore.class);
        when(conversationService.getConversationMemoryTail("conv-1")).thenThrow(new IllegalStateException("store down"));
        assertTrue(streaming.cancelOnDisconnectFor("conv-1", true));
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
}
