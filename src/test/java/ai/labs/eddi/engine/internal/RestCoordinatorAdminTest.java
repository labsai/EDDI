package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestCoordinatorAdmin}.
 */
class RestCoordinatorAdminTest {

    private IConversationCoordinator coordinator;
    private RestCoordinatorAdmin restCoordinatorAdmin;

    @BeforeEach
    void setUp() {
        coordinator = mock(IConversationCoordinator.class);
        restCoordinatorAdmin = new RestCoordinatorAdmin(coordinator);
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("should delegate to coordinator")
        void delegatesToCoordinator() {
            var status = new CoordinatorStatus("in-memory", true, "OK", 5, 100L, 0L, java.util.Map.of());
            when(coordinator.getStatus()).thenReturn(status);

            CoordinatorStatus result = restCoordinatorAdmin.getStatus();

            assertEquals("in-memory", result.coordinatorType());
            assertTrue(result.connected());
            assertEquals("OK", result.connectionStatus());
            assertEquals(5, result.activeConversations());
            assertEquals(100L, result.totalProcessed());
            assertEquals(0L, result.totalDeadLettered());
            assertTrue(result.queueDepths().isEmpty());
            verify(coordinator).getStatus();
        }
    }

    @Nested
    @DisplayName("getDeadLetters")
    class GetDeadLetters {

        @Test
        @DisplayName("should return dead letter list from coordinator")
        void returnsList() {
            var entries = List.of(new DeadLetterEntry("1", "c1", "err", 0, ""));
            when(coordinator.getDeadLetters()).thenReturn(entries);

            List<DeadLetterEntry> result = restCoordinatorAdmin.getDeadLetters();

            assertEquals(1, result.size());
            assertEquals(entries, result);
            verify(coordinator).getDeadLetters();
        }
    }

    @Nested
    @DisplayName("replayDeadLetter")
    class ReplayDeadLetter {

        @Test
        @DisplayName("should succeed when coordinator returns true")
        void success() {
            when(coordinator.replayDeadLetter("dl-1")).thenReturn(true);

            assertDoesNotThrow(() -> restCoordinatorAdmin.replayDeadLetter("dl-1"));
            verify(coordinator).replayDeadLetter("dl-1");
        }

        @Test
        @DisplayName("should throw NotFoundException when entry not found")
        void notFound() {
            when(coordinator.replayDeadLetter("dl-missing")).thenReturn(false);

            assertThrows(NotFoundException.class,
                    () -> restCoordinatorAdmin.replayDeadLetter("dl-missing"));
        }
    }

    @Nested
    @DisplayName("discardDeadLetter")
    class DiscardDeadLetter {

        @Test
        @DisplayName("should succeed when coordinator returns true")
        void success() {
            when(coordinator.discardDeadLetter("dl-1")).thenReturn(true);

            assertDoesNotThrow(() -> restCoordinatorAdmin.discardDeadLetter("dl-1"));
            verify(coordinator).discardDeadLetter("dl-1");
        }

        @Test
        @DisplayName("should throw NotFoundException when entry not found")
        void notFound() {
            when(coordinator.discardDeadLetter("dl-missing")).thenReturn(false);

            assertThrows(NotFoundException.class,
                    () -> restCoordinatorAdmin.discardDeadLetter("dl-missing"));
        }
    }

    @Nested
    @DisplayName("purgeDeadLetters")
    class PurgeDeadLetters {

        @Test
        @DisplayName("should return purged count")
        void returnsPurgedCount() {
            when(coordinator.purgeDeadLetters()).thenReturn(3);

            assertEquals(3, restCoordinatorAdmin.purgeDeadLetters());
        }

        @Test
        @DisplayName("should return 0 when nothing to purge")
        void returnsZero() {
            when(coordinator.purgeDeadLetters()).thenReturn(0);

            assertEquals(0, restCoordinatorAdmin.purgeDeadLetters());
        }
    }

    @Nested
    @DisplayName("streamEvents")
    class StreamEvents {

        @Test
        @DisplayName("should send initial status and register client")
        void sendsInitialStatus() {
            var status = new CoordinatorStatus("in-memory", true, "OK", 0, 0L, 0L, java.util.Map.of());
            when(coordinator.getStatus()).thenReturn(status);

            var eventSink = mock(jakarta.ws.rs.sse.SseEventSink.class);
            var sse = mock(jakarta.ws.rs.sse.Sse.class, RETURNS_DEEP_STUBS);
            var event = mock(jakarta.ws.rs.sse.OutboundSseEvent.class);

            when(sse.newEventBuilder().name(anyString()).data(any()).build()).thenReturn(event);

            restCoordinatorAdmin.streamEvents(eventSink, sse);

            verify(eventSink).send(event);
            verify(coordinator).getStatus();
        }

        @Test
        @DisplayName("should handle exception during initial status send")
        void handlesInitialStatusError() {
            when(coordinator.getStatus()).thenThrow(new RuntimeException("Status unavailable"));

            var eventSink = mock(jakarta.ws.rs.sse.SseEventSink.class);
            var sse = mock(jakarta.ws.rs.sse.Sse.class);

            // Should not throw — error is caught internally
            assertDoesNotThrow(() -> restCoordinatorAdmin.streamEvents(eventSink, sse));
        }
    }
}
