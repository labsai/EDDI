/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.gdpr.erasure.AsyncErasureWriter;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.InMemoryErasureTombstoneStore;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
    void writesTheRow() throws Exception {
        recorder.record(memory(), null, ErrorKind.TURN_ERROR, 42L, false);

        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).upsert(row.capture());
        assertEquals("conv-1", row.getValue().conversationId());
        assertEquals("TURN_ERROR", row.getValue().errorKind());
        assertEquals(42L, row.getValue().durationMs());
        // The write is complete, its erasure check included: nothing more can happen.
        assertTrue(recorder.awaitDrained(Duration.ofSeconds(5)));
        verify(store, never()).markDiscarded(any(), any());
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
    void abandonedIsInsertOnly() throws Exception {
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));

        recorder.record(memory, null, ErrorKind.TURN_DEADLINE, 60_000L, true);

        verify(store, timeout(5_000)).insertIfAbsent(any());
        // The write is complete, its erasure check included: nothing more can happen.
        assertTrue(recorder.awaitDrained(Duration.ofSeconds(5)));
        verify(store, never()).upsert(any());
        verify(store, never()).markDiscarded(any(), any());
    }

    @Test
    @DisplayName("a cancelled say after an undo leaves the undone execution alone")
    void cancelledDoesNotDiscard() throws Exception {
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
        memory.setCancelled(true);

        recorder.record(memory, null, ErrorKind.TURN_INTERRUPTED, null, false);

        verify(store, timeout(5_000)).insertIfAbsent(any());
        // The write is complete, its erasure check included: nothing more can happen.
        assertTrue(recorder.awaitDrained(Duration.ofSeconds(5)));
        verify(store, never()).markDiscarded(any(), any());
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
    @DisplayName("a turn whose snapshot was not stored is inserted only if absent, never as paused, and discards nothing")
    void uncommittedIsUnsettled() throws Exception {
        ConversationMemory memory = memory();
        memory.setConversationState(ConversationState.AWAITING_HUMAN);
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));

        recorder.record(memory, null, null, 5L, false, false);

        ArgumentCaptor<HealthTurn> row = ArgumentCaptor.forClass(HealthTurn.class);
        verify(store, timeout(5_000)).insertIfAbsent(row.capture());
        assertFalse(row.getValue().paused());
        // The write is complete, its erasure check included: nothing more can happen.
        assertTrue(recorder.awaitDrained(Duration.ofSeconds(5)));
        verify(store, never()).upsert(any());
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
     * or its conversation deleted must not survive the delete — the erasure
     * guarantee ({@link ErasureGuard}), with a real guard over in-memory
     * tombstones.
     */
    @Nested
    @DisplayName("erasure and conversation delete against queued writes (the erasure guard)")
    class ErasureAndPurge {

        private InMemoryErasureTombstoneStore tombstones;
        private ErasureGuard guard;
        private HealthTurnRecorder guarded;
        private HealthErasureWriter writer;

        @BeforeEach
        void setUp() {
            tombstones = new InMemoryErasureTombstoneStore();
            List<AsyncErasureWriter> writers = new ArrayList<>();
            guard = new ErasureGuard(tombstones, writers, new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400), Duration.ofSeconds(5),
                    Duration.ofMinutes(5), CLOCK);
            guarded = new HealthTurnRecorder(instance(store), guard, HealthSettings.defaults(), new HealthMetrics(registry), CLOCK);
            writer = new HealthErasureWriter(guarded, store);
            writers.add(writer);
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

        private ConversationMemory memoryWithDiscard() {
            ConversationMemory memory = memory();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
            return memory;
        }

        @Test
        @DisplayName("user erased while the row is queued: neither the row nor its discard markers are written")
        void erasedWhileQueued() throws Exception {
            assertTrue(blockWorker().await(5, TimeUnit.SECONDS));
            guarded.record(memoryWithDiscard(), null, null, null, false);
            assertEquals(1, guarded.queued());

            guard.tombstoneUser("user-1", List.of());
            release.countDown();

            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
            verify(store, never()).upsert(argThat(turn -> "conv-1".equals(turn.conversationId())));
            verify(store, never()).insertIfAbsent(any());
            verify(store, never()).markDiscarded(any(), any());
        }

        @Test
        @DisplayName("conversation deleted while the row is queued: nothing is written")
        void purgedWhileQueued() throws Exception {
            assertTrue(blockWorker().await(5, TimeUnit.SECONDS));
            guarded.record(memoryWithDiscard(), null, null, null, false);

            guard.tombstoneConversation("conv-1");
            release.countDown();

            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
            verify(store, never()).upsert(argThat(turn -> "conv-1".equals(turn.conversationId())));
            verify(store, never()).markDiscarded(any(), any());
        }

        @Test
        @DisplayName("erasure while the row is being written: the row and each marker are deleted again right after the write")
        void erasedWhileWriting() throws Exception {
            CountDownLatch writing = new CountDownLatch(1);
            doAnswer(inv -> {
                writing.countDown();
                release.await(30, TimeUnit.SECONDS);
                return null;
            }).when(store).upsert(any());
            guarded.record(memoryWithDiscard(), null, null, null, false);
            assertTrue(writing.await(5, TimeUnit.SECONDS));

            // The erasure starts while the write is in flight.
            guard.tombstoneUser("user-1", List.of("conv-1"));
            release.countDown();

            ArgumentCaptor<HealthTurn> written = ArgumentCaptor.forClass(HealthTurn.class);
            InOrder order = inOrder(store);
            order.verify(store, timeout(5_000)).upsert(written.capture());
            order.verify(store, timeout(5_000)).markDiscarded(any(), eq(List.of("old-exec")));
            order.verify(store, timeout(5_000)).deleteRow("conv-1", written.getValue().executionId());
            order.verify(store, timeout(5_000)).deleteRow("conv-1", "old-exec");
            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
            assertEquals(0, tombstones.pendingCount());
        }

        @Test
        @DisplayName("a failed write is still checked: a row it may have stored is removed")
        void failedWriteStillGuarded() {
            doAnswer(inv -> {
                // Part of the write landed, then the store failed; the erasure started
                // meanwhile.
                guard.tombstoneConversation("conv-1");
                throw new HealthStoreException("timeout", null);
            }).when(store).upsert(any());
            guarded.record(memory(), null, null, null, false);

            verify(store, timeout(5_000)).deleteRow(eq("conv-1"), any());
            assertTrueEventually(() -> dropped(HealthMetrics.REASON_STORE_ERROR) == 1.0);
        }

        @Test
        @DisplayName("a removal that fails is recorded as pending and keeps the user's erasure failing until a retry removes the row")
        void failedRemovalKeepsErasureFailing() throws Exception {
            CountDownLatch writing = new CountDownLatch(1);
            doAnswer(inv -> {
                writing.countDown();
                release.await(30, TimeUnit.SECONDS);
                return null;
            }).when(store).upsert(any());
            when(store.deleteRow(any(), any())).thenThrow(new HealthStoreException("store down", null));
            guarded.record(memory(), null, null, null, false);
            assertTrue(writing.await(5, TimeUnit.SECONDS));

            guard.tombstoneUser("user-1", List.of("conv-1"));
            release.countDown();
            assertTrueEventually(() -> tombstones.pendingCount() == 1);

            // The erasure's own delete reports the rows gone, but the pending removal is
            // still there and its retry fails: the step fails.
            HealthTurnGdprParticipant participant = new HealthTurnGdprParticipant(store, guard);
            when(store.countByUserId("user-1")).thenReturn(0L);
            assertThrows(IllegalStateException.class, () -> participant.erase("user-1"));

            // The store recovers: the retry removes the row, and a re-run completes.
            doReturn(1L).when(store).deleteRow(any(), any());
            assertDoesNotThrow(() -> participant.erase("user-1"));
            assertEquals(0, tombstones.pendingCount());
        }

        @Test
        @DisplayName("a turn captured after the user's erasure (a reused id) is stored")
        void capturedAfterErasureIsStored() throws Exception {
            // The tombstone is an hour older than the capture: beyond the clock skew.
            ErasureGuard earlier = new ErasureGuard(tombstones, List.of(writer), new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400),
                    Duration.ofSeconds(5), Duration.ofMinutes(5), Clock.offset(CLOCK, Duration.ofHours(-1)));
            earlier.tombstoneUser("user-1", List.of());

            guarded.record(memory(), null, null, null, false);

            verify(store, timeout(5_000)).upsert(argThat(turn -> "conv-1".equals(turn.conversationId())));
            // The write is complete, its erasure check included: nothing more can happen.
            assertTrue(guarded.awaitDrained(Duration.ofSeconds(5)));
            verify(store, never()).deleteRow(any(), any());
            assertEquals(0.0, dropped(HealthMetrics.REASON_ERASED));
        }

        @Test
        @DisplayName("tombstones that cannot be read refuse the write (fail closed)")
        void unreadableTombstonesRefuse() {
            tombstones.failReads = true;
            guarded.record(memory(), null, null, null, false);

            assertTrueEventually(() -> dropped(HealthMetrics.REASON_ERASED) == 1.0);
            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("the drain barrier waits for a write in progress")
        void drainBarrier() throws Exception {
            assertTrue(blockWorker().await(5, TimeUnit.SECONDS));
            assertFalse(writer.awaitDrained(Duration.ofMillis(100)), "the blocked write is outstanding");
            release.countDown();
            assertTrue(writer.awaitDrained(Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("normal writes are unaffected: no tombstone, nothing deleted")
        void normalWrites() throws Exception {
            guarded.record(memory(), null, null, null, false);
            verify(store, timeout(5_000)).upsert(any());
            assertTrueEventually(() -> registry.counter("eddi.health.recorded").count() == 1.0);

            // The write is complete, its erasure check included: nothing more can happen.
            assertTrue(guarded.awaitDrained(Duration.ofSeconds(5)));
            verify(store, never()).deleteRow(any(), any());
            assertEquals(0.0, dropped(HealthMetrics.REASON_ERASED));
        }
    }

    private static void assertTrueEventually(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condition.getAsBoolean()) {
                // Interruptible: the timeout's interrupt ends the loop.
                Thread.sleep(1);
            }
        });
    }
}
