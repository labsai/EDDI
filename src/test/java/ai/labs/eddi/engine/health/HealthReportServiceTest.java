/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.agenttests.IAgentTestRunStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.Check;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.Marker;
import ai.labs.eddi.engine.health.report.HealthReports.Section;
import ai.labs.eddi.engine.health.report.HealthReports.Series;
import ai.labs.eddi.engine.health.report.HealthReports.SeriesPoint;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import org.junit.jupiter.api.BeforeEach;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.report.HealthReports;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthReportService — report, series and compare from the stores")
class HealthReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private IHealthTurnStore turns;
    private IHealthDailyStore daily;
    private IHealthDirtyDayStore dirty;
    private IHealthMarkerStore markers;
    private IConversationRatingStore ratings;
    private IAgentTestRunStore testRuns;
    private IDeploymentStore deployments;
    private HealthReportService service;

    @BeforeEach
    void setUp() {
        turns = mock(IHealthTurnStore.class);
        daily = mock(IHealthDailyStore.class);
        dirty = mock(IHealthDirtyDayStore.class);
        markers = mock(IHealthMarkerStore.class);
        ratings = mock(IConversationRatingStore.class);
        testRuns = mock(IAgentTestRunStore.class);
        deployments = mock(IDeploymentStore.class);
        service = new HealthReportService(turns, daily, dirty, markers, ratings, testRuns, deployments, HealthSettings.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(dirty.earliestPending(any(), any())).thenReturn(Optional.empty());
        when(turns.usage(any(), any())).thenReturn(new HealthUsage(10, 8, 40, 6, 2));
        when(ratings.summarize(any(), any())).thenReturn(List.of(RatingSummary.empty("all")));
    }

    static HealthTurnAggregate row(LocalDate day, Integer version, String origin, long turns) {
        List<Long> buckets = new ArrayList<>();
        for (int i = 0; i <= HealthTurnAggregator.DURATION_BOUNDS_MS.length; i++) {
            buckets.add(0L);
        }
        return new HealthTurnAggregate("agent", version, origin, day, turns, turns, turns, 0, Map.of(), 0, 0, Map.of(), 0, 0.0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, buckets, 4000, 0, 0);
    }

    private static Window last(int days) {
        return new Window(TODAY.minusDays(days - 1L), TODAY);
    }

    @Test
    @DisplayName("the report: interactive default origins, both periods counted, quality read only when permitted, no status yet")
    void report() {
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY, 2, "api", 50), row(TODAY, 2, "schedule", 500)));

        AgentHealth report = service.report(new Scope("agent", last(30), null, null), "Support", false);

        assertEquals("support", report.preset());
        assertEquals(HealthOrigins.INTERACTIVE, report.origins());
        assertNull(report.status());
        assertTrue(report.reasons().isEmpty());
        assertEquals(TODAY.toString(), report.dataThrough());
        assertEquals(TODAY.minusDays(59).toString(), report.baseline().from());
        assertEquals(TODAY.minusDays(30).toString(), report.baseline().to());
        assertTrue(((Section) report.sections().get("quality")).restricted());
        verify(ratings, never()).summarize(any(), any());
        // The schedule turns are outside the interactive default.
        var reliability = (Section) report.sections().get("reliability");
        assertEquals(50, reliability.measures().get(0).n());
        // Usage: current period and a baseline inside the turn retention.
        ArgumentCaptor<HealthUsageQuery> query = ArgumentCaptor.forClass(HealthUsageQuery.class);
        ArgumentCaptor<LocalDate> lookback = ArgumentCaptor.forClass(LocalDate.class);
        verify(turns, times(2)).usage(query.capture(), lookback.capture());
        // A fixed 30-day look-back before each window.
        assertEquals(List.of(TODAY.minusDays(59), TODAY.minusDays(89)), lookback.getAllValues());
        assertEquals(HealthOrigins.INTERACTIVE, query.getAllValues().get(0).origins());
    }

    @Test
    @DisplayName("an agent with only automated traffic is a task agent; a 90-day baseline is past the turn retention, so no usage there")
    void taskPresetAndRetention() {
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY, 1, "schedule", 20)));
        AgentHealth report = service.report(new Scope("agent", last(90), null, null), "Batch", true);
        assertEquals("task", report.preset());
        assertFalse(report.origins().contains(HealthOrigins.AGENT_TEST));
        assertTrue(report.origins().contains(HealthOrigins.SCHEDULE));
        verify(turns, times(1)).usage(any(), any());
        assertFalse(((Section) report.sections().get("quality")).restricted());
    }

    @Test
    @DisplayName("dataThrough is the day before the earliest day overdue for the rollup — one marked a moment ago is not behind")
    void dataThrough() {
        when(dirty.earliestPending("agent", NOW.minus(HealthSettings.defaults().rollupInterval()))).thenReturn(Optional.of(TODAY.minusDays(2)));
        assertEquals(TODAY.minusDays(3), service.dataThrough("agent"));
        when(dirty.earliestPending("agent", NOW.minus(HealthSettings.defaults().rollupInterval()))).thenReturn(Optional.empty());
        assertEquals(TODAY, service.dataThrough("agent"));
    }

    @Test
    @DisplayName("a window ending today counts the elapsed part of today; a past window its full days")
    void exposure() {
        assertEquals(6.5, service.exposureDays(last(7)), 1e-9, "12:00 UTC");
        assertEquals(7.0, service.exposureDays(new Window(TODAY.minusDays(13), TODAY.minusDays(7))), 1e-9);
    }

    @Test
    @DisplayName("repeated reads within the cache TTL hit the store once")
    void cached() {
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY, 1, "api", 10)));
        service.report(new Scope("agent", last(7), null, null), "A", false);
        service.report(new Scope("agent", last(7), null, null), "A", false);
        verify(daily, times(1)).find(eq("agent"), any(), any());
        verify(turns, times(2)).usage(any(), any());
    }

    @Test
    @DisplayName("golden suite: the newest finished run of the deployed version; ERROR runs say nothing; old runs are stale")
    void goldenSuite() throws Exception {
        DeploymentInfo info = new DeploymentInfo();
        info.setAgentId("agent");
        info.setAgentVersion(8);
        info.setEnvironment(Deployment.Environment.production);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        when(deployments.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(info));
        long recent = NOW.minusSeconds(3600).toEpochMilli();
        long old = NOW.minusSeconds(30L * 24 * 3600).toEpochMilli();
        when(testRuns.listByAgent("agent", 0, HealthReportService.TEST_RUNS_SCANNED)).thenReturn(List.of(
                run(9, AgentTestRun.Status.PASSED, recent), run(8, AgentTestRun.Status.ERROR, recent), run(8, AgentTestRun.Status.FAILED, old)));

        Check check = service.goldenSuite("agent");
        assertEquals("FAIL", check.state());
        assertEquals(8, check.version());
        assertTrue(check.stale());

        when(testRuns.listByAgent("agent", 0, HealthReportService.TEST_RUNS_SCANNED)).thenReturn(List.of());
        Check none = service.goldenSuite("agent");
        assertEquals("UNKNOWN", none.state());
        assertNull(none.at());
    }

    private static AgentTestRun run(int version, AgentTestRun.Status status, long finishedAt) {
        return new AgentTestRun("r", "s", 1, "suite", "agent", version, status, "u", finishedAt - 1000, finishedAt, 1000L, 0.0, null, false,
                List.of(), 1, 1, 0, List.of(), false, null, null, null);
    }

    @Test
    @DisplayName("series: a point per day, null for a day without data, grouped by version; markers stored and derived")
    void series() {
        Window window = last(3);
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY, 7, "api", 40), row(TODAY, 8, "api", 60)));
        when(turns.dailyUsage(any(), eq("version"))).thenReturn(List.of(new HealthUsagePoint(TODAY, "8", 5, 4, 60, 3)));
        when(markers.findForAgent(eq("agent"), any(), any(), anyInt())).thenReturn(
                List.of(new HealthMarker("m-1", "agent", TODAY.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60),
                        HealthMarker.KIND_DEPLOYMENT, "v8 deployed (production)", 8)));
        when(markers.findGlobal(eq(List.of("default", "t.team.1")), any(), any(), anyInt())).thenReturn(
                List.of(new HealthMarker("g-x", null, TODAY.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        HealthMarker.KIND_GLOBAL_VARIABLE_CHANGE, "Global variables changed", null, "t.team.1")));
        when(daily.firstDayByVersion(eq("agent"), any())).thenReturn(Map.of(7, TODAY.minusDays(40), 8, TODAY));

        HealthSeries series = service.series(new Scope("agent", window, null, null, List.of("default", "t.team.1")),
                List.of("agentFailureRate", "sessions"), "version", true);

        Series v7 = series.series().stream().filter(s -> s.measure().equals("agentFailureRate") && "7".equals(s.group())).findFirst().orElseThrow();
        assertEquals(3, v7.points().size());
        assertEquals(new SeriesPoint(TODAY.minusDays(2).toString(), null, 0), v7.points().get(0));
        assertEquals(0.0, v7.points().get(2).value());
        assertEquals(40, v7.points().get(2).n());
        Series sessions8 = series.series().stream().filter(s -> s.measure().equals("sessions") && "8".equals(s.group())).findFirst().orElseThrow();
        assertEquals(5.0, sessions8.points().get(2).value());
        assertEquals(List.of("g-x", "m-1", "vft-8"), series.markers().stream().map(Marker::id).toList());
    }

    @Test
    @DisplayName("portfolio traffic: a 90-day window is counted but its baseline is not; a window past the retention is not counted")
    void portfolioSessionsRetention() {
        when(turns.portfolioSessions(any(), any(), any(), any(), any(), any())).thenReturn(Map.of("a", new HealthPortfolioSessions(5, 6, 0, 0)));
        HealthPortfolioSessions ninety = service.portfolioSessions(List.of("a"), last(90)).get("a");
        assertEquals(5L, ninety.interactiveNow());
        assertNull(ninety.interactiveBefore());
        // Read without baseline days.
        verify(turns).portfolioSessions(any(), eq(TODAY.minusDays(89)), eq(TODAY.minusDays(89)), eq(TODAY), any(), any());

        HealthPortfolioSessions old = service.portfolioSessions(List.of("b"), new Window(TODAY.minusDays(120), TODAY.minusDays(100))).get("b");
        assertNull(old.interactiveNow());
        assertNull(old.allBefore());
    }

    @Test
    @DisplayName("series without rating permission answer null rating points")
    void seriesRestricted() {
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY, 1, "api", 40)));
        HealthSeries series = service.series(new Scope("agent", last(2), null, null), List.of("ratingScore"), "none", false);
        assertTrue(series.series().get(0).points().stream().allMatch(p -> p.value() == null && p.n() == 0));
        verify(ratings, never()).summarize(any(), any());
    }

    @Test
    @DisplayName("compare: the newer version since its first turn, the older over its last days before; a version without turns is empty")
    void compare() {
        when(daily.firstDayByVersion(eq("agent"), any())).thenReturn(Map.of(7, TODAY.minusDays(60), 8, TODAY.minusDays(5)));
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(row(TODAY.minusDays(10), 7, "api", 100), row(TODAY, 8, "api", 100)));

        HealthCompare compare = service.compare("agent", 7, 8, 14, null, false);

        assertEquals(TODAY.minusDays(5).toString(), compare.to().window().from());
        assertEquals(TODAY.toString(), compare.to().window().to());
        assertEquals(TODAY.minusDays(6).toString(), compare.from().window().to());
        assertEquals(TODAY.minusDays(19).toString(), compare.from().window().from());
        assertTrue(compare.deltas().stream().anyMatch(d -> d.measureId().equals("agentFailureRate")));

        verify(daily).firstDayByVersion("agent", HealthReportService.firstTurnOrigins(null));
        assertFalse(HealthReportService.firstTurnOrigins(null).contains(HealthOrigins.AGENT_TEST), "test runs are no first turn");
        assertEquals(List.of("api", "agent-test"), HealthReportService.firstTurnOrigins(List.of("api", "agent-test")));
        when(daily.firstDayByVersion(eq("agent"), any())).thenReturn(Map.of());
        HealthCompare unknown = service.compare("agent", 3, 4, 14, null, false);
        assertEquals(TODAY.toString(), unknown.to().window().from());
    }

    @Test
    @DisplayName("M4: while the outcome evaluation has not reached the period's end, outcome measures say so and the hint names the day")
    void outcomesStillEvaluating() {
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        when(outcomes.counts(any(), anyBoolean(), any()))
                .thenReturn(List.of(new HealthOutcomeCount(null, null, "ASSUMED_RESOLVED", "silence", true, false, 40, 80)));
        when(outcomes.watermark()).thenReturn(Optional.of(Instant.parse("2026-10-20T08:00:00Z")));
        service.withOutcomes(outcomes);
        HealthPeriods.Window window = new HealthPeriods.Window(TODAY.minusDays(6), TODAY);
        assertEquals("Outcomes are still being evaluated (through 2026-10-20)", service.outcomesPendingHint(window));
        HealthReports.AgentHealth report = service.report(new HealthReportService.Scope("agent", window, null, null), "A", false);
        HealthReports.EffectivenessSection effectiveness = (HealthReports.EffectivenessSection) report.sections().get("effectiveness");
        HealthReports.Measure resolution = effectiveness.measures().get(0);
        assertEquals(1.0, resolution.value(), "the value so far is shown");
        assertEquals("Outcomes are still being evaluated (through 2026-10-20)", resolution.hint());

        // A window that ended before the watermark is complete.
        assertEquals(null, service.outcomesPendingHint(new HealthPeriods.Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18))));
    }

    @Test
    @DisplayName("M4: caught up within the slack, or never run, are told apart")
    void outcomesCaughtUp() {
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        when(outcomes.watermark()).thenReturn(Optional.of(NOW.minusSeconds(120)));
        service.withOutcomes(outcomes);
        assertEquals(null, service.outcomesPendingHint(new HealthPeriods.Window(TODAY.minusDays(6), TODAY)));
        IHealthOutcomeStore never = mock(IHealthOutcomeStore.class);
        when(never.watermark()).thenReturn(Optional.empty());
        HealthReportService fresh = new HealthReportService(turns, daily, dirty, markers, ratings, testRuns, deployments, HealthSettings.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC)).withOutcomes(never);
        assertEquals("Outcomes are still being evaluated", fresh.outcomesPendingHint(new HealthPeriods.Window(TODAY.minusDays(6), TODAY)));
    }
}
