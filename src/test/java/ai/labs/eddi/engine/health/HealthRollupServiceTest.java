/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import java.util.function.BiConsumer;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthRollupService — dirty days recomputed from turn rows, cleared by token")
class HealthRollupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private IHealthTurnStore turns;
    private IHealthDailyStore daily;
    private IHealthDirtyDayStore dirty;
    private SimpleMeterRegistry registry;
    private HealthRollupService service;

    @BeforeEach
    void setUp() {
        turns = mock(IHealthTurnStore.class);
        daily = mock(IHealthDailyStore.class);
        dirty = mock(IHealthDirtyDayStore.class);
        registry = new SimpleMeterRegistry();
        service = new HealthRollupService(turns, daily, dirty, HealthSettings.defaults(), new HealthMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(inv -> {
            Consumer<HealthTurn> consumer = inv.getArgument(2);
            consumer.accept(HealthAttributionTest.turn(null, List.of(), 100L, false));
            consumer.accept(HealthAttributionTest.turn("TURN_ERROR", List.of(), 100L, false));
            return null;
        }).when(turns).forEachByAgentAndDay(eq("agent"), any(), any());
    }

    @SuppressWarnings("unchecked")
    private List<HealthTurnAggregate> replaced(int times) {
        ArgumentCaptor<List<HealthTurnAggregate>> rows = ArgumentCaptor.forClass(List.class);
        verify(daily, times(times)).replaceDay(eq("agent"), eq(TODAY), rows.capture());
        return rows.getValue();
    }

    @Test
    @DisplayName("a dirty day is recomputed, its rows replaced, and the entry cleared with the token it was read with")
    void rollsUpAndClears() {
        HealthDirtyDay entry = new HealthDirtyDay("agent", TODAY, "t1", NOW.minusSeconds(60));
        when(dirty.oldest(anyInt())).thenReturn(List.of(entry), List.of());
        when(dirty.clear(entry)).thenReturn(true);

        HealthRollupService.Result result = service.run();

        assertEquals(1, result.days());
        assertEquals(1, result.rows());
        List<HealthTurnAggregate> rows = replaced(1);
        assertEquals(2, rows.get(0).turns());
        assertEquals(1, rows.get(0).agentFailedTurns());
        verify(dirty).clear(entry);
        assertEquals(1.0, registry.counter("eddi.health.rollup.days").count());
    }

    @Test
    @DisplayName("idempotent: rolling the same day twice writes the same rows")
    void idempotent() {
        assertEquals(1, service.rollUp("agent", TODAY));
        assertEquals(1, service.rollUp("agent", TODAY));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HealthTurnAggregate>> rows = ArgumentCaptor.forClass(List.class);
        verify(daily, times(2)).replaceDay(eq("agent"), eq(TODAY), rows.capture());
        assertEquals(rows.getAllValues().get(0), rows.getAllValues().get(1));
    }

    @Test
    @DisplayName("a day older than the turn retention is dropped from the queue, never recomputed")
    void tooOld() {
        HealthDirtyDay old = new HealthDirtyDay("agent", TODAY.minusDays(200), "t", NOW);
        when(dirty.oldest(anyInt())).thenReturn(List.of(old), List.of());

        HealthRollupService.Result result = service.run();

        assertEquals(1, result.skipped());
        verify(dirty).clear(old);
        verify(daily, never()).replaceDay(any(), any(), any());
    }

    @Test
    @DisplayName("a failing day keeps its entry for the next pass and is counted; the pass does not spin on it")
    void failure() {
        HealthDirtyDay entry = new HealthDirtyDay("agent", TODAY, "t1", NOW.minusSeconds(600));
        when(dirty.oldest(anyInt())).thenReturn(List.of(entry));
        when(dirty.count()).thenReturn(1L);
        doThrow(new HealthStoreException("down", null)).when(daily).replaceDay(any(), any(), any());

        HealthRollupService.Result result = service.run();

        assertEquals(1, result.failed());
        assertEquals(1, result.pending());
        verify(dirty, never()).clear(any());
        verify(dirty).defer(eq(entry), any());
        assertEquals(1.0, registry.counter("eddi.health.rollup.failures").count());
        assertEquals(600.0, registry.get("eddi.health.rollup.lag.seconds").gauge().value());
    }

    @Test
    @DisplayName("a day failing again and again is deferred every pass and never dropped, even past the attempt cap")
    void poisonKept() {
        HealthDirtyDay poison = new HealthDirtyDay("agent", TODAY, "t1", NOW.minusSeconds(60));
        when(dirty.oldest(anyInt())).thenReturn(List.of(poison));
        doThrow(new HealthStoreException("bad day", null)).when(daily).replaceDay(any(), any(), any());
        when(dirty.defer(eq(poison), any())).thenReturn(2, HealthRollupService.MAX_ATTEMPTS, HealthRollupService.MAX_ATTEMPTS + 1);

        service.run();
        service.run();
        service.run();
        verify(dirty, never()).clear(any());
        verify(dirty, times(3)).defer(eq(poison), any());
    }

    @Test
    @DisplayName("a failing clear of a day past the retention is contained")
    void guardedClear() {
        HealthDirtyDay old = new HealthDirtyDay("agent", TODAY.minusDays(200), "t", NOW);
        when(dirty.oldest(anyInt())).thenReturn(List.of(old));
        when(dirty.clear(old)).thenThrow(new HealthStoreException("down", null));
        HealthRollupService.Result result = service.run();
        assertEquals(1, result.failed());
    }

    @Test
    @DisplayName("self-heal re-marks the days of rows written since the watermark, then moves the watermark")
    void selfHeal() {
        Instant watermark = NOW.minusSeconds(3600);
        when(dirty.selfHealWatermark()).thenReturn(Optional.of(watermark));
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        doAnswer(inv -> {
            BiConsumer<String, LocalDate> consumer = inv.getArgument(3);
            consumer.accept("agent", TODAY);
            consumer.accept("agent", TODAY.minusDays(1));
            consumer.accept("other", TODAY);
            return null;
        }).when(turns).forEachAgentDayUpdatedSince(eq(watermark), any(), anyInt(), any());

        service.run();

        verify(dirty).mark(eq("agent"), eq(new TreeSet<>(Set.of(TODAY, TODAY.minusDays(1)))));
        verify(dirty).mark(eq("other"), eq(new TreeSet<>(Set.of(TODAY))));
        verify(dirty).setSelfHealWatermark(NOW.minus(HealthRollupService.SELF_HEAL_GRACE));
    }

    @Test
    @DisplayName("an overfull self-heal window is halved until it fits, and the watermark moves only past what was marked")
    void selfHealHalvesAnOverfullWindow() {
        Instant watermark = NOW.minusSeconds(3600);
        Instant end = NOW.minus(HealthRollupService.SELF_HEAL_GRACE);
        Instant half = watermark.plus(Duration.between(watermark, end).dividedBy(2));
        when(dirty.selfHealWatermark()).thenReturn(Optional.of(watermark));
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        doAnswer(inv -> {
            BiConsumer<String, LocalDate> consumer = inv.getArgument(3);
            boolean full = end.equals(inv.getArgument(1));
            int pairs = full ? HealthRollupService.SELF_HEAL_MAX + 1 : 2;
            for (int i = 0; i < pairs; i++) {
                consumer.accept("agent-" + i, TODAY);
            }
            return null;
        }).when(turns).forEachAgentDayUpdatedSince(eq(watermark), any(), anyInt(), any());

        service.run();

        verify(dirty).mark(eq("agent-0"), any());
        verify(dirty).mark(eq("agent-1"), any());
        verify(dirty, never()).mark(eq("agent-2"), any());
        verify(dirty).setSelfHealWatermark(half);
        verify(dirty, never()).setSelfHealWatermark(end);
    }

    @Test
    @DisplayName("more pairs than one scan holds, all written at the same instant: every one is marked, and a second pass marks none again")
    void selfHealNeverSkipsPastATruncatedScan() {
        Instant watermark = NOW.minusSeconds(3600);
        Instant written = watermark; // every row at one instant: no window narrower than the tie
        int total = HealthRollupService.SELF_HEAL_MAX + 500;
        Instant[] stored = {watermark};
        when(dirty.selfHealWatermark()).thenAnswer(inv -> Optional.of(stored[0]));
        doAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return null;
        }).when(dirty).setSelfHealWatermark(any());
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        doAnswer(inv -> {
            Instant since = inv.getArgument(0);
            Instant until = inv.getArgument(1);
            int limit = inv.getArgument(2);
            BiConsumer<String, LocalDate> consumer = inv.getArgument(3);
            if (!written.isBefore(since) && written.isBefore(until)) {
                for (int i = 0; i < Math.min(limit, total); i++) {
                    consumer.accept("agent-" + i, TODAY);
                }
            }
            return null;
        }).when(turns).forEachAgentDayUpdatedSince(any(), any(), anyInt(), any());
        Set<String> marked = new HashSet<>();
        int[] marks = {0};
        doAnswer(inv -> {
            marked.add(inv.getArgument(0));
            marks[0]++;
            return null;
        }).when(dirty).mark(any(), any());

        service.run();
        assertEquals(total, marked.size(), "every agent day of the window is marked, none skipped");
        assertEquals(total, marks[0], "each pair marked once");
        Instant afterFirst = stored[0];
        assertTrue(afterFirst.isAfter(written), "the watermark passed the rows it marked");

        service.run();
        assertEquals(total, marks[0], "the second pass marks nothing again");
    }

    @Test
    @DisplayName("a failing self-heal leaves the watermark and does not stop the drain")
    void selfHealFailure() {
        when(dirty.selfHealWatermark()).thenThrow(new HealthStoreException("down", null));
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        service.run();
        verify(dirty, never()).setSelfHealWatermark(any());
    }

    @Test
    @DisplayName("the backlog gauges read the shared queue, cluster-wide")
    void backlogGauges() {
        when(dirty.count()).thenReturn(3L);
        when(dirty.oldest(1)).thenReturn(List.of(new HealthDirtyDay("agent", TODAY, "t", NOW.minusSeconds(90))));
        assertEquals(3.0, registry.get("eddi.health.rollup.pending").gauge().value());
        assertEquals(90.0, registry.get("eddi.health.rollup.lag.seconds").gauge().value());
    }

    @Test
    @DisplayName("an empty queue is an empty result")
    void empty() {
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        assertEquals(new HealthRollupService.Result(0, 0, 0, 0, 0), service.run());
    }
}
