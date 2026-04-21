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
            assertEquals(5, result.activeConversations());
        }
    }

    @Nested
    @DisplayName("getDeadLetters")
    class GetDeadLetters {

        @Test
        @DisplayName("should return dead letter list from coordinator")
        void returnsList() {
            when(coordinator.getDeadLetters()).thenReturn(List.of());

            List<DeadLetterEntry> result = restCoordinatorAdmin.getDeadLetters();

            assertTrue(result.isEmpty());
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
}
