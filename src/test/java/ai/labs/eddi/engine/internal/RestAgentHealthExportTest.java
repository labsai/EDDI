/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthDrillDownAccess;
import ai.labs.eddi.engine.health.HealthExport;
import ai.labs.eddi.engine.health.HealthOutcomeViews;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthReportService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestAgentHealth — export permissions and parameters (H5)")
class RestAgentHealthExportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private HealthAccess access;
    private HealthDrillDownAccess drillDown;
    private HealthExport exports;
    private RestAgentHealth rest;

    @BeforeEach
    void setUp() {
        access = mock(HealthAccess.class);
        HealthReportService reports = mock(HealthReportService.class);
        drillDown = mock(HealthDrillDownAccess.class);
        exports = mock(HealthExport.class);
        when(reports.today()).thenReturn(TODAY);
        when(reports.effectiveOrigins(any(), any(), any())).thenReturn(List.of("api"));
        when(exports.daily(any(), any(), any(), any(), anyString())).thenReturn(out -> out.write('d'));
        when(exports.sessions(any(), any(), any(), any(), anyString())).thenReturn(out -> out.write('s'));
        rest = new RestAgentHealth(access, reports, drillDown, mock(HealthOutcomeViews.class), exports);
    }

    @Test
    @DisplayName("daily needs VIEW only — never the drill-down rule; CSV is served as an attachment")
    void dailyNeedsView() {
        Response r = rest.export("agent", "csv", null, "7d", null, null, null, null);
        assertEquals(200, r.getStatus());
        assertTrue(String.valueOf(r.getMetadata().getFirst("Content-Type")).startsWith("text/csv"));
        assertTrue(String.valueOf(r.getHeaderString("Content-Disposition")).contains("agent-health-daily-"));
        verify(access).requireView("agent");
        verify(drillDown, never()).reviewableVersions(any(), any());
        verify(exports).daily("agent", new Window(TODAY.minusDays(6), TODAY), null, List.of("api"), "csv");
    }

    @Test
    @DisplayName("sessions follow the drill-down rule: its 403 passes through, its versions narrow the export")
    void sessionsNeedTheDrillDownRule() {
        when(drillDown.reviewableVersions("agent", null)).thenReturn(Set.of(4, 5));
        rest.export("agent", null, "sessions", null, null, null, null, null);
        verify(exports).sessions("agent", new Window(TODAY.minusDays(29), TODAY), Set.of(4, 5), List.of("api"), "json");

        doThrow(new ForbiddenException("REVIEW_DISABLED")).when(drillDown).reviewableVersions("other", null);
        assertThrows(ForbiddenException.class, () -> rest.export("other", "csv", "sessions", null, null, null, null, null));
        verify(exports, never()).sessions(eq("other"), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("no VIEW, no export of any kind")
    void noView() {
        doThrow(new ForbiddenException("no")).when(access).requireView("agent");
        assertThrows(ForbiddenException.class, () -> rest.export("agent", "json", "daily", null, null, null, null, null));
        verify(exports, never()).daily(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("an unknown format or kind is a 400 naming the parameter")
    void badParameters() {
        assertTrue(assertThrows(BadRequestException.class, () -> rest.export("agent", "xml", null, null, null, null, null, null)).getMessage()
                .startsWith("format"));
        assertTrue(assertThrows(BadRequestException.class, () -> rest.export("agent", null, "weekly", null, null, null, null, null)).getMessage()
                .startsWith("kind"));
    }
}
