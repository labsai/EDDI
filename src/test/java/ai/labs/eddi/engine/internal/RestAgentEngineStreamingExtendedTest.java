package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RestAgentEngineStreaming} — exercises the anonymous
 * StreamingResponseHandler callbacks (onTaskStart, onTaskComplete, onToken,
 * onComplete, onError) to cover the $1 inner class.
 */
class RestAgentEngineStreamingExtendedTest {

    private IConversationService conversationService;
    private RestAgentEngineStreaming streaming;
    private SseEventSink eventSink;
    private Sse sse;
    private OutboundSseEvent.Builder eventBuilder;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        streaming = new RestAgentEngineStreaming(conversationService);
        eventSink = mock(SseEventSink.class);
        sse = mock(Sse.class);
        eventBuilder = mock(OutboundSseEvent.Builder.class);

        when(eventSink.isClosed()).thenReturn(false);
        when(sse.newEventBuilder()).thenReturn(eventBuilder);
        when(eventBuilder.name(anyString())).thenReturn(eventBuilder);
        when(eventBuilder.data(any(Class.class), anyString())).thenReturn(eventBuilder);
        when(eventBuilder.build()).thenReturn(mock(OutboundSseEvent.class));
    }

    /**
     * Captures the StreamingResponseHandler from the conversationService call.
     */
    private IConversationService.StreamingResponseHandler captureHandler() throws Exception {
        var captor = ArgumentCaptor.forClass(IConversationService.StreamingResponseHandler.class);
        verify(conversationService).sayStreaming(anyString(), any(), any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private void invokeSayStreaming() {
        var inputData = new InputData();
        inputData.setInput("Hello");
        streaming.sayStreaming("conv-1", false, false, List.of(), inputData, eventSink, sse);
    }

    @Nested
    @DisplayName("StreamingResponseHandler callbacks")
    class HandlerCallbacks {

        @Test
        @DisplayName("onTaskStart sends task_start SSE event")
        void onTaskStartSendsEvent() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onTaskStart("task-1", "LlmTask", 0);

            verify(eventBuilder).name("task_start");
            verify(eventBuilder).data(eq(String.class), contains("task-1"));
            verify(eventSink).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("onTaskComplete sends task_complete SSE event with basic fields")
        void onTaskCompleteSendsBasicEvent() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onTaskComplete("task-1", "LlmTask", 150L, Map.of());

            verify(eventBuilder).name("task_complete");
            verify(eventBuilder).data(eq(String.class), contains("task-1"));
        }

        @Test
        @DisplayName("onTaskComplete includes actions in event")
        void onTaskCompleteIncludesActions() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onTaskComplete("task-1", "RulesEvaluation", 50L,
                    Map.of("actions", List.of("greet", "respond")));

            var dataCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder).data(eq(String.class), dataCaptor.capture());
            String data = dataCaptor.getValue();
            assertTrue(data.contains("actions"));
            assertTrue(data.contains("greet"));
        }

        @Test
        @DisplayName("onTaskComplete includes toolTrace in event")
        void onTaskCompleteIncludesToolTrace() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onTaskComplete("task-1", "LlmTask", 200L,
                    Map.of("toolTrace", List.of(Map.of("tool", "weather", "duration", 100))));

            var dataCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder).data(eq(String.class), dataCaptor.capture());
            String data = dataCaptor.getValue();
            assertTrue(data.contains("toolTrace"));
        }

        @Test
        @DisplayName("onTaskComplete includes confidence in event")
        void onTaskCompleteIncludesConfidence() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onTaskComplete("task-1", "LlmTask", 100L,
                    Map.of("confidence", 0.95));

            var dataCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder).data(eq(String.class), dataCaptor.capture());
            String data = dataCaptor.getValue();
            assertTrue(data.contains("confidence"));
            assertTrue(data.contains("0.95"));
        }

        @Test
        @DisplayName("onToken sends token SSE event")
        void onTokenSendsEvent() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onToken("Hello");

            verify(eventBuilder).name("token");
            verify(eventBuilder).data(eq(String.class), eq("Hello"));
        }

        @Test
        @DisplayName("onComplete sends done event and closes sink")
        void onCompleteSendsAndCloses() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);

            handler.onComplete(snapshot);

            verify(eventBuilder).name("done");
            verify(eventSink).close();
        }

        @Test
        @DisplayName("onComplete sends done event with conversation outputs")
        void onCompleteWithOutputs() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            var output = new ConversationOutput();
            output.put("output", List.of("Hello there!"));
            snapshot.setConversationOutputs(List.of(output));

            handler.onComplete(snapshot);

            var dataCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder).data(eq(String.class), dataCaptor.capture());
            assertTrue(dataCaptor.getValue().contains("conversationOutputs"));
        }

        @Test
        @DisplayName("onError sends error event and closes sink")
        void onErrorSendsAndCloses() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onError(new RuntimeException("Something broke"));

            verify(eventBuilder).name("error");
            verify(eventSink).close();
        }

        @Test
        @DisplayName("onError escapes special characters in error message")
        void onErrorEscapesMessage() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            handler.onError(new RuntimeException("Error with \"quotes\" and\nnewlines"));

            var dataCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBuilder).data(eq(String.class), dataCaptor.capture());
            String data = dataCaptor.getValue();
            assertFalse(data.contains("\n")); // newline should be escaped
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("onComplete closes already-closed sink gracefully")
        void onCompleteAlreadyClosed() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            when(eventSink.isClosed()).thenReturn(false).thenReturn(true);

            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.ENDED);

            // Should not throw
            assertDoesNotThrow(() -> handler.onComplete(snapshot));
        }

        @Test
        @DisplayName("sendEvent handles exception in eventBuilder gracefully")
        void sendEventHandlesBuilderException() throws Exception {
            when(eventBuilder.build()).thenThrow(new RuntimeException("Builder error"));

            invokeSayStreaming();
            var handler = captureHandler();

            // Should not throw — logged as warning
            assertDoesNotThrow(() -> handler.onToken("test"));
        }

        @Test
        @DisplayName("onTaskComplete handles toolTrace serialization failure")
        void toolTraceSerializationFailure() throws Exception {
            invokeSayStreaming();
            var handler = captureHandler();

            // Circular reference would cause serialization failure
            Object circular = new Object() {
                @Override
                public String toString() {
                    throw new RuntimeException("Cannot serialize");
                }
            };

            // Should not throw — handled gracefully
            assertDoesNotThrow(() -> handler.onTaskComplete("t1", "LlmTask", 100L,
                    Map.of("toolTrace", circular)));
        }
    }
}
