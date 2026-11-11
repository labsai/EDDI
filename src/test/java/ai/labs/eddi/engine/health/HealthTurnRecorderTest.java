/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore.StateAndRevision;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a row written after the self-heal grace passed its capture marks its day at once, not on the debounced flush")
    void lateWriteMarksAtOnce() {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        LocalDate day = LocalDate.of(2026, 11, 10);
        Instant captured = CLOCK.instant();
        AtomicReference<Instant> now = new AtomicReference<>(captured);
        Clock moving = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        // The worker reaches the store two minutes after the capture.
        when(store.daysOf(eq("conv-1"), any())).thenAnswer(inv -> {
            now.set(captured.plus(Duration.ofMinutes(2)));
            return Set.of(day);
        });
        HealthTurnRecorder marking = new HealthTurnRecorder(instance(store), HealthSettings.defaults(), new HealthMetrics(registry), dirty, moving);
        try {
            marking.record(memory(), null, null, null, false);
            verify(dirty, timeout(5_000)).markLateWrite("agent-1", Set.of(day));
            verify(dirty, never()).note(eq("agent-1"), any());
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

    @Test
    @DisplayName("a turn whose snapshot was not stored is inserted only if absent, never as paused, and discards nothing")
    void uncommittedIsUnsettled() {
        ConversationMemory memory = memory();
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));

        recorder.record(memory, null, null, 5L, false, false);

        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).insertIfAbsent(row.capture());
        assertFalse(row.getValue().paused());
        verify(store, after(300).never()).upsert(any());
        verify(store, never()).markDiscarded(any(), any());
    }

    @Test
    @DisplayName("a committed pause is settled: upserted as paused")
    void committedPauseIsSettled() {
        ConversationMemory memory = memory();
        memory.setConversationState(ConversationState.AWAITING_HUMAN);

        recorder.record(memory, null, null, 5L, false, true);

        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).upsert(row.capture());
        assertTrue(row.getValue().paused());
        verify(store, never()).insertIfAbsent(any());
    }

    /**
     * A row waiting in the health queue, or being written, while its user is erased
     * or its conversation deleted must not survive the delete.
     */
    @Nested
    @DisplayName("erasure and conversation delete against queued writes")
    class ErasureAndPurge {

        private IConversationMemoryStore conversations;
        private HealthErasureFence fence;
        private HealthTurnRecorder guarded;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            conversations = mock(IConversationMemoryStore.class);
            when(conversations.getStateAndRevision(any())).thenReturn(new StateAndRevision(ConversationState.READY, 3L));
            Instance<IConversationMemoryStore> conversationInstance = mock(Instance.class);
            when(conversationInstance.isResolvable()).thenReturn(true);
            when(conversationInstance.get()).thenReturn(conversations);
            fence = new HealthErasureFence();
            guarded = new HealthTurnRecorder(instance(store), conversationInstance, fence, HealthSettings.defaults(), new HealthMetrics(registry),
                    CLOCK);
        }

        @AfterEach
        void tearDown() {
            release.countDown();
            guarded.shutdown();
        }

        /** Occupies the worker with another conversation's row until released. */
        private CountDownLatch blockWorker() {
            CountDownLatch busy = new CountDownLatch(1);
            doAnswer(inv -> {
                HealthTurn turn = inv.getArgument(0);
                if ("conv-blocker".equals(turn.conversationId())) {
                    busy.countDown();
                    release.await(30, TimeUnit.SECONDS);
                }
                return null;
            }).when(store).upsert(any());
            guarded.record(new ConversationMemory("conv-blocker", "agent-1", 1, "user-other"), null, null, null, false);
            return busy;
        }

        @Test
        @DisplayName("erasure while the row is queued: neither the row nor its discard markers are written")
        void erasedWhileQueued() throws Exception {
            assertTrue(blockWorker().await(5, TimeUnit.SECONDS));
            ConversationMemory memory = memory();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
            guarded.record(memory, null, null, null, false);
            assertEquals(1, guarded.queued());

            // The erasure's step 0, then its delete of the user's rows.
            fence.stopInFlightWork("user-1");
            store.deleteByUserId("user-1");
            release.countDown();

            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
            verify(store, never()).upsert(argThat(turn -> "conv-1".equals(turn.conversationId())));
            verify(store, never()).insertIfAbsent(any());
            verify(store, never()).markDiscarded(any(), any());
        }

        @Test
        @DisplayName("erasure while the row is being written: it is deleted again right after the write")
        void erasedWhileWriting() throws Exception {
            CountDownLatch writing = new CountDownLatch(1);
            doAnswer(inv -> {
                writing.countDown();
                release.await(30, TimeUnit.SECONDS);
                return null;
            }).when(store).upsert(any());
            guarded.record(memory(), null, null, null, false);
            assertTrue(writing.await(5, TimeUnit.SECONDS));

            // The erasure runs (and finds nothing yet) while the write is in flight.
            fence.stopInFlightWork("user-1");
            release.countDown();

            InOrder order = inOrder(store);
            order.verify(store, timeout(5_000)).upsert(any());
            order.verify(store, timeout(5_000)).deleteConversationReturningDays("conv-1");
        }

        @Test
        @DisplayName("conversation deleted while the row was queued (on any node): the row and its markers are removed after the write")
        void purgedWhileQueued() throws Exception {
            assertTrue(blockWorker().await(5, TimeUnit.SECONDS));
            ConversationMemory memory = memory();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
            guarded.record(memory, null, null, null, false);

            // The purge deletes the snapshot, then the conversation's health rows.
            when(conversations.getStateAndRevision("conv-1")).thenReturn(new StateAndRevision(null, null));
            store.deleteByConversationId("conv-1");
            release.countDown();

            InOrder order = inOrder(store);
            order.verify(store).deleteByConversationId("conv-1");
            order.verify(store, timeout(5_000)).upsert(argThat(turn -> "conv-1".equals(turn.conversationId())));
            order.verify(store, timeout(5_000)).markDiscarded(any(), eq(List.of("old-exec")));
            order.verify(store, timeout(5_000)).deleteConversationReturningDays("conv-1");
            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
        }

        @Test
        @DisplayName("normal writes are unaffected: a live conversation keeps its row, and a store that cannot answer deletes nothing")
        void normalWrites() {
            guarded.record(memory(), null, null, null, false);
            verify(store, timeout(5_000)).upsert(any());
            assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 1.0);

            when(conversations.getStateAndRevision(any())).thenReturn(null);
            guarded.record(new ConversationMemory("conv-2", "agent-1", 2, "user-2"), null, null, null, false);
            assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 2.0);

            verify(store, after(300).never()).deleteConversationReturningDays(any());
            assertEquals(0.0, dropped(HealthMetrics.REASON_ERASED));
        }

        @Test
        @DisplayName("a failing cleanup never reaches the worker's caller")
        void cleanupFailure() {
            when(conversations.getStateAndRevision(any())).thenThrow(new IllegalStateException("db down"));
            assertDoesNotThrow(() -> guarded.record(memory(), null, null, null, false));
            assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 1.0);
            verify(store, after(300).never()).deleteConversationReturningDays(any());
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
