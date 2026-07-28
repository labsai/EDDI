/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentEngineStreaming}. Tests the SSE event mapping,
 * JSON serialization helpers, and error handling.
 */
class RestAgentEngineStreamingTest {

    private IConversationService conversationService;
    private ConversationAccessGuard conversationAccessGuard;
    private RestAgentEngineStreaming streaming;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        conversationAccessGuard = mock(ConversationAccessGuard.class);
        streaming = new RestAgentEngineStreaming(conversationService, conversationAccessGuard);
    }

    @Nested
    @DisplayName("LogSanitizer.sanitize (centralized utility)")
    class SanitizeForLog {

        @Test
        @DisplayName("should replace newlines, carriage returns, and tabs")
        void replacesControlChars() {
            assertEquals("hello_world", ai.labs.eddi.utils.LogSanitizer.sanitize("hello\nworld"));
            assertEquals("hello_world", ai.labs.eddi.utils.LogSanitizer.sanitize("hello\rworld"));
            assertEquals("hello_world", ai.labs.eddi.utils.LogSanitizer.sanitize("hello\tworld"));
            assertEquals("null", ai.labs.eddi.utils.LogSanitizer.sanitize(null));
        }
    }

    @Nested
    @DisplayName("escapeJson (private)")
    class EscapeJson {

        @Test
        @DisplayName("should escape special characters")
        void escapesSpecialChars() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);

            assertEquals("", method.invoke(streaming, (String) null));
            assertEquals("hello", method.invoke(streaming, "hello"));
            assertEquals("say \\\"hi\\\"", method.invoke(streaming, "say \"hi\""));
            assertEquals("line1\\nline2", method.invoke(streaming, "line1\nline2"));
            assertEquals("col1\\tcol2", method.invoke(streaming, "col1\tcol2"));
            assertEquals("path\\\\file", method.invoke(streaming, "path\\file"));
        }
    }

    @Nested
    @DisplayName("toJsonArray (private)")
    class ToJsonArray {

        @Test
        @DisplayName("should serialize list to JSON array")
        void serializesList() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJsonArray", Object.class);
            method.setAccessible(true);

            assertEquals("[\"a\",\"b\",\"c\"]", method.invoke(streaming, List.of("a", "b", "c")));
            assertEquals("[]", method.invoke(streaming, List.of()));
        }

        @Test
        @DisplayName("should return [] for non-list input")
        void nonList() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJsonArray", Object.class);
            method.setAccessible(true);

            assertEquals("[]", method.invoke(streaming, "not-a-list"));
        }
    }

    @Nested
    @DisplayName("toJson snapshot (private)")
    class ToJsonSnapshot {

        @Test
        @DisplayName("should serialize snapshot with conversation state")
        void serializesSnapshot() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ai.labs.eddi.engine.memory.model.ConversationState.READY);

            String json = (String) method.invoke(streaming, snapshot);

            assertTrue(json.contains("READY"));
        }

        @Test
        @DisplayName("should include conversationOutputs when present")
        void includesOutputs() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("toJson", SimpleConversationMemorySnapshot.class);
            method.setAccessible(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ai.labs.eddi.engine.memory.model.ConversationState.READY);
            var output = new ai.labs.eddi.engine.memory.model.ConversationOutput();
            output.put("output", List.of("Hello!"));
            snapshot.setConversationOutputs(List.of(output));

            String json = (String) method.invoke(streaming, snapshot);

            assertTrue(json.contains("conversationOutputs"));
        }
    }

    @Nested
    @DisplayName("sayStreaming")
    class SayStreaming {

        @Test
        @DisplayName("should delegate to conversationService and set up handler")
        void delegatesToService() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            verify(conversationService).sayStreaming(eq("conv-1"), eq(false), eq(false), eq(List.of()),
                    eq(inputData), any(IConversationService.StreamingResponseHandler.class));
        }

        @Test
        @DisplayName("should send error event when service throws")
        void sendsErrorOnException() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);

            doThrow(new RuntimeException("Service failed"))
                    .when(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), any());

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            // Should have sent an error event
            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
            verify(eventBuilder, atLeastOnce()).name("error");
        }

        @Test
        @DisplayName("cascade handler methods emit cascade_step_start and cascade_escalation SSE events")
        void forwardsCascadeEvents() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            var inputData = new InputData();
            inputData.setInput("Hi");

            when(eventSink.isClosed()).thenReturn(false);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            // Capture the handler the streaming endpoint wired up, then drive its cascade
            // callbacks.
            var cap = org.mockito.ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
            verify(conversationService).sayStreaming(eq("conv-1"), any(), any(), any(), any(), cap.capture());
            var handler = cap.getValue();

            handler.onCascadeStepStart(0, "openai", "gpt-4o-mini", 2);
            verify(eventBuilder).name("cascade_step_start");
            verify(eventSink).send(sseEvent);

            handler.onCascadeEscalation(0, 1, 0.4, 0.7, "low_confidence", 42L);
            verify(eventBuilder).name("cascade_escalation");
            verify(eventSink, times(2)).send(sseEvent);
        }

        @Test
        @DisplayName("A1: a caller who does not own the conversation is denied (403) before the turn runs")
        void deniesForeignConversation() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            // user B posting into user A's conversation
            doThrow(new ForbiddenException("Access denied: you do not own this conversation"))
                    .when(conversationAccessGuard).requireConversationOwner("conv-of-user-a");

            assertThrows(ForbiddenException.class,
                    () -> streaming.sayStreaming("conv-of-user-a", false, false, List.of(), inputData, eventSink, sse));

            // The turn must not have been started, and the denial must NOT be
            // downgraded into an SSE 'error' event on an otherwise 200 stream.
            verify(conversationService, never()).sayStreaming(anyString(), any(), any(), any(), any(), any());
            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("A1: the owner check runs on every streaming turn")
        void checksOwnershipOnEveryTurn() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();
            inputData.setInput("Hello");

            when(eventSink.isClosed()).thenReturn(false);

            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);

            verify(conversationAccessGuard).requireConversationOwner("conv-1");
        }

        @Test
        @DisplayName("should handle closed sink gracefully")
        void handleClosedSink() throws Exception {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var inputData = new InputData();

            when(eventSink.isClosed()).thenReturn(true);

            doThrow(new RuntimeException("Service failed"))
                    .when(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), any());

            // Should not throw
            streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);
        }
    }

    /**
     * F6 — the streaming endpoint receives only {@link SseEventSink}/{@link Sse}
     * and never signalled cancellation, so closing the tab at token 5 of 4000 still
     * streamed (and billed) the whole completion, possibly escalating through the
     * whole model cascade.
     * <p>
     * A JAX-RS {@code ConnectionCallback} is not the mechanism here: RESTEasy
     * Reactive registers connection callbacks into a request property that nothing
     * ever reads, so one would cancel nothing. The observable signal is
     * {@code SseEventSink.isClosed()} (backed by
     * {@code serverResponse().closed()}), which is why the disconnect is detected
     * on the next outbound frame — i.e. within one token boundary.
     */
    @Nested
    @DisplayName("F6 — client disconnect cancels the in-flight turn")
    class ClientDisconnect {

        private SseEventSink eventSink;

        /** Starts a stream and returns the handler the endpoint wired up. */
        private IConversationService.StreamingResponseHandler start(String conversationId) throws Exception {
            eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var eventBuilder = mock(OutboundSseEvent.Builder.class);
            var sseEvent = mock(OutboundSseEvent.class);
            when(sse.newEventBuilder()).thenReturn(eventBuilder);
            when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
            when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
            when(eventBuilder.build()).thenReturn(sseEvent);
            when(eventSink.isClosed()).thenReturn(false);

            var inputData = new InputData();
            inputData.setInput("Hello");
            streaming.sayStreaming(conversationId, false, false, List.of(), inputData, eventSink, sse);

            var captor = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
            verify(conversationService).sayStreaming(eq(conversationId), any(), any(), any(), any(), captor.capture());
            return captor.getValue();
        }

        private SimpleConversationMemorySnapshot readySnapshot() {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            return snapshot;
        }

        @Test
        @DisplayName("a token emitted after the client vanished cancels the turn, exactly once")
        void tokenAfterDisconnectCancelsOnce() throws Exception {
            var handler = start("conv-1");

            // The client closed the tab: Vert.x closes the response, so the sink
            // reports closed from the next frame onwards.
            when(eventSink.isClosed()).thenReturn(true);

            handler.onToken("tok-5");
            handler.onToken("tok-6");
            handler.onTaskComplete(new TaskId("ai.labs.llm"), "langchain", 5L, Map.of());

            verify(conversationService, times(1)).cancelConversation("conv-1",
                    ControlSignal.CANCEL_GRACEFUL, RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
            // …and nothing is written to a sink the client is no longer reading.
            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("a send that fails because the sink closed mid-write also cancels")
        void sendFailureOnClosedSinkCancels() throws Exception {
            var handler = start("conv-4");

            // The sink closes between the isClosed() pre-check and the write, which is
            // when RESTEasy Reactive throws IllegalStateException("Already closed").
            when(eventSink.isClosed()).thenReturn(false, true);
            doThrow(new IllegalStateException("Already closed"))
                    .when(eventSink).send(any(OutboundSseEvent.class));

            handler.onToken("tok-5");

            verify(conversationService, times(1)).cancelConversation("conv-4",
                    ControlSignal.CANCEL_GRACEFUL, RestAgentEngineStreaming.CANCELLED_BY_CLIENT_DISCONNECT);
        }

        @Test
        @DisplayName("a send failure on a still-open sink is NOT a disconnect and does not cancel")
        void sendFailureOnOpenSinkDoesNotCancel() throws Exception {
            var handler = start("conv-5");

            // A payload/serialization fault, not a vanished client.
            doThrow(new RuntimeException("bad payload"))
                    .when(eventSink).send(any(OutboundSseEvent.class));

            handler.onToken("tok-5");

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a stream that completes normally is never cancelled")
        void normalCompletionDoesNotCancel() throws Exception {
            var handler = start("conv-2");

            handler.onToken("hi");
            handler.onComplete(readySnapshot());

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("a sink already closed when the terminal frame is emitted is not a disconnect")
        void closedAtTerminalFrameDoesNotCancel() throws Exception {
            var handler = start("conv-3");

            // Client went away right as the turn finished — the answer is already
            // produced, so there is nothing left to cancel.
            when(eventSink.isClosed()).thenReturn(true);
            handler.onComplete(readySnapshot());

            verify(conversationService, never()).cancelConversation(anyString(), any(), anyString());
        }
    }
}
