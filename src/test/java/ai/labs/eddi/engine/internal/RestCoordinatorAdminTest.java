package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestCoordinatorAdmin}.
 *
 * <p>
 * Verifies REST delegation to the {@link IConversationCoordinator}, including
 * status, dead-letter CRUD, and error handling.
 * </p>
 */
class RestCoordinatorAdminTest {

    private IConversationCoordinator coordinator;
    private RestCoordinatorAdmin admin;

    @BeforeEach
    void setUp() {
        coordinator = mock(IConversationCoordinator.class);
        admin = new RestCoordinatorAdmin(coordinator);
    }

    // ==================== Status ====================

    @Test
    void shouldReturnCoordinatorStatus() {
        CoordinatorStatus expected = new CoordinatorStatus("in-memory", true, "CONNECTED", 3, 100L, 2L, Map.of("conv-1", 2, "conv-2", 1));
        when(coordinator.getStatus()).thenReturn(expected);

        CoordinatorStatus result = admin.getStatus();

        assertEquals("in-memory", result.coordinatorType());
        assertTrue(result.connected());
        assertEquals(3, result.activeConversations());
        assertEquals(100L, result.totalProcessed());
        assertEquals(2L, result.totalDeadLettered());
        assertEquals(2, result.queueDepths().size());
        verify(coordinator).getStatus();
    }

    @Test
    void shouldReturnNatsStatus() {
        CoordinatorStatus nats = new CoordinatorStatus("nats", true, "CONNECTED", 0, 500L, 0L, Collections.emptyMap());
        when(coordinator.getStatus()).thenReturn(nats);

        CoordinatorStatus result = admin.getStatus();

        assertEquals("nats", result.coordinatorType());
        assertEquals(500L, result.totalProcessed());
        assertEquals(0, result.queueDepths().size());
    }

    // ==================== Dead-Letters ====================

    @Test
    void shouldReturnDeadLetters() {
        List<DeadLetterEntry> entries = List.of(new DeadLetterEntry("1", "conv-fail-1", "timeout", 1000L, "{}"),
                new DeadLetterEntry("2", "conv-fail-2", "NPE", 2000L, "{}"));
        when(coordinator.getDeadLetters()).thenReturn(entries);

        List<DeadLetterEntry> result = admin.getDeadLetters();

        assertEquals(2, result.size());
        assertEquals("conv-fail-1", result.get(0).conversationId());
        assertEquals("timeout", result.get(0).error());
        verify(coordinator).getDeadLetters();
    }

    @Test
    void shouldReturnEmptyDeadLetters() {
        when(coordinator.getDeadLetters()).thenReturn(Collections.emptyList());

        List<DeadLetterEntry> result = admin.getDeadLetters();

        assertTrue(result.isEmpty());
    }

    // ==================== Replay ====================

    @Test
    void shouldReplayDeadLetter() {
        when(coordinator.replayDeadLetter("dl-1")).thenReturn(true);

        admin.replayDeadLetter("dl-1");

        verify(coordinator).replayDeadLetter("dl-1");
    }

    @Test
    void shouldThrowNotFoundWhenReplayingNonExistent() {
        when(coordinator.replayDeadLetter("nonexistent")).thenReturn(false);

        assertThrows(NotFoundException.class, () -> admin.replayDeadLetter("nonexistent"));
    }

    // ==================== Discard ====================

    @Test
    void shouldDiscardDeadLetter() {
        when(coordinator.discardDeadLetter("dl-2")).thenReturn(true);

        admin.discardDeadLetter("dl-2");

        verify(coordinator).discardDeadLetter("dl-2");
    }

    @Test
    void shouldThrowNotFoundWhenDiscardingNonExistent() {
        when(coordinator.discardDeadLetter("nonexistent")).thenReturn(false);

        assertThrows(NotFoundException.class, () -> admin.discardDeadLetter("nonexistent"));
    }

    // ==================== Purge ====================

    @Test
    void shouldPurgeAllDeadLetters() {
        when(coordinator.purgeDeadLetters()).thenReturn(5);

        int count = admin.purgeDeadLetters();

        assertEquals(5, count);
        verify(coordinator).purgeDeadLetters();
    }

    @Test
    void shouldReturnZeroWhenPurgingEmpty() {
        when(coordinator.purgeDeadLetters()).thenReturn(0);

        int count = admin.purgeDeadLetters();

        assertEquals(0, count);
    }
}
