/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("HealthTurnRecorder — own bounded worker, never in the turn's way")
class HealthTurnRecorderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    private IHealthTurnStore store;
    private SimpleMeterRegistry registry;
    private HealthTurnRecorder recorder;
    private final CountDownLatch release = new CountDownLatch(1);

    @SuppressWarnings("unchecked")
    private static Instance<IHealthTurnStore> instance(IHealthTurnStore store) {
        Instance<IHealthTurnStore> instance = mock(Instance.class);
        when(instance.get()).thenReturn(store);
        return instance;
    }

    private HealthTurnRecorder recorder(HealthSettings settings) {
        return new HealthTurnRecorder(instance(store), settings, new HealthMetrics(registry), CLOCK);
    }

    @BeforeEach
    void setUp() {
        store = mock(IHealthTurnStore.class);
        registry = new SimpleMeterRegistry();
        recorder = recorder(HealthSettings.defaults());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        recorder.shutdown();
    }

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-1", "agent-1", 2, "user-1");
    }

    private double dropped(String reason) {
        return registry.counter("eddi.health.dropped", "reason", reason).count();
    }

    @Test
    @DisplayName("a turn is written on the health worker as a replace-upsert of its row")
    void writesTheRow() {
        recorder.record(memory(), null, ErrorKind.TURN_ERROR, 42L, false);

        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).upsert(row.capture());
        assertEquals("conv-1", row.getValue().conversationId());
        assertEquals("TURN_ERROR", row.getValue().errorKind());
        assertEquals(42L, row.getValue().durationMs());
        verify(store, after(300).never()).markDiscarded(any(), any());
        verify(store, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("an undo-replacing turn writes its own row first, then marks the undone executions discarded")
    void marksDiscardedAfterTheNewRow() {
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));

        recorder.record(memory, null, null, null, false);

        InOrder order = inOrder(store);
        order.verify(store, timeout(5_000)).upsert(any());
        order.verify(store, timeout(5_000)).markDiscarded(argThat(turn -> "conv-1".equals(turn.conversationId())), eq(List.of("old-exec")));
        assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 1.0);
    }

    @Test
    @DisplayName("eddi.health.enabled=false records nothing")
    void disabled() {
        HealthTurnRecorder off = recorder(new HealthSettings(false, 10, Duration.ofDays(90)));
        try {
            off.record(memory(), null, null, 1L, false);
            verifyNoInteractions(store);
        } finally {
            off.shutdown();
        }
    }

    @Test
    @DisplayName("with the worker stuck and the queue full, 1,000 turns still return at once; every drop is counted")
    void dropsInsteadOfBlocking() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        doAnswer(inv -> {
            busy.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(store).upsert(any());
        HealthTurnRecorder small = recorder(new HealthSettings(true, 3, Duration.ofDays(90)));
        try {
            small.record(memory(), null, null, null, false);
            assertTrue(busy.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (int i = 0; i < 1_000; i++) {
                    small.record(memory(), null, null, null, false);
                }
            });
            assertEquals(1_000 - 3, dropped(HealthMetrics.REASON_QUEUE_FULL));
            assertEquals(3, small.queued());
        } finally {
            release.countDown();
            small.shutdown();
        }
    }

    @Test
    @DisplayName("a failing store is counted as store_error and never reaches the caller")
    void storeFailure() {
        doThrow(new HealthStoreException("down", null)).when(store).upsert(any());
        assertDoesNotThrow(() -> recorder.record(memory(), null, null, null, false));
        assertTrueEventually(() -> dropped(HealthMetrics.REASON_STORE_ERROR) == 1.0);
        assertEquals(0.0, registry.counter("eddi.health.recorded").count());
    }

    @Test
    @DisplayName("a memory that throws while being read is skipped, not propagated")
    void memoryFailure() {
        IConversationMemory broken = mock(IConversationMemory.class);
        when(broken.getConversationId()).thenReturn("c");
        when(broken.getAgentId()).thenReturn("a");
        when(broken.getCurrentStep()).thenThrow(new IllegalStateException("concurrent modification"));
        assertDoesNotThrow(() -> recorder.record(broken, null, null, null, false));
        assertDoesNotThrow(() -> recorder.record(null, null, null, null, false));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("an abandoned wait is inserted only if absent and discards nothing — it never replaces a row")
    void abandonedIsInsertOnly() {
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));

        recorder.record(memory, null, ErrorKind.TURN_DEADLINE, 60_000L, true);

        verify(store, timeout(5_000)).insertIfAbsent(any());
        verify(store, after(300).never()).upsert(any());
        verify(store, never()).markDiscarded(any(), any());
    }

    @Test
    @DisplayName("a cancelled say after an undo leaves the undone execution alone")
    void cancelledDoesNotDiscard() {
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
        memory.setCancelled(true);

        recorder.record(memory, null, ErrorKind.TURN_INTERRUPTED, null, false);

        verify(store, timeout(5_000)).insertIfAbsent(any());
        verify(store, after(300).never()).markDiscarded(any(), any());
    }

    @Test
    @DisplayName("after shutdown a record is counted as shutdown, not as a full queue")
    void afterShutdown() {
        recorder.shutdown();
        recorder.record(memory(), null, null, null, false);
        assertEquals(1.0, dropped(HealthMetrics.REASON_SHUTDOWN));
        assertEquals(0.0, dropped(HealthMetrics.REASON_QUEUE_FULL));
    }

    @Test
    @DisplayName("the dropped and recorded counters and the queue gauge are registered from the start")
    void metersRegistered() {
        assertEquals(0.0, dropped(HealthMetrics.REASON_QUEUE_FULL));
        assertEquals(0.0, dropped(HealthMetrics.REASON_STORE_ERROR));
        assertEquals(0.0, registry.get("eddi.health.queue.depth").gauge().value());
    }

    @Test
    @DisplayName("a resumed turn marks the day its row is stored on (the first-recorded day), not the day of the resume")
    void marksTheStoredDayDirty() {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        LocalDate firstDay = LocalDate.of(2026, 11, 9);
        when(store.daysOf(eq("conv-1"), any())).thenReturn(Set.of(firstDay));
        HealthTurnRecorder marking = new HealthTurnRecorder(instance(store), HealthSettings.defaults(), new HealthMetrics(registry), dirty, CLOCK);
        try {
            marking.record(memory(), null, null, null, false);
            verify(dirty, timeout(5_000)).note("agent-1", Set.of(firstDay));
        } finally {
            marking.shutdown();
        }
    }

    @Test
    @DisplayName("an undo marks the days of the discarded executions too; an unreadable day falls back to the capture's")
    void marksDiscardedDaysDirty() {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        LocalDate undoneDay = LocalDate.of(2026, 11, 8);
        when(store.daysOf(eq("conv-1"), any())).thenThrow(new HealthStoreException("down", null)).thenReturn(Set.of(undoneDay));
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
        HealthTurnRecorder marking = new HealthTurnRecorder(instance(store), HealthSettings.defaults(), new HealthMetrics(registry), dirty, CLOCK);
        try {
            marking.record(memory, null, null, null, false);
            verify(dirty, timeout(5_000)).note("agent-1", Set.of(LocalDate.of(2026, 11, 10)));
            verify(dirty, timeout(5_000)).note("agent-1", Set.of(undoneDay));
            assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 1.0);
        } finally {
            marking.shutdown();
        }
    }

    private static void assertTrueEventually(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condition.getAsBoolean()) {
                Thread.onSpinWait();
            }
        });
    }
}
