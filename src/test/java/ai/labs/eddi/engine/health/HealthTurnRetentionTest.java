/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.ConversationActivity;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore.StateAndRevision;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Agent Health settings, retention and the orphan sweep")
class HealthTurnRetentionTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T00:30:00Z"), ZoneOffset.UTC);

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @SuppressWarnings("unchecked")
    private static Instance<IConversationMemoryStore> instance(IConversationMemoryStore conversations) {
        Instance<IConversationMemoryStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(conversations != null);
        when(instance.get()).thenReturn(conversations);
        return instance;
    }

    private HealthTurnRetention retention(IHealthTurnStore store, IConversationMemoryStore conversations, HealthSettings settings, Clock clock) {
        return new HealthTurnRetention(store, instance(conversations), settings, new HealthMetrics(registry), clock);
    }

    private HealthTurnRetention retention(IHealthTurnStore store, HealthSettings settings) {
        return retention(store, null, settings, CLOCK);
    }

    private double orphaned() {
        return registry.counter("eddi.health.dropped", "reason", HealthMetrics.REASON_ORPHANED).count();
    }

    private static ConversationListingSummary summary(String conversationId) {
        return new ConversationListingSummary(conversationId, null, null, null, null, null, 0);
    }

    /** A store whose recent conversations are {@code ids}. */
    private static IHealthTurnStore storeWith(List<String> ids) {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        doAnswer(inv -> {
            int limit = inv.getArgument(1);
            BiConsumer<String, String> consumer = inv.getArgument(2);
            ids.stream().limit(limit).forEach(id -> consumer.accept(id, "agent"));
            return null;
        }).when(store).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        when(store.deleteConversationReturningDays(anyString())).thenReturn(new HealthDeletedRows(2L, Map.of("agent", Set.of(DAY))));
        return store;
    }

    @Test
    @DisplayName("the sweep deletes the days before today minus the retention")
    void sweep() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        retention(store, HealthSettings.defaults()).sweep();
        verify(store).deleteOlderThan(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a failing sweep is logged, never thrown")
    void failure() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        when(store.deleteOlderThan(any())).thenThrow(new HealthStoreException("down", null));
        assertDoesNotThrow(() -> retention(store, HealthSettings.defaults()).sweep());
    }

    @Test
    @DisplayName("the sweep runs with collection switched off")
    void runsWhenDisabled() {
        IHealthTurnStore store = mock(IHealthTurnStore.class);
        retention(store, new HealthSettings(false, 10, Duration.ofDays(7))).sweep();
        verify(store).deleteOlderThan(LocalDate.of(2026, 11, 3));
    }

    @Test
    @DisplayName("defaults: on, a 10,000 queue, 90 days, a 48 h / 50,000 orphan sweep; nonsense is bounded")
    void settings() {
        HealthSettings defaults = HealthSettings.defaults();
        assertTrue(defaults.enabled());
        assertEquals(10_000, defaults.queueCapacity());
        assertEquals(90, defaults.factsRetentionDays());
        assertEquals(Duration.ofHours(48), defaults.orphanSweepWindow());
        assertEquals(50_000, defaults.orphanSweepMax());

        HealthSettings odd = new HealthSettings(false, 0, Duration.ofHours(1));
        assertFalse(odd.enabled());
        assertEquals(1, odd.queueCapacity());
        assertEquals(1, odd.factsRetentionDays(), "partial days round up");
        assertEquals(90, new HealthSettings(true, 5, Duration.ZERO).factsRetentionDays());
        assertEquals(90, new HealthSettings(true, 5, null).factsRetentionDays());
        assertEquals(2, new HealthSettings(true, 5, Duration.ofHours(25)).factsRetentionDays());

        HealthSettings sweep = new HealthSettings(true, 5, Duration.ofDays(1), Duration.ofHours(-1), 0);
        assertEquals(Duration.ofHours(48), sweep.orphanSweepWindow());
        assertEquals(1, sweep.orphanSweepMax());
        assertEquals(Duration.ofHours(48), new HealthSettings(true, 5, Duration.ofDays(1), null, 7).orphanSweepWindow());
        assertEquals(Duration.ofHours(6), new HealthSettings(true, 5, Duration.ofDays(1), Duration.ofHours(6), 7).orphanSweepWindow());
    }

    @Nested
    @DisplayName("the orphan sweep")
    class OrphanSweep {

        @Test
        @DisplayName("rows of conversations that no longer exist are deleted and counted; existing ones are kept")
        void deletesOrphans() throws Exception {
            IHealthTurnStore store = storeWith(List.of("live", "gone-1", "gone-2"));
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of("live", summary("live")));

            long removed = retention(store, conversations, HealthSettings.defaults(), CLOCK).removeOrphans();

            assertEquals(4L, removed);
            assertEquals(4.0, orphaned());
            verify(store).deleteConversationReturningDays("gone-1");
            verify(store).deleteConversationReturningDays("gone-2");
            verify(store, never()).deleteConversationReturningDays("live");
            verify(store).forEachConversationAgentUpdatedSince(eq(Instant.parse("2026-11-08T00:30:00Z")), eq(50_001), any());
        }

        @Test
        @DisplayName("a conversation store that cannot answer deletes nothing — never on uncertainty — and the next batch still runs")
        void unknownIsKept() throws Exception {
            List<String> ids = IntStream.range(0, HealthTurnRetention.BATCH + 1).mapToObj(i -> "gone-" + i).toList();
            IHealthTurnStore store = storeWith(ids);
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenThrow(new IResourceStore.ResourceStoreException("down"))
                    .thenThrow(new IllegalStateException("still down"));

            assertEquals(0L, retention(store, conversations, HealthSettings.defaults(), CLOCK).removeOrphans());

            verify(conversations, times(2)).loadListingSummaries(any());
            verify(store, never()).deleteConversationReturningDays(anyString());
            assertEquals(0.0, orphaned());
        }

        @Test
        @DisplayName("a null answer is no answer: nothing is deleted")
        void nullAnswerIsKept() throws Exception {
            IHealthTurnStore store = storeWith(List.of("gone"));
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(null);

            assertEquals(0L, retention(store, conversations, HealthSettings.defaults(), CLOCK).removeOrphans());
            verify(store, never()).deleteConversationReturningDays(anyString());
        }

        @Test
        @DisplayName("at most eddi.health.orphan-sweep-max conversations are checked, in batches")
        void capRespected() throws Exception {
            List<String> ids = IntStream.range(0, 1_200).mapToObj(i -> "gone-" + i).toList();
            IHealthTurnStore store = storeWith(ids);
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            List<Integer> batchSizes = new ArrayList<>();
            when(conversations.loadListingSummaries(any())).thenAnswer(inv -> {
                batchSizes.add(((Collection<?>) inv.getArgument(0)).size());
                return Map.of();
            });
            HealthSettings settings = new HealthSettings(true, 10, Duration.ofDays(90), Duration.ofHours(48), 1_000);

            assertEquals(2_000L, retention(store, conversations, settings, CLOCK).removeOrphans());

            verify(store).forEachConversationAgentUpdatedSince(any(), eq(1_001), any());
            assertEquals(List.of(500, 500), batchSizes);
            verify(store, times(1_000)).deleteConversationReturningDays(anyString());
            verify(store, never()).deleteConversationReturningDays("gone-1000");
        }

        @Test
        @DisplayName("a failing read or delete is logged, never thrown; the other orphans are still removed")
        void failuresContained() throws Exception {
            IHealthTurnStore failingRead = mock(IHealthTurnStore.class);
            doThrow(new HealthStoreException("down", null)).when(failingRead).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of());
            assertEquals(0L, retention(failingRead, conversations, HealthSettings.defaults(), CLOCK).removeOrphans());

            IHealthTurnStore store = storeWith(List.of("gone-1", "gone-2"));
            when(store.deleteConversationReturningDays("gone-1")).thenThrow(new HealthStoreException("down", null));
            assertEquals(2L, retention(store, conversations, HealthSettings.defaults(), CLOCK).removeOrphans());
            verify(store).deleteConversationReturningDays("gone-2");
        }

        @Test
        @DisplayName("no conversation store: the sweep checks nothing")
        void noConversationStore() {
            IHealthTurnStore store = mock(IHealthTurnStore.class);
            assertEquals(0L, retention(store, HealthSettings.defaults()).removeOrphans());
            verify(store, never()).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
        }

        @Test
        @DisplayName("the daily sweep runs retention, then the orphan sweep — even when retention fails")
        void dailyRunsBoth() throws Exception {
            IHealthTurnStore store = storeWith(List.of("gone"));
            when(store.deleteOlderThan(any())).thenThrow(new HealthStoreException("down", null));
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of());

            retention(store, conversations, HealthSettings.defaults(), CLOCK).sweep();

            verify(store).deleteConversationReturningDays("gone");
        }

        @SuppressWarnings("unchecked")
        private HealthDirtyDays dirtyDays(HealthTurnRetention retention) {
            HealthDirtyDays dirty = mock(HealthDirtyDays.class);
            Instance<HealthDirtyDays> instance = mock(Instance.class);
            when(instance.isResolvable()).thenReturn(true);
            when(instance.get()).thenReturn(dirty);
            retention.dirtyDays = instance;
            return dirty;
        }

        @Test
        @DisplayName("an orphan's deleted rows and its agents from the candidate read go to the dirty marks")
        void orphanMarksDays() throws Exception {
            IHealthTurnStore store = storeWith(List.of("gone"));
            HealthDeletedRows unknown = new HealthDeletedRows(2L, null);
            when(store.deleteConversationReturningDays("gone")).thenReturn(unknown);
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of());
            HealthTurnRetention retention = retention(store, conversations, HealthSettings.defaults(), CLOCK);
            HealthDirtyDays dirty = dirtyDays(retention);

            assertEquals(2L, retention.removeOrphans());
            verify(dirty).markDeletedRows("gone", Set.of("agent"), unknown);
        }

        @Test
        @DisplayName("an orphan whose rows name no agent is never deleted blind: it waits for the next sweep")
        void orphanWithoutAgentIsKept() throws Exception {
            IHealthTurnStore store = mock(IHealthTurnStore.class);
            doAnswer(inv -> {
                BiConsumer<String, String> consumer = inv.getArgument(2);
                consumer.accept("no-agent", null);
                consumer.accept("two-agents", "a1");
                consumer.accept("two-agents", "a2");
                consumer.accept("two-agents", null);
                return null;
            }).when(store).forEachConversationAgentUpdatedSince(any(), anyInt(), any());
            HealthDeletedRows deleted = new HealthDeletedRows(3L, Map.of());
            when(store.deleteConversationReturningDays("two-agents")).thenReturn(deleted);
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of());
            HealthTurnRetention retention = retention(store, conversations, HealthSettings.defaults(), CLOCK);
            HealthDirtyDays dirty = dirtyDays(retention);

            assertEquals(3L, retention.removeOrphans());
            verify(store, never()).deleteConversationReturningDays("no-agent");
            verify(dirty).markDeletedRows("two-agents", Set.of("a1", "a2"), deleted);
        }
    }

    /**
     * The race the post-write check cannot close on its own: a row queued on a
     * node's health worker lands after its conversation was deleted, and the
     * worker's clean-up of it fails. The next daily sweep removes it.
     */
    @Nested
    @DisplayName("a delayed write whose clean-up fails")
    class DelayedWriteRace {

        @Test
        @DisplayName("the row survives the write's own clean-up, and the next sweep removes it")
        void sweepRemovesTheSurvivor() throws Exception {
            InMemoryHealthTurnStore store = new InMemoryHealthTurnStore();
            Map<String, Boolean> stored = new ConcurrentHashMap<>(Map.of("conv-1", true));
            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.getStateAndRevision(anyString()))
                    .thenAnswer(inv -> stored.containsKey(inv.<String>getArgument(0))
                            ? new StateAndRevision(null, 1L)
                            : new StateAndRevision(null, null));
            when(conversations.loadListingSummaries(any())).thenAnswer(inv -> {
                Map<String, ConversationListingSummary> found = new HashMap<>();
                for (Object id : (Collection<?>) inv.getArgument(0)) {
                    if (stored.containsKey(id.toString())) {
                        found.put(id.toString(), summary(id.toString()));
                    }
                }
                return found;
            });

            @SuppressWarnings("unchecked")
            Instance<IHealthTurnStore> storeInstance = mock(Instance.class);
            when(storeInstance.get()).thenReturn(store);
            Clock writeClock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
            HealthTurnRecorder recorder = new HealthTurnRecorder(storeInstance, instance(conversations), new HealthErasureFence(),
                    HealthSettings.defaults(), new HealthMetrics(registry), writeClock);
            try {
                // The worker is held up, so the turn's row waits in the queue.
                store.holdWrites();
                recorder.record(new ConversationMemory("conv-1", "agent-1", 2, "user-1"), null, null, null, false);
                assertTrue(store.writing.await(5, TimeUnit.SECONDS));

                // The purge: the conversation goes first, then its rows (none yet).
                stored.remove("conv-1");
                store.deleteByConversationId("conv-1");
                // The worker's own clean-up will fail.
                store.failDeletes.set(1);
                store.releaseWrites();

                assertTrueEventually(() -> store.failDeletes.get() == 0);
            } finally {
                recorder.shutdown();
            }
            assertEquals(1, store.rows("conv-1"), "the post-write clean-up failed, so the row is still there");

            Clock nextDay = Clock.fixed(Instant.parse("2026-11-11T00:15:00Z"), ZoneOffset.UTC);
            long removed = retention(store, conversations, HealthSettings.defaults(), nextDay).removeOrphans();

            assertEquals(1L, removed);
            assertEquals(0, store.rows("conv-1"));
            assertEquals(1.0, orphaned());
        }
    }

    /**
     * A purge that could read neither the agent nor the days keeps the rows; the
     * next orphan sweep deletes them with the agent its candidate read names, and
     * re-rolls that agent's retained days.
     */
    @Nested
    @DisplayName("rows a purge had to keep")
    class KeptByThePurge {

        @Test
        @DisplayName("are removed by the next orphan sweep, which marks the agent's retained days dirty")
        @SuppressWarnings("unchecked")
        void sweepRemovesAndMarks() throws Exception {
            InMemoryHealthTurnStore store = new InMemoryHealthTurnStore();
            store.upsert(new HealthTurn("conv-1", 0, "e0", "agent-1", 1, null, "user-1", "api", LocalDate.of(2026, 11, 9),
                    Instant.parse("2026-11-09T12:00:00Z"), Instant.parse("2026-11-09T12:00:00Z"), 0.1, null, 1L, 1L, 2L, 10L, null, false,
                    List.of(), false, 0, 0, 0, 0, true, true, false, List.of(), false));
            store.failDayReads = true;

            // The purge: the agent lookup failed, and so does the day read — nothing is
            // deleted.
            assertEquals(null, store.deleteConversationIfDaysKnown("conv-1"));
            assertEquals(1, store.rows("conv-1"));

            IConversationMemoryStore conversations = mock(IConversationMemoryStore.class);
            when(conversations.loadListingSummaries(any())).thenReturn(Map.of());
            IHealthDirtyDayStore dirtyStore = mock(IHealthDirtyDayStore.class);
            Instance<IHealthDirtyDayStore> dirtyStoreInstance = mock(Instance.class);
            when(dirtyStoreInstance.get()).thenReturn(dirtyStore);
            HealthDirtyDays dirty = new HealthDirtyDays(dirtyStoreInstance, HealthSettings.defaults(), CLOCK);
            Instance<HealthDirtyDays> dirtyInstance = mock(Instance.class);
            when(dirtyInstance.isResolvable()).thenReturn(true);
            when(dirtyInstance.get()).thenReturn(dirty);
            HealthTurnRetention retention = retention(store, conversations, HealthSettings.defaults(), CLOCK);
            retention.dirtyDays = dirtyInstance;

            assertEquals(1L, retention.removeOrphans());
            assertEquals(0, store.rows("conv-1"));
            ArgumentCaptor<Collection<LocalDate>> days = ArgumentCaptor.forClass(Collection.class);
            verify(dirtyStore).mark(eq("agent-1"), days.capture());
            assertEquals(91, days.getValue().size(), "today and the 90 retained days before it");
        }
    }

    private static void assertTrueEventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not met in time");
            Thread.sleep(10);
        }
    }

    @Nested
    @DisplayName("daily rows and change markers")
    class DailyAndMarkers {

        private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

        @Test
        @DisplayName("deleted before the daily retention: rows by day, markers by the start of that day")
        void sweeps() {
            IHealthTurnStore turns = mock(IHealthTurnStore.class);
            IHealthDailyStore daily = mock(IHealthDailyStore.class);
            IHealthMarkerStore markers = mock(IHealthMarkerStore.class);
            HealthTurnRetention retention = new HealthTurnRetention(turns, null, daily, markers, HealthSettings.defaults(),
                    new HealthMetrics(new SimpleMeterRegistry()), clock);
            assertEquals(LocalDate.of(2026, 11, 10).minusDays(730), retention.dailyCutoff());
            retention.deleteExpired();
            verify(turns).deleteOlderThan(LocalDate.of(2026, 11, 10).minusDays(90));
            verify(daily).deleteOlderThan(LocalDate.of(2024, 11, 10));
            verify(markers).deleteOlderThan(Instant.parse("2024-11-10T00:00:00Z"));
        }

        @Test
        @DisplayName("each delete is isolated: a failing one does not stop the others")
        void isolated() {
            IHealthTurnStore turns = mock(IHealthTurnStore.class);
            IHealthDailyStore daily = mock(IHealthDailyStore.class);
            IHealthMarkerStore markers = mock(IHealthMarkerStore.class);
            when(turns.deleteOlderThan(any())).thenThrow(new HealthStoreException("t", null));
            when(daily.deleteOlderThan(any())).thenThrow(new HealthStoreException("d", null));
            HealthTurnRetention retention = new HealthTurnRetention(turns, null, daily, markers, HealthSettings.defaults(),
                    new HealthMetrics(new SimpleMeterRegistry()), clock);
            assertDoesNotThrow(retention::deleteExpired);
            verify(markers).deleteOlderThan(any());
        }
    }

    /**
     * Just enough of a store for the race: rows by key, an optional hold on the
     * first write, and deletes that can be made to fail.
     */
    private static final class InMemoryHealthTurnStore implements IHealthTurnStore {

        private final Map<String, HealthTurn> rows = new LinkedHashMap<>();
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger failDeletes = new AtomicInteger();
        private volatile boolean hold;
        /** The day reads fail (a MongoDB delete then cannot tell the days). */
        private volatile boolean failDayReads;

        void holdWrites() {
            hold = true;
        }

        void releaseWrites() {
            release.countDown();
        }

        synchronized int rows(String conversationId) {
            return (int) rows.values().stream().filter(turn -> conversationId.equals(turn.conversationId())).count();
        }

        @Override
        public void upsert(HealthTurn turn, Instant lateWrittenAt) {
            if (hold) {
                writing.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this) {
                rows.put(turn.conversationId() + "|" + turn.executionId(), turn);
            }
        }

        @Override
        public synchronized void insertIfAbsent(HealthTurn turn, Instant lateWrittenAt) {
            rows.putIfAbsent(turn.conversationId() + "|" + turn.executionId(), turn);
        }

        @Override
        public long markDiscarded(HealthTurn replacing, Collection<String> executionIds, Instant lateWrittenAt) {
            return 0L;
        }

        @Override
        public List<HealthTurn> findByConversation(String conversationId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HealthTurn> findByConversationSteps(String conversationId, int fromStep, Integer toStep, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConversationActivity> conversationsUpdatedBetween(Instant since, String afterConversationId, Instant until, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HealthTurn> findByAgentAndDayRange(String agentId, LocalDate from, LocalDate to, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachByAgentAndDay(String agentId, LocalDate day, Consumer<HealthTurn> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HealthTurn> findByUserId(String userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long deleteByUserId(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long deleteByConversationId(String conversationId) {
            if (failDeletes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new HealthStoreException("transient", null);
            }
            synchronized (this) {
                int before = rows.size();
                rows.values().removeIf(turn -> conversationId.equals(turn.conversationId()));
                return before - rows.size();
            }
        }

        @Override
        public HealthDeletedRows deleteByUserIdReturningDays(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HealthDeletedRows deleteConversationReturningDays(String conversationId) {
            Map<String, Set<LocalDate>> days = failDayReads ? null : Map.of();
            return new HealthDeletedRows(deleteByConversationId(conversationId), days);
        }

        @Override
        public HealthDeletedRows deleteConversationIfDaysKnown(String conversationId) {
            return failDayReads ? null : deleteConversationReturningDays(conversationId);
        }

        @Override
        public synchronized void forEachConversationAgentUpdatedSince(Instant since, int limit, BiConsumer<String, String> consumer) {
            rows.values().stream().filter(turn -> turn.updatedAt() != null && !turn.updatedAt().isBefore(since))
                    .map(turn -> new AbstractMap.SimpleImmutableEntry<>(turn.conversationId(), turn.agentId())).distinct().limit(limit).toList()
                    .forEach(e -> consumer.accept(e.getKey(), e.getValue()));
        }

        @Override
        public synchronized void forEachConversationUpdatedSince(Instant since, int limit, Consumer<String> consumer) {
            rows.values().stream().filter(turn -> turn.updatedAt() != null && !turn.updatedAt().isBefore(since)).map(HealthTurn::conversationId)
                    .distinct().limit(limit).toList().forEach(consumer);
        }

        @Override
        public long deleteOlderThan(LocalDate day) {
            return 0L;
        }

        @Override
        public Set<LocalDate> daysOf(String conversationId, Collection<String> executionIds) {
            return Set.of();
        }

        @Override
        public HealthUsage usage(HealthUsageQuery query, LocalDate lookbackFrom) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HealthUsagePoint> dailyUsage(HealthUsageQuery query, String groupBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom,
                                                                      LocalDate to, Collection<String> interactive, Collection<String> origins) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachAgentDayUpdatedSince(Instant since, Instant until, int limit, BiConsumer<String, LocalDate> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Set<LocalDate>> agentDaysOfUser(String userId, int limit) {
            return Map.of();
        }

        @Override
        public Map<String, Set<LocalDate>> agentDaysOfConversation(String conversationId) {
            return Map.of();
        }
    }
}
