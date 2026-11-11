/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1.0, registry.counter("eddi.health.rollup.failures").count());
        assertEquals(600.0, registry.get("eddi.health.rollup.lag").gauge().value());
    }

    @Test
    @DisplayName("an empty queue is an empty result")
    void empty() {
        when(dirty.oldest(anyInt())).thenReturn(List.of());
        assertEquals(new HealthRollupService.Result(0, 0, 0, 0, 0), service.run());
    }
}
