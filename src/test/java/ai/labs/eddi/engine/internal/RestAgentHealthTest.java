/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthPortfolioService;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RestAgentHealth / RestHealthPortfolio — permissions and query validation")
class RestAgentHealthTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private HealthAccess access;
    private HealthReportService reports;
    private HealthPortfolioService portfolio;
    private RestAgentHealth rest;
    private RestHealthPortfolio portfolioRest;

    @BeforeEach
    void setUp() {
        access = mock(HealthAccess.class);
        reports = mock(HealthReportService.class);
        portfolio = mock(HealthPortfolioService.class);
        when(reports.today()).thenReturn(TODAY);
        when(access.requireView("agent")).thenReturn("Support");
        rest = new RestAgentHealth(access, reports);
        portfolioRest = new RestHealthPortfolio(access, reports, portfolio);
    }

    private static String message(BadRequestException e) {
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) e.getResponse().getEntity();
        return body.get("message");
    }

    @Nested
    @DisplayName("permissions")
    class Permissions {
        @Test
        @DisplayName("without VIEW the 403 of the access check comes back, before any read")
        void forbidden() {
            when(access.requireView("secret")).thenThrow(new ForbiddenException("Access denied"));
            assertThrows(ForbiddenException.class, () -> rest.readHealth("secret", null, null, null, null, null));
            assertThrows(ForbiddenException.class, () -> rest.readSeries("secret", List.of("apdex"), null, null, null, null, null, null));
            assertThrows(ForbiddenException.class, () -> rest.compareVersions("secret", 1, 2, null, null));
            verify(reports, never()).report(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("an unknown agent is the 404 of the access check")
        void notFound() {
            when(access.requireView("ghost")).thenThrow(new NotFoundException());
            assertThrows(NotFoundException.class, () -> rest.readHealth("ghost", null, null, null, null, null));
        }

        @Test
        @DisplayName("the ratings rule decides the quality section, per agent")
        void quality() {
            when(access.mayReadRatings("agent")).thenReturn(false);
            rest.readHealth("agent", null, null, null, null, null);
            verify(reports).report(any(), eq("Support"), eq(false));

            when(access.mayReadRatings("agent")).thenReturn(true);
            rest.compareVersions("agent", 7, 8, null, null);
            verify(reports).compare("agent", 7, 8, HealthReportService.DEFAULT_COMPARE_DAYS, null, true);
        }

        @Test
        @DisplayName("the portfolio lists only what the access helper says the caller may view")
        void portfolioScope() {
            var agents = List.of(new HealthPortfolioService.AgentRef("a", "A"));
            when(access.viewableAgents()).thenReturn(agents);
            portfolioRest.listAgentHealth(null, null, null, null, null);
            verify(portfolio).portfolio(eq(agents), any(), eq("attention"), eq(List.of()), eq(100), eq(0));
        }
    }

    @Nested
    @DisplayName("query validation — a 400 naming the parameter")
    class Validation {
        @Test
        @DisplayName("period defaults to 30d ending today; from/to are a range of at most 90 days")
        void period() {
            rest.readHealth("agent", null, null, null, null, null);
            ArgumentCaptor<Scope> scope = ArgumentCaptor.forClass(Scope.class);
            verify(reports).report(scope.capture(), any(), anyBoolean());
            assertEquals(TODAY.minusDays(29), scope.getValue().window().from());
            assertEquals(TODAY, scope.getValue().window().to());
            assertNull(scope.getValue().origins(), "no origin: the preset's default applies");

            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readHealth("agent", "14d", null, null, null, null)))
                    .contains("period"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readHealth("agent", null, "2026-01-01", null, null, null)))
                    .contains("from and to"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readHealth("agent", null, "2026-01-01", "2026-11-10", null, null))).contains("90 days"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readHealth("agent", null, "nope", "2026-11-10", null, null)))
                    .contains("from"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readHealth("agent", "7d", "2026-11-01", "2026-11-10", null, null))).contains("period"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readHealth("agent", null, "2026-11-01", "2026-11-12", null, null))).contains("to"));
        }

        @Test
        @DisplayName("version must be positive; origin must be a known id")
        void versionAndOrigin() {
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readHealth("agent", null, null, null, 0, null)))
                    .contains("version"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readHealth("agent", null, null, null, null, List.of("web"))))
                    .contains("origin"));
            rest.readHealth("agent", null, null, null, 3, List.of("api", "agent-test", "api"));
            ArgumentCaptor<Scope> scope = ArgumentCaptor.forClass(Scope.class);
            verify(reports).report(scope.capture(), any(), anyBoolean());
            assertEquals(List.of("api", "agent-test"), scope.getValue().origins());
            assertEquals(3, scope.getValue().version());
        }

        @Test
        @DisplayName("series: 1 to 6 known measures, groupBy none|version|origin")
        void series() {
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.readSeries("agent", null, null, null, null, null, null, null)))
                    .contains("measure"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readSeries("agent", List.of("nope"), null, null, null, null, null, null))).contains("nope"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readSeries("agent", List.of("apdex", "sessions", "fallbackRate", "tokensPerTurn", "costPerSession",
                            "distinctUsers", "ratingScore"), null, null, null, null, null, null)))
                    .contains("at most 6"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> rest.readSeries("agent", List.of("apdex"), null, null, null, null, null, "day"))).contains("groupBy"));
            rest.readSeries("agent", List.of("apdex", "apdex"), "7d", null, null, null, null, "version");
            verify(reports).series(any(), eq(List.of("apdex")), eq("version"), anyBoolean());
        }

        @Test
        @DisplayName("compare: both versions required, positive, different; days 1..90")
        void compare() {
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.compareVersions("agent", null, 2, null, null)))
                    .contains("from"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.compareVersions("agent", 1, null, null, null)))
                    .contains("to"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.compareVersions("agent", 2, 2, null, null)))
                    .contains("different"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.compareVersions("agent", 1, 2, 0, null)))
                    .contains("days"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> rest.compareVersions("agent", -1, 2, null, null)))
                    .contains("from"));
        }

        @Test
        @DisplayName("portfolio: period, sort, status, limit and cursor are checked")
        void portfolio() {
            assertTrue(message(assertThrows(BadRequestException.class, () -> portfolioRest.listAgentHealth("1d", null, null, null, null)))
                    .contains("period"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> portfolioRest.listAgentHealth(null, "size", null, null, null)))
                    .contains("sort"));
            assertTrue(message(assertThrows(BadRequestException.class,
                    () -> portfolioRest.listAgentHealth(null, null, List.of("GOOD"), null, null))).contains("status"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> portfolioRest.listAgentHealth(null, null, null, 501, null)))
                    .contains("limit"));
            assertTrue(message(assertThrows(BadRequestException.class, () -> portfolioRest.listAgentHealth(null, null, null, null, "x")))
                    .contains("cursor"));
            verifyNoInteractions(portfolio);
            portfolioRest.listAgentHealth("30d", "name", List.of("WATCH"), 10, "20");
            verify(portfolio).portfolio(any(), any(), eq("name"), eq(List.of("WATCH")), eq(10), eq(20));
        }
    }

    @Test
    @DisplayName("measures() keeps order and drops blanks")
    void measuresHelper() {
        assertEquals(List.of("apdex", "sessions"), RestAgentHealth.measures(List.of(" apdex", "", "sessions")));
        verify(access, never()).requireView(anyString());
        verify(reports, never()).compare(anyString(), anyInt(), anyInt(), anyInt(), any(), anyBoolean());
    }
}
