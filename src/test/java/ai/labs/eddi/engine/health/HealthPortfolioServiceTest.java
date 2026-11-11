/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthPortfolioService.AgentRef;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.report.HealthReports.PlatformIncident;
import ai.labs.eddi.engine.health.report.HealthReports.Portfolio;
import ai.labs.eddi.engine.health.report.HealthReports.PortfolioItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HealthPortfolioService — attention order, sessions delta, platform incidents")
class HealthPortfolioServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Window WINDOW = new Window(TODAY.minusDays(6), TODAY);

    private HealthReportService reports;
    private HealthPortfolioService service;

    @BeforeEach
    void setUp() {
        reports = mock(HealthReportService.class);
        when(reports.exposureDays(any())).thenAnswer(inv -> (double) inv.getArgument(0, Window.class).days());
        service = new HealthPortfolioService(reports);
    }

    static HealthTurnAggregate row(String agent, LocalDate day, long turns, long agentFailed, long platformAffected, Map<String, Long> classes) {
        List<Long> buckets = new ArrayList<>();
        for (int i = 0; i <= HealthTurnAggregator.DURATION_BOUNDS_MS.length; i++) {
            buckets.add(0L);
        }
        return new HealthTurnAggregate(agent, 1, "api", day, turns, turns, turns, agentFailed, Map.of(), 0, 0, classes, 0, 0.0, 0, 0, 0, 0, 0, 0,
                0, agentFailed, 0, platformAffected, 0, buckets, 4000, 0, 0);
    }

    @Test
    @DisplayName("a significantly worse agent comes first; then traffic; sessionsDelta is relative; status is null")
    void attention() {
        List<HealthTurnAggregate> rows = new ArrayList<>();
        // "worse": 1 % failures in the baseline week, 8 % now.
        rows.add(row("worse", WINDOW.baseline().from(), 1000, 10, 0, Map.of()));
        rows.add(row("worse", TODAY, 1000, 80, 0, Map.of()));
        // "busy": steady and healthy, more traffic.
        rows.add(row("busy", WINDOW.baseline().from(), 5000, 5, 0, Map.of()));
        rows.add(row("busy", TODAY, 5000, 5, 0, Map.of()));
        when(reports.dailyRows(any(), any(), any())).thenReturn(rows);
        when(reports.portfolioSessions(any(), eq(WINDOW))).thenReturn(Map.of("worse", new HealthPortfolioSessions(90, 95, 100, 105), "busy",
                new HealthPortfolioSessions(400, 400, 400, 400)));

        Portfolio p = service.portfolio(List.of(new AgentRef("busy", "Busy"), new AgentRef("worse", "Worse"), new AgentRef("idle", "Idle")), WINDOW,
                "attention", List.of(), 100, 0);

        assertEquals(List.of("worse", "busy", "idle"), p.items().stream().map(PortfolioItem::agentId).toList());
        PortfolioItem worse = p.items().get(0);
        assertNull(worse.status());
        assertEquals(-0.1, worse.sessionsDelta(), 1e-12);
        assertEquals(3, worse.keyMeasures().size());
        assertEquals("RED", worse.keyMeasures().get(0).band());
        assertNull(p.items().get(2).sessionsDelta(), "no baseline sessions, no relative change");
        assertNull(p.nextCursor());

        Portfolio byName = service.portfolio(List.of(new AgentRef("busy", "Busy"), new AgentRef("worse", "Worse"), new AgentRef("idle", "Idle")),
                WINDOW, "name", List.of(), 2, 0);
        assertEquals(List.of("busy", "idle"), byName.items().stream().map(PortfolioItem::agentId).toList());
        assertEquals("2", byName.nextCursor());
        assertTrue(service.portfolio(List.of(new AgentRef("busy", "Busy")), WINDOW, "attention", List.of("WATCH"), 100, 0).items().isEmpty(),
                "no agent has a status before H4");
    }

    @Test
    @DisplayName("a platform class hitting three agents on consecutive days is one incident; those agents are flagged")
    void incidents() {
        List<HealthTurnAggregate> rows = new ArrayList<>();
        for (String agent : List.of("a", "b", "c")) {
            rows.add(row(agent, TODAY.minusDays(2), 100, 0, 20, Map.of("RATE_LIMITED", 10L)));
            rows.add(row(agent, TODAY.minusDays(1), 100, 0, 20, Map.of("RATE_LIMITED", 10L)));
        }
        // Only two agents hit by TRANSIENT: no incident. And d is too small to count.
        rows.add(row("a", TODAY, 100, 0, 20, Map.of("TRANSIENT", 5L)));
        rows.add(row("b", TODAY, 100, 0, 20, Map.of("TRANSIENT", 5L)));
        rows.add(row("d", TODAY.minusDays(1), 5, 0, 5, Map.of("RATE_LIMITED", 5L)));
        when(reports.dailyRows(any(), any(), any())).thenReturn(rows);
        when(reports.portfolioSessions(any(), any())).thenReturn(Map.of());

        Portfolio p = service.portfolio(List.of(new AgentRef("a", "A"), new AgentRef("b", "B"), new AgentRef("c", "C"), new AgentRef("d", "D")),
                WINDOW, "name", List.of(), 100, 0);

        assertEquals(List.of(new PlatformIncident(TODAY.minusDays(2).toString(), TODAY.minusDays(1).toString(), "RATE_LIMITED", 3)),
                p.platformIncidents());
        assertTrue(p.items().get(0).platformIncident());
        assertFalse(p.items().get(3).platformIncident(), "d had too few turns to count as hit");
    }

    @Test
    @DisplayName("sessionsDelta compares with the baseline at the window's exposure")
    void deltaAtExposure() {
        when(reports.exposureDays(WINDOW)).thenReturn(3.5);
        when(reports.dailyRows(any(), any(), any())).thenReturn(List.of(row("a", TODAY, 10, 0, 0, Map.of())));
        when(reports.portfolioSessions(any(), eq(WINDOW))).thenReturn(Map.of("a", new HealthPortfolioSessions(50, 50, 100, 100)));
        Portfolio p = service.portfolio(List.of(new AgentRef("a", "A")), WINDOW, "name", List.of(), 100, 0);
        assertEquals(0.0, p.items().get(0).sessionsDelta(), 1e-12, "50 in half a week against 100 a week is no change");
    }

    @Test
    @DisplayName("sessions that cannot be counted are null with a hint, cost per session says why, and traffic sorts by turns")
    void uncounted() {
        when(reports.dailyRows(any(), any(), any()))
                .thenReturn(List.of(row("small", TODAY, 10, 0, 0, Map.of()), row("big", TODAY, 500, 0, 0, Map.of())));
        when(reports.portfolioSessions(any(), any())).thenReturn(Map.of("small", HealthPortfolioSessions.UNCOUNTED, "big",
                HealthPortfolioSessions.UNCOUNTED));
        Portfolio p = service.portfolio(List.of(new AgentRef("small", "Small"), new AgentRef("big", "Big")), WINDOW, "traffic", List.of(), 100, 0);
        assertEquals(List.of("big", "small"), p.items().stream().map(PortfolioItem::agentId).toList());
        PortfolioItem big = p.items().get(0);
        assertNull(big.sessions());
        assertNull(big.sessionsDelta());
        assertEquals(HealthMeasures.HINT_USAGE_UNAVAILABLE, big.hint());
        assertNull(big.keyMeasures().get(2).value(), "costPerSession");
        assertEquals(HealthMeasures.HINT_USAGE_UNAVAILABLE, big.keyMeasures().get(2).hint());
    }

    @Test
    @DisplayName("a counted window with an uncounted baseline has sessions but no delta")
    void baselineUncounted() {
        when(reports.dailyRows(any(), any(), any())).thenReturn(List.of(row("a", TODAY, 10, 0, 0, Map.of())));
        when(reports.portfolioSessions(any(), any())).thenReturn(Map.of("a", new HealthPortfolioSessions(7L, 7L, null, null)));
        PortfolioItem item = service.portfolio(List.of(new AgentRef("a", "A")), WINDOW, "name", List.of(), 100, 0).items().get(0);
        assertEquals(7L, item.sessions());
        assertNull(item.sessionsDelta());
        assertNull(item.hint());
    }

    @Test
    @DisplayName("an empty portfolio reads nothing and lists nothing")
    void empty() {
        Portfolio p = service.portfolio(List.of(), WINDOW, "attention", List.of(), 100, 0);
        assertTrue(p.items().isEmpty());
        assertTrue(p.platformIncidents().isEmpty());
    }
}
