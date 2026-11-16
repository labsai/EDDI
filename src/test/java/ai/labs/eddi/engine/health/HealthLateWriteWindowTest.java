/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two windows in which a late row's dirty mark used to be lost, replayed
 * against in-memory stores with the real recorder, dirty-day queue and rollup:
 * a rollup interleaved with the write, then the node stopping. In each, a
 * second rollup — another node, later — must count the row.
 * <p>
 * The stores follow the {@link IHealthTurnStore} and
 * {@link IHealthDirtyDayStore} contracts (guarded replace, first-recorded day,
 * sticky discard, the late-write stamp; token-checked clear); the real stores'
 * statements are checked by their own tests and contract ITs.
 */
@DisplayName("Late Agent Health rows survive a rollup during the write and a node stopping after it")
class HealthLateWriteWindowTest {

    private static final Instant CAPTURED = Instant.parse("2026-11-10T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final LocalDate YESTERDAY = DAY.minusDays(1);
    /** The late row reaches the store five minutes after its capture. */
    private static final Instant WRITTEN = CAPTURED.plus(Duration.ofMinutes(5));

    /** The node dies: thrown past the recorder's own error handling. */
    static final class NodeStopped extends Error {
        NodeStopped() {
            super("node stopped", null, false, false);
        }
    }

    private final MutableClock clock = new MutableClock(CAPTURED);
    private FakeTurnStore turns;
    private FakeDirtyStore dirtyStore;
    private final Map<LocalDate, List<HealthTurnAggregate>> daily = new HashMap<>();
    private IHealthDailyStore dailyStore;
    private HealthDirtyDays dirtyDays;
    private HealthTurnRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        turns = new FakeTurnStore();
        dirtyStore = new FakeDirtyStore();
        dailyStore = mock(IHealthDailyStore.class);
        doAnswer(inv -> {
            daily.put(inv.getArgument(1), List.copyOf(inv.<List<HealthTurnAggregate>>getArgument(2)));
            return null;
        }).when(dailyStore).replaceDay(any(), any(), any());
        Instance<IHealthDirtyDayStore> dirtyInstance = mock(Instance.class);
        when(dirtyInstance.get()).thenReturn(dirtyStore);
        Instance<IHealthTurnStore> turnInstance = mock(Instance.class);
        when(turnInstance.get()).thenReturn(turns);
        dirtyDays = new HealthDirtyDays(dirtyInstance, HealthSettings.defaults(), clock);
        recorder = new HealthTurnRecorder(turnInstance, HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()), dirtyDays, clock);
    }

    @AfterEach
    void tearDown() {
        recorder.shutdown();
    }

    /** A rollup pass on whichever node runs the job, at the clock's time. */
    private HealthRollupService.Result rollup() {
        return new HealthRollupService(turns, dailyStore, dirtyStore, HealthSettings.defaults(), new HealthMetrics(new SimpleMeterRegistry()),
                clock).run();
    }

    private long countedTurns(LocalDate day) {
        return daily.getOrDefault(day, List.of()).stream().mapToLong(HealthTurnAggregate::turns).sum();
    }

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-1", "agent-1", 2, "user-1");
    }

    /** The turn's capture, taken on the turn's thread at {@link #CAPTURED}. */
    private static HealthTurnFactory.Capture capture(ConversationMemory memory) {
        return HealthTurnFactory.capture(memory, null, null, 100L, CAPTURED, false, true);
    }

    /** An on-time row of the same agent and day, already rolled up. */
    private void earlierRowRolledUp(LocalDate day, String conversation, String execution, Instant at) {
        clock.set(at);
        turns.upsert(new HealthTurn(conversation, 0, execution, "agent-1", 2, null, "user-1", "api", day, at, at, null, null, null, null, null,
                10L, null, false, List.of(), false, 0, 0, 0, 0, true, true, false, List.of(), false));
        dirtyStore.mark("agent-1", List.of(day));
        clock.set(at.plus(Duration.ofMinutes(2)));
        rollup();
        assertEquals(1, countedTurns(day), "the earlier row is counted");
    }

    @Test
    @DisplayName("window 1: a rollup recomputes the day while the late row is being written, then the node stops before any mark — "
            + "the next rollup still counts the row")
    void rollupDuringTheWriteThenTheNodeStops() {
        earlierRowRolledUp(DAY, "conv-0", "e0", CAPTURED.minus(Duration.ofMinutes(30)));
        dirtyStore.mark("agent-1", List.of(DAY));
        clock.set(WRITTEN);
        // Interleaved: the rollup runs just before the row lands. It clears the day's
        // entry having counted it without the row, and its self-heal watermark moves
        // past the row's capture time.
        turns.beforeWrite = () -> {
            rollup();
            assertEquals(1, countedTurns(DAY), "rolled up without the late row");
            assertTrue(dirtyStore.selfHealWatermark().orElseThrow().isAfter(CAPTURED));
        };
        // The node stops the moment the row is stored: nothing after the write runs,
        // so no mark, durable or in memory, is ever made for it.
        turns.afterUpsert = () -> {
            throw new NodeStopped();
        };

        assertThrows(NodeStopped.class, () -> recorder.write(capture(memory())));
        assertEquals(0, dirtyStore.count(), "no dirty entry left for the day");

        // Another node's rollup, a few minutes on.
        turns.beforeWrite = () -> {
        };
        clock.set(WRITTEN.plus(Duration.ofMinutes(3)));
        rollup();

        assertEquals(2, countedTurns(DAY), "the late row is counted");
    }

    @Test
    @DisplayName("window 2: every durable mark fails and the node stops with the mark only in memory — the next rollup still counts the row")
    void everyMarkFailsThenTheNodeStops() {
        earlierRowRolledUp(DAY, "conv-0", "e0", CAPTURED.minus(Duration.ofMinutes(30)));
        clock.set(WRITTEN);
        // A rollup during the outage: nothing to mark, so its self-heal watermark
        // moves past the row's capture time all the same.
        rollup();
        assertTrue(dirtyStore.selfHealWatermark().orElseThrow().isAfter(CAPTURED));
        dirtyStore.failMarks = true;

        recorder.write(capture(memory()));
        dirtyDays.flush();
        dirtyDays.flush();
        assertEquals(1, dirtyDays.pendingCount(), "the day's mark exists only in this node's memory");
        assertEquals(0, dirtyStore.count());
        // The node stops: its HealthDirtyDays, and the mark, are gone.

        dirtyStore.failMarks = false;
        clock.set(WRITTEN.plus(Duration.ofMinutes(3)));
        rollup();

        assertEquals(2, countedTurns(DAY), "the late row is counted");
    }

    @Test
    @DisplayName("window 1, undo: a late turn discards yesterday's execution and the node stops after the discard — yesterday is re-rolled "
            + "without it")
    void lateDiscardThenTheNodeStops() {
        earlierRowRolledUp(YESTERDAY, "conv-1", "old-exec", CAPTURED.minus(Duration.ofHours(20)));
        ConversationMemory memory = memory();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.HEALTH_DISCARDED_EXECUTIONS, List.of("old-exec")));
        clock.set(WRITTEN);
        turns.beforeWrite = () -> {
            rollup();
            assertTrue(dirtyStore.selfHealWatermark().orElseThrow().isAfter(CAPTURED));
        };
        turns.afterDiscard = () -> {
            throw new NodeStopped();
        };

        assertThrows(NodeStopped.class, () -> recorder.write(capture(memory)));
        assertTrue(turns.discarded("conv-1", "old-exec"));
        assertEquals(1, countedTurns(YESTERDAY), "still counted until re-rolled");

        turns.beforeWrite = () -> {
        };
        clock.set(WRITTEN.plus(Duration.ofMinutes(3)));
        rollup();

        assertEquals(0, countedTurns(YESTERDAY), "the discarded execution is no longer counted");
        assertEquals(1, countedTurns(DAY), "the late row is counted");
    }

    @Test
    @DisplayName("an on-time row carries no stamp: it is found by its capture time, and its index entry is not paid for")
    void onTimeRowsCarryNoStamp() {
        clock.set(CAPTURED.plusSeconds(5));
        recorder.write(capture(memory()));
        assertEquals(Optional.empty(), turns.stamp("conv-1", turns.only().executionId()));
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

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
            return now;
        }
    }

    /**
     * {@link IHealthTurnStore} in memory, for what the recorder and the rollup use.
     */
    static final class FakeTurnStore implements IHealthTurnStore {

        private static final class Row {
            HealthTurn turn;
            Instant writtenLate;
            Instant discardedAt;
            String markerAgent;
        }

        private final Map<String, Row> rows = new LinkedHashMap<>();
        Runnable beforeWrite = () -> {
        };
        Runnable afterUpsert = () -> {
        };
        Runnable afterDiscard = () -> {
        };

        private static String key(String conversationId, String executionId) {
            return conversationId + "|" + executionId;
        }

        HealthTurn only() {
            List<HealthTurn> recorded = rows.values().stream().map(r -> r.turn).filter(t -> t != null && t.recorded()).toList();
            assertEquals(1, recorded.size(), recorded.toString());
            return recorded.get(0);
        }

        Optional<Instant> stamp(String conversationId, String executionId) {
            return Optional.ofNullable(rows.get(key(conversationId, executionId)).writtenLate);
        }

        boolean discarded(String conversationId, String executionId) {
            return rows.get(key(conversationId, executionId)).turn.discarded();
        }

        @Override
        public void upsert(HealthTurn turn, Instant lateWrittenAt) {
            beforeWrite.run();
            Row row = rows.computeIfAbsent(key(turn.conversationId(), turn.executionId()), k -> new Row());
            HealthTurn stored = row.turn;
            if (stored != null && stored.updatedAt() != null && stored.updatedAt().isAfter(turn.updatedAt())) {
                return; // stale: ignored
            }
            boolean discarded = turn.discarded() || (stored != null && stored.discarded());
            LocalDate day = stored != null && stored.day() != null ? stored.day() : turn.day();
            Instant timestamp = stored != null && stored.timestamp() != null ? stored.timestamp() : turn.timestamp();
            row.turn = new HealthTurn(turn.conversationId(), turn.stepIndex(), turn.executionId(), turn.agentId(), turn.agentVersion(),
                    turn.previousAgentVersion(), turn.userId(), turn.origin(), day, timestamp, turn.updatedAt(), turn.costUsd(),
                    turn.discardedCostUsd(),
                    turn.inputTokens(), turn.outputTokens(), turn.totalTokens(), turn.durationMs(), turn.errorKind(), turn.paused(), turn.failures(),
                    turn.fallbackServed(), turn.toolCalls(), turn.toolFailures(), turn.toolRefusals(), turn.guardrailTrips(), turn.userInput(),
                    turn.agentOutput(), turn.lastOutputWasQuestion(), turn.actions(), discarded);
            row.writtenLate = lateWrittenAt;
            afterUpsert.run();
        }

        @Override
        public void insertIfAbsent(HealthTurn turn, Instant lateWrittenAt) {
            beforeWrite.run();
            Row row = rows.computeIfAbsent(key(turn.conversationId(), turn.executionId()), k -> new Row());
            if (row.turn == null) {
                row.turn = turn;
                row.writtenLate = lateWrittenAt;
            }
            afterUpsert.run();
        }

        @Override
        public long markDiscarded(HealthTurn replacing, Collection<String> executionIds, Instant lateWrittenAt) {
            Instant at = lateWrittenAt != null ? lateWrittenAt : replacing.updatedAt();
            for (String executionId : new LinkedHashSet<>(executionIds)) {
                Row row = rows.computeIfAbsent(key(replacing.conversationId(), executionId), k -> new Row());
                if (row.turn == null) {
                    row.markerAgent = replacing.agentId();
                    row.discardedAt = at;
                } else if (!row.turn.discarded()) {
                    row.turn = row.turn.asDiscarded();
                    row.discardedAt = at;
                }
            }
            afterDiscard.run();
            return executionIds.size();
        }

        @Override
        public void forEachByAgentAndDay(String agentId, LocalDate day, Consumer<HealthTurn> consumer) {
            List.copyOf(rows.values()).stream().map(r -> r.turn).filter(t -> t != null && agentId.equals(t.agentId()) && day.equals(t.day()))
                    .forEach(consumer);
        }

        @Override
        public Set<LocalDate> daysOf(String conversationId, Collection<String> executionIds) {
            Set<LocalDate> days = new LinkedHashSet<>();
            for (String executionId : executionIds) {
                Row row = rows.get(key(conversationId, executionId));
                if (row != null && row.turn != null && row.turn.day() != null) {
                    days.add(row.turn.day());
                }
            }
            return days;
        }

        private static boolean in(Instant at, Instant since, Instant until) {
            return at != null && !at.isBefore(since) && at.isBefore(until);
        }

        @Override
        public void forEachAgentDayUpdatedSince(Instant since, Instant until, int limit, BiConsumer<String, LocalDate> consumer) {
            Set<String> seen = new LinkedHashSet<>();
            for (Row row : List.copyOf(rows.values())) {
                HealthTurn t = row.turn;
                if (t == null || t.day() == null || t.agentId() == null) {
                    continue;
                }
                if ((in(t.updatedAt(), since, until) || in(row.discardedAt, since, until) || in(row.writtenLate, since, until))
                        && seen.add(t.agentId() + "|" + t.day()) && seen.size() <= limit) {
                    consumer.accept(t.agentId(), t.day());
                }
            }
        }

        @Override
        public List<HealthTurn> findByConversation(String conversationId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HealthTurn> findByAgentAndDayRange(String agentId, LocalDate from, LocalDate to, int limit) {
            throw new UnsupportedOperationException();
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
        public Map<String, Set<LocalDate>> agentDaysOfUser(String userId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Set<LocalDate>> agentDaysOfConversation(String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HealthDeletedRows deleteConversationReturningDays(String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HealthDeletedRows deleteConversationIfDaysKnown(String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachConversationAgentUpdatedSince(Instant since, int limit, BiConsumer<String, String> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HealthDeletedRows deleteByUserIdReturningDays(String userId) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachConversationUpdatedSince(Instant since, int limit, Consumer<String> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long deleteOlderThan(LocalDate day) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * {@link IHealthDirtyDayStore} in memory: one entry per (agent, day), cleared
     * by token.
     */
    static final class FakeDirtyStore implements IHealthDirtyDayStore {

        private final Map<String, HealthDirtyDay> entries = new LinkedHashMap<>();
        private final AtomicInteger tokens = new AtomicInteger();
        private Instant watermark;
        volatile boolean failMarks;
        private Instant markedAt = Instant.EPOCH;

        @Override
        public synchronized void mark(String agentId, Collection<LocalDate> days) {
            if (failMarks) {
                throw new HealthStoreException("dirty store down", null);
            }
            for (LocalDate day : days) {
                // Strictly increasing, so the queue order is the marking order.
                markedAt = markedAt.plusMillis(1);
                entries.put(agentId + "|" + day, new HealthDirtyDay(agentId, day, "t" + tokens.incrementAndGet(), markedAt));
            }
        }

        @Override
        public synchronized List<HealthDirtyDay> oldest(int limit) {
            return entries.values().stream().sorted(Comparator.comparing(HealthDirtyDay::markedAt)).limit(limit).toList();
        }

        @Override
        public synchronized boolean clear(HealthDirtyDay entry) {
            String key = entry.agentId() + "|" + entry.day();
            HealthDirtyDay current = entries.get(key);
            if (current != null && current.token().equals(entry.token())) {
                entries.remove(key);
                return true;
            }
            return false;
        }

        @Override
        public synchronized int defer(HealthDirtyDay entry, Instant now) {
            String key = entry.agentId() + "|" + entry.day();
            HealthDirtyDay current = entries.get(key);
            if (current == null || !current.token().equals(entry.token())) {
                return 0;
            }
            HealthDirtyDay deferred = new HealthDirtyDay(entry.agentId(), entry.day(), entry.token(), now, current.attempts() + 1);
            entries.put(key, deferred);
            return deferred.attempts();
        }

        @Override
        public synchronized Optional<LocalDate> earliestPending(String agentId, Instant markedBefore) {
            return entries.values().stream().filter(e -> e.agentId().equals(agentId) && e.markedAt().isBefore(markedBefore))
                    .map(HealthDirtyDay::day).min(Comparator.naturalOrder());
        }

        @Override
        public synchronized Optional<Instant> selfHealWatermark() {
            return Optional.ofNullable(watermark);
        }

        @Override
        public synchronized void setSelfHealWatermark(Instant at) {
            watermark = at;
        }

        @Override
        public synchronized long count() {
            return entries.size();
        }
    }
}
