/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthDrillDownAccess;
import ai.labs.eddi.engine.health.HealthOutcomeViews;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestAgentHealth — the session drill-down and outcome suggestions")
class RestAgentHealthOutcomeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private HealthAccess access;
    private HealthReportService reports;
    private HealthDrillDownAccess drillDown;
    private HealthOutcomeViews views;
    private RestAgentHealth rest;

    @BeforeEach
    void setUp() {
        access = mock(HealthAccess.class);
        reports = mock(HealthReportService.class);
        drillDown = mock(HealthDrillDownAccess.class);
        views = mock(HealthOutcomeViews.class);
        when(reports.today()).thenReturn(TODAY);
        when(reports.effectiveOrigins(any(), any(), any())).thenReturn(List.of("api"));
        rest = new RestAgentHealth(access, reports, drillDown, views);
    }

    @Test
    @DisplayName("checks VIEW, then the review rule, then reads the page with the parsed filters")
    void sessions() {
        SessionPage page = new SessionPage(List.of(), null);
        when(drillDown.reviewableVersions("agent", 3)).thenReturn(Set.of(3));
        when(views.sessions(eq("agent"), any(), eq(Set.of(3)), eq(List.of("api")), eq(List.of("ABANDONED")), eq(List.of("silence")), eq(20),
                eq(40))).thenReturn(page);
        assertSame(page, rest.listSessions("agent", "7d", null, null, 3, null, List.of(" ABANDONED", "ABANDONED"), List.of("silence"), 20, "40"));
        verify(access).requireView("agent");
        verify(views).sessions(eq("agent"), eq(new Window(TODAY.minusDays(6), TODAY)), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("bad filters, limits and cursors are 400s naming the parameter, before any read")
    void validation() {
        assertTrue(assertThrows(BadRequestException.class,
                () -> rest.listSessions("agent", null, null, null, null, null, List.of("SOLVED"), null, null, null)).getMessage()
                .startsWith("outcome"));
        assertTrue(assertThrows(BadRequestException.class,
                () -> rest.listSessions("agent", null, null, null, null, null, null, List.of("gut_feeling"), null, null)).getMessage()
                .startsWith("evidence"));
        assertThrows(BadRequestException.class, () -> rest.listSessions("agent", null, null, null, null, null, null, null, 101, null));
        assertThrows(BadRequestException.class, () -> rest.listSessions("agent", null, null, null, null, null, null, null, 0, null));
        assertThrows(BadRequestException.class, () -> rest.listSessions("agent", null, null, null, null, null, null, null, null, "x"));
        assertThrows(BadRequestException.class, () -> rest.listSessions("agent", null, null, null, null, null, null, null, null, "-1"));
        assertThrows(BadRequestException.class, () -> rest.listSessions("agent", null, null, null, 0, null, null, null, null, null));
        verify(drillDown, never()).reviewableVersions(any(), any());
    }

    @Test
    @DisplayName("a refused review passes through as the contract's 403")
    void forbidden() {
        ForbiddenException refused = new ForbiddenException(Response.status(403).build());
        when(drillDown.reviewableVersions("agent", null)).thenThrow(refused);
        assertSame(refused, assertThrows(ForbiddenException.class,
                () -> rest.listSessions("agent", null, null, null, null, null, null, null, null, null)));
        verify(views, never()).sessions(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("suggestions need VIEW only and exclude the current version's mappings")
    void suggestions() {
        OutcomeSuggestions none = new OutcomeSuggestions(List.of());
        when(drillDown.mappedActions("agent")).thenReturn(Set.of("done"));
        when(views.suggestions(eq("agent"), any(), eq(null), eq(List.of("api")), eq(Set.of("done")))).thenReturn(none);
        assertSame(none, rest.suggestOutcomes("agent", null, null, null, null, null));
        verify(access).requireView("agent");
        verify(drillDown, never()).reviewableVersions(any(), any());
        assertEquals(0, none.items().size());
    }
}
