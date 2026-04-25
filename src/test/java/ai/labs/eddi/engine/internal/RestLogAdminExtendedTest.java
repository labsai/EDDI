package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.runtime.InstanceIdProducer;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RestLogAdmin} — covers the live listener callback,
 * filter logic, and sendEvent error handling.
 */
class RestLogAdminExtendedTest {

    private BoundedLogStore boundedLogStore;
    private IDatabaseLogs databaseLogs;
    private InstanceIdProducer instanceIdProducer;
    private RestLogAdmin restLogAdmin;

    @BeforeEach
    void setUp() {
        boundedLogStore = mock(BoundedLogStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        instanceIdProducer = mock(InstanceIdProducer.class);
        restLogAdmin = new RestLogAdmin(boundedLogStore, databaseLogs, instanceIdProducer);
    }

    // ─── Live listener filter logic ─────────────────────────

    @Nested
    @DisplayName("streamLogs listener callback")
    class ListenerCallback {

        private SseEventSink eventSink;
        private Sse sse;
        @SuppressWarnings("unchecked")
        private Consumer<LogEntry> capturedListener;

        @BeforeEach
        void setUp() {
            eventSink = mock(SseEventSink.class);
            sse = mock(Sse.class, RETURNS_DEEP_STUBS);
            var event = mock(OutboundSseEvent.class);
            when(sse.newEventBuilder().name(anyString()).data(any()).build()).thenReturn(event);
            when(eventSink.send(any(OutboundSseEvent.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(eventSink.isClosed()).thenReturn(false);
            when(boundedLogStore.getEntries(any(), any(), any(), anyInt())).thenReturn(List.of());
            when(boundedLogStore.addListener(any())).thenReturn("listener-1");

            restLogAdmin.streamLogs("agent-1", "conv-1", "WARN", eventSink, sse);

            var captor = ArgumentCaptor.forClass(Consumer.class);
            verify(boundedLogStore).addListener(captor.capture());
            capturedListener = captor.getValue();
        }

        @Test
        @DisplayName("should skip event when sink is closed")
        void skipsWhenClosed() {
            when(eventSink.isClosed()).thenReturn(true);

            var entry = logEntry("agent-1", "conv-1", "WARN");
            capturedListener.accept(entry);

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("should skip event when agentId doesn't match")
        void filtersAgentId() {
            var entry = logEntry("other-agent", "conv-1", "WARN");
            capturedListener.accept(entry);

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("should skip event when conversationId doesn't match")
        void filtersConversationId() {
            var entry = logEntry("agent-1", "other-conv", "WARN");
            capturedListener.accept(entry);

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("should skip event when level doesn't meet minimum")
        void filtersLevel() {
            when(boundedLogStore.meetsMinimumLevel("DEBUG", "WARN")).thenReturn(false);

            var entry = logEntry("agent-1", "conv-1", "DEBUG");
            capturedListener.accept(entry);

            verify(eventSink, never()).send(any(OutboundSseEvent.class));
        }

        @Test
        @DisplayName("should send event when all filters pass")
        void sendsWhenFiltersPass() {
            when(boundedLogStore.meetsMinimumLevel("ERROR", "WARN")).thenReturn(true);

            var entry = logEntry("agent-1", "conv-1", "ERROR");
            capturedListener.accept(entry);

            verify(eventSink, atLeastOnce()).send(any(OutboundSseEvent.class));
        }
    }

    // ─── sendEvent error handling ───────────────────────────

    @Nested
    @DisplayName("sendEvent error handling")
    class SendEventErrors {

        @Test
        @DisplayName("should handle exception in sendEvent gracefully")
        void handlesSendException() {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class, RETURNS_DEEP_STUBS);
            when(sse.newEventBuilder().name(anyString()).data(any()).build())
                    .thenThrow(new RuntimeException("Build failed"));
            when(eventSink.isClosed()).thenReturn(false);

            // Initial batch with one entry
            var entry = logEntry("agent-1", null, "INFO");
            when(boundedLogStore.getEntries(any(), any(), any(), anyInt())).thenReturn(List.of(entry));
            when(boundedLogStore.addListener(any())).thenReturn("listener-err");

            // Should not throw
            assertDoesNotThrow(() -> restLogAdmin.streamLogs(null, null, null, eventSink, sse));
        }
    }

    // ─── Multiple initial entries sent in reverse ───────────

    @Nested
    @DisplayName("Initial batch ordering")
    class InitialBatch {

        @Test
        @DisplayName("should send initial entries in reverse order (newest first)")
        void sendsInReverseOrder() {
            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);
            var builder = mock(OutboundSseEvent.Builder.class);
            var event = mock(OutboundSseEvent.class);
            when(sse.newEventBuilder()).thenReturn(builder);
            when(builder.name(anyString())).thenReturn(builder);
            when(builder.data(any())).thenReturn(builder);
            when(builder.build()).thenReturn(event);
            when(eventSink.send(any(OutboundSseEvent.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            when(eventSink.isClosed()).thenReturn(false); // keep open so the initial batch can be sent

            var entry1 = logEntry("a1", null, "INFO");
            var entry2 = logEntry("a2", null, "WARN");
            var entry3 = logEntry("a3", null, "ERROR");
            when(boundedLogStore.getEntries(any(), any(), any(), anyInt()))
                    .thenReturn(List.of(entry1, entry2, entry3));
            when(boundedLogStore.addListener(any())).thenReturn("listener-3");

            restLogAdmin.streamLogs(null, null, null, eventSink, sse);

            // Verify order: entry3, then entry2, then entry1
            var inOrder = inOrder(builder);
            inOrder.verify(builder).data(entry3);
            inOrder.verify(builder).data(entry2);
            inOrder.verify(builder).data(entry1);
            verify(eventSink, times(3)).send(event);
        }
    }

    private LogEntry logEntry(String agentId, String conversationId, String level) {
        return new LogEntry(System.currentTimeMillis(), level, "test.Logger", "message",
                null, agentId, null, conversationId, null, null);
    }
}
