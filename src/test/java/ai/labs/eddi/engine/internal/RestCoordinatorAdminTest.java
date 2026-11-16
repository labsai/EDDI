/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import java.util.Optional;
import jakarta.ws.rs.WebApplicationException;
import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.cluster.admin.ClusterAdminService;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.ItemOutcome;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.sse.SseEventSink;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.OutboundSseEvent;
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
            var status = new CoordinatorStatus("in-memory", true, "OK", 5, 100L, 0L, Map.of());
            when(coordinator.getStatus()).thenReturn(status);

            CoordinatorStatus result = restCoordinatorAdmin.getStatus(null);

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

            List<DeadLetterEntry> result = restCoordinatorAdmin.getDeadLetters(100, null);

            assertEquals(1, result.size());
            assertEquals(entries, result);
            verify(coordinator).getDeadLetters();
        }
    }

    @Nested
    @DisplayName("replayDeadLetter")
    class ReplayDeadLetter {

        private ClusterAdminService clusterAdmin;

        @BeforeEach
        void wire() {
            clusterAdmin = mock(ClusterAdminService.class);
            restCoordinatorAdmin.clusterAdmin = clusterAdmin;
        }

        private void outcome(String id, String outcome, String message) {
            when(clusterAdmin.replay(eq(List.of(id)), anyString())).thenReturn(
                    new BulkResult(List.of(new ItemOutcome(id, outcome, message)), "REPLAYED".equals(outcome) ? 1 : 0,
                            "REPLAYED".equals(outcome) ? 0 : 1));
        }

        @Test
        @DisplayName("A7: goes through the console's replay (claim, commit-gated delete, audit) with the calling admin")
        void success() {
            outcome("dl-1", "REPLAYED", null);
            assertDoesNotThrow(() -> restCoordinatorAdmin.replayDeadLetter("dl-1"));
            verify(clusterAdmin).replay(List.of("dl-1"), "anonymous");
            verify(coordinator, never()).replayDeadLetter(anyString());
        }

        @Test
        @DisplayName("refuses (409) an entry without captured input and keeps it")
        void notReplayable() {
            outcome("dl-2", "NOT_REPLAYABLE", DeadLetterEntry.NOT_REPLAYABLE_NOT_CAPTURED);
            var e = assertThrows(WebApplicationException.class, () -> restCoordinatorAdmin.replayDeadLetter("dl-2"));
            assertEquals(409, e.getResponse().getStatus());
        }

        @Test
        @DisplayName("A7: refuses (409) while another administrator replays the same entry")
        void inProgress() {
            outcome("dl-3", "IN_PROGRESS", "another administrator is replaying this entry right now");
            var e = assertThrows(WebApplicationException.class, () -> restCoordinatorAdmin.replayDeadLetter("dl-3"));
            assertEquals(409, e.getResponse().getStatus());
        }

        @Test
        @DisplayName("keeps the entry (409) when the conversation refuses the turn")
        void refusedTurnKeepsEntry() {
            outcome("dl-4", "REJECTED", "ConversationEndedException: ended");
            var e = assertThrows(WebApplicationException.class, () -> restCoordinatorAdmin.replayDeadLetter("dl-4"));
            assertEquals(409, e.getResponse().getStatus());
        }

        @Test
        @DisplayName("should throw NotFoundException when entry not found")
        void notFound() {
            outcome("dl-missing", "NOT_FOUND", "already replayed, discarded or expired");
            assertThrows(NotFoundException.class, () -> restCoordinatorAdmin.replayDeadLetter("dl-missing"));
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
            var status = new CoordinatorStatus("in-memory", true, "OK", 0, 0L, 0L, Map.of());
            when(coordinator.getStatus()).thenReturn(status);

            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class, RETURNS_DEEP_STUBS);
            var event = mock(OutboundSseEvent.class);

            when(sse.newEventBuilder().name(anyString()).data(any()).build()).thenReturn(event);

            restCoordinatorAdmin.streamEvents(eventSink, sse);

            verify(eventSink).send(event);
            verify(coordinator).getStatus();
        }

        @Test
        @DisplayName("should handle exception during initial status send")
        void handlesInitialStatusError() {
            when(coordinator.getStatus()).thenThrow(new RuntimeException("Status unavailable"));

            var eventSink = mock(SseEventSink.class);
            var sse = mock(Sse.class);

            // Should not throw — error is caught internally
            assertDoesNotThrow(() -> restCoordinatorAdmin.streamEvents(eventSink, sse));
        }
    }
}
