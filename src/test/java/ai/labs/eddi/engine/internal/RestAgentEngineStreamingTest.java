/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAgentEngineStreaming}. Tests the SSE event mapping,
 * JSON serialization helpers, and error handling.
 */
class RestAgentEngineStreamingTest {

    private IConversationService conversationService;
    private RestAgentEngineStreaming streaming;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        streaming = new RestAgentEngineStreaming(conversationService);
    }

    @Nested
    @DisplayName("sanitizeForLog (private)")
    class SanitizeForLog {

        @Test
        @DisplayName("should replace newlines and carriage returns")
        void replacesNewlines() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("sanitizeForLog", String.class);
            method.setAccessible(true);

            assertEquals("hello_world", method.invoke(null, "hello\nworld"));
            assertEquals("hello_world", method.invoke(null, "hello\rworld"));
            assertEquals("null", method.invoke(null, (String) null));
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

    @Nested
    @DisplayName("sendEvent helper")
    class SendEvent {

        @Test
        @DisplayName("should skip sending when sink is closed")
        void skipsClosedSink() throws Exception {
            Method method = RestAgentEngineStreaming.class.getDeclaredMethod("sendEvent",
                    SseEventSink.class, Sse.class, String.class, String.class);
            method.setAccessible(true);

            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            when(eventSink.isClosed()).thenReturn(true);

            method.invoke(streaming, eventSink, sse, "test", "data");

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }
    }
}
