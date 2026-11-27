/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.Admission;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard.ErasureIncompleteException;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.InMemoryErasureTombstoneStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The session outcomes in the erasure guarantee, wired for real: a real
 * {@link ErasureGuard} over in-memory tombstones with the real
 * {@link HealthErasureWriter}, the outcome store stubbed.
 */
@DisplayName("Session outcomes through the real erasure guard and HealthErasureWriter")
class HealthOutcomeErasureWiringTest {

    private InMemoryErasureTombstoneStore tombstones;
    private ErasureGuard guard;
    private IHealthTurnStore turns;
    private IHealthOutcomeStore outcomes;
    /** The sessions the stubbed outcome store holds for conversation "conv". */
    private AtomicLong storedSessions;

    @BeforeEach
    void setUp() throws Exception {
        tombstones = new InMemoryErasureTombstoneStore();
        turns = mock(IHealthTurnStore.class);
        outcomes = mock(IHealthOutcomeStore.class);
        storedSessions = new AtomicLong(2);
        when(outcomes.countByConversationId("conv")).thenAnswer(inv -> storedSessions.get());
        HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
        when(recorder.awaitDrained(any())).thenReturn(true);
        HealthOutcomeEvaluator evaluator = mock(HealthOutcomeEvaluator.class);
        when(evaluator.awaitDrained(any())).thenReturn(true);
        HealthErasureWriter writer = new HealthErasureWriter(recorder, turns, null, null, instance(outcomes), instance(evaluator));
        guard = new ErasureGuard(tombstones, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Clock.systemUTC());
    }

    @Test
    @DisplayName("a recorded session removal is retried through removeRow and deletes exactly that session")
    void pendingSessionRemovalIsRetried() {
        Admission admission = guard.beforeWrite("user", "conv", Instant.now());
        assertTrue(admission.allowed());
        guard.tombstoneConversation("conv");
        // The evaluator's own removal fails: the guard records a pending removal.
        assertTrue(guard.afterWrite(HealthErasureWriter.NAME, HealthErasureWriter.sessionRowId("conv", 3), admission, () -> {
            throw new HealthStoreException("down", null);
        }));
        assertEquals(1, tombstones.pendingCount());

        assertEquals(1, guard.retryPending(null, null, "conv"));

        verify(outcomes).deleteSessions("conv", List.of(3));
        assertEquals(0, tombstones.pendingCount());
    }

    @Test
    @DisplayName("a guarded conversation delete erases the sessions and verifies none is left")
    void conversationDeleteErasesSessions() {
        when(outcomes.deleteByConversationId("conv")).thenAnswer(inv -> storedSessions.getAndSet(0));
        assertDoesNotThrow(() -> guard.eraseConversation("conv"));
        verify(outcomes).deleteByConversationId("conv");
        assertEquals(0, storedSessions.get());
    }

    @Test
    @DisplayName("sessions left after the delete fail the conversation's erasure")
    void remainingSessionsFailTheErasure() {
        // The delete removes nothing: the count must catch it.
        when(outcomes.deleteByConversationId("conv")).thenReturn(0L);
        assertThrows(ErasureIncompleteException.class, () -> guard.eraseConversation("conv"));
        Map<String, String> failed = guard.eraseConversationRows(List.of("conv"));
        assertTrue(failed.containsKey("conv"), failed.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(bean);
        return instance;
    }
}
