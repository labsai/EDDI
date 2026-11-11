/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.RatingStats;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.CompareDelta;
import ai.labs.eddi.engine.health.report.HealthReports.EffectivenessSection;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthMeasures — values, bands, intervals and significance from what a period holds")
class HealthMeasuresTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    /** A daily row with the counters the measures read. */
    static HealthTurnAggregate row(long turns, long agentFailed, long platformAffected, long fallback, double cost, long tokens, long timed,
                                   long satisfied, long tolerating, Map<String, Long> classes) {
        List<Long> buckets = new ArrayList<>();
        for (int i = 0; i <= HealthTurnAggregator.DURATION_BOUNDS_MS.length; i++) {
            buckets.add(i == 3 ? timed : 0L);
        }
        return new HealthTurnAggregate("agent", 1, "api", DAY, turns, turns, turns, agentFailed, Map.of(), 0, fallback, classes, 0, cost, tokens,
                0, timed, 0, 0, 0, 0, agentFailed, 0, platformAffected, turns, buckets, 4000, satisfied, tolerating);
    }

    static PeriodData data(HealthTurnAggregate row, HealthUsage usage, RatingStats ratings) {
        return new PeriodData(Totals.of(row == null ? List.of() : List.of(row)), usage, ratings);
    }

    @Nested
    @DisplayName("with no data")
    class Empty {
        @Test
        @DisplayName("every turn measure is null, GREY and says why; counts are 0")
        void nothing() {
            PeriodData empty = data(null, HealthUsage.EMPTY, RatingStats.EMPTY);
            Measure failures = HealthMeasures.measure("agentFailureRate", empty, null);
            assertNull(failures.value());
            assertEquals(0, failures.n());
            assertEquals("GREY", failures.band());
            assertEquals(HealthMeasures.HINT_NO_DATA, failures.hint());
            assertFalse(failures.significant());
            assertEquals(0.0, HealthMeasures.measure("sessions", empty, null).value());
            assertNull(HealthMeasures.measure("ratingScore", empty, null).value());
        }

        @Test
        @DisplayName("usage that cannot be counted (past the turn retention) is null with its own hint")
        void usageUnavailable() {
            Measure sessions = HealthMeasures.measure("sessions", data(null, null, null), null);
            assertNull(sessions.value());
            assertEquals(HealthMeasures.HINT_USAGE_UNAVAILABLE, sessions.hint());
        }
    }

    @Test
    @DisplayName("agentFailureRate: value, Wilson interval, band by the default thresholds, and its thresholds")
    void agentFailureRate() {
        Measure m = HealthMeasures.measure("agentFailureRate", data(row(1000, 30, 0, 0, 0, 0, 0, 0, 0, Map.of()), HealthUsage.EMPTY, null),
                null);
        assertEquals(0.03, m.value(), 1e-12);
        assertEquals(1000, m.n());
        assertTrue(m.interval().low() < 0.03 && m.interval().high() > 0.03);
        assertEquals("AMBER", m.band(), "between green 1 % and red 5 %");
        assertTrue(m.critical());
        assertNotNull(m.thresholds());
        assertFalse(m.thresholds().higherIsBetter());

        assertEquals("GREEN", HealthMeasures.measure("agentFailureRate", data(row(1000, 5, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null), null)
                .band());
        assertEquals("RED", HealthMeasures.measure("agentFailureRate", data(row(1000, 80, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null), null)
                .band());
    }

    @Test
    @DisplayName("below the minimum sample the value is shown, the band is GREY and the hint names the sample")
    void minSample() {
        Measure m = HealthMeasures.measure("agentFailureRate", data(row(10, 9, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null), null);
        assertEquals(0.9, m.value(), 1e-12);
        assertEquals("GREY", m.band());
        assertEquals("Fewer than 30 turns in this period", m.hint());
    }

    @Test
    @DisplayName("against a baseline: delta in the measure's unit and a significant change only when both periods are large enough")
    void baselineAndSignificance() {
        PeriodData now = data(row(1000, 50, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null);
        PeriodData before = data(row(1000, 10, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null);
        Measure m = HealthMeasures.measure("agentFailureRate", now, before);
        assertEquals(0.01, m.baseline(), 1e-12);
        assertEquals(0.04, m.delta(), 1e-12);
        assertTrue(m.significant());
        assertTrue(HealthMeasures.worsening(m) > 0.03);

        PeriodData smallBefore = data(row(20, 1, 0, 0, 0, 0, 0, 0, 0, Map.of()), null, null);
        assertFalse(HealthMeasures.measure("agentFailureRate", now, smallBefore).significant(), "baseline below the minimum sample");
    }

    @Test
    @DisplayName("platformFailureRate is informational, counts recovered attempts' turns, and breaks down by platform class only")
    void platformFailureRate() {
        Measure m = HealthMeasures.measure("platformFailureRate",
                data(row(200, 2, 40, 0, 0, 0, 0, 0, 0, Map.of("RATE_LIMITED", 30L, "AUTH", 2L)), null, null), null);
        assertEquals(0.2, m.value(), 1e-12);
        assertEquals("INFORMATIONAL", m.kind());
        assertNull(m.thresholds());
        assertEquals("GREY", m.band());
        assertEquals(List.of("RATE_LIMITED"), m.breakdown().stream().map(BreakdownEntry::key).toList());
        Measure agent = HealthMeasures.measure("agentFailureRate",
                data(row(200, 2, 40, 0, 0, 0, 0, 0, 0, Map.of("RATE_LIMITED", 30L, "AUTH", 2L)), null, null), null);
        assertEquals(List.of("AUTH"), agent.breakdown().stream().map(BreakdownEntry::key).toList());
    }

    @Test
    @DisplayName("apdex = (satisfied + tolerating/2) / timed, with a normal interval inside 0..1")
    void apdex() {
        Measure m = HealthMeasures.measure("apdex", data(row(100, 0, 0, 0, 0, 0, 100, 80, 10, Map.of()), null, null), null);
        assertEquals(0.85, m.value(), 1e-12);
        assertEquals(100, m.n());
        assertTrue(m.interval().low() >= 0 && m.interval().high() <= 1);
        assertEquals("GREEN", m.band());
        Measure p95 = HealthMeasures.measure("turnDurationP95", data(row(100, 0, 0, 0, 0, 0, 100, 80, 10, Map.of()), null, null), null);
        assertNotNull(p95.value());
        assertTrue(p95.label().contains("approx"));
    }

    @Test
    @DisplayName("efficiency and adoption divide by the sessions counted from the turn rows")
    void perSession() {
        PeriodData d = data(row(300, 0, 0, 0, 3.0, 30_000, 0, 0, 0, Map.of()), new HealthUsage(100, 60, 300, 80, 20), null);
        assertEquals(0.03, HealthMeasures.measure("costPerSession", d, null).value(), 1e-12);
        assertEquals(100.0, HealthMeasures.measure("tokensPerTurn", d, null).value(), 1e-12);
        assertEquals(3.0, HealthMeasures.measure("turnsPerSession", d, null).value(), 1e-12);
        assertEquals(60.0, HealthMeasures.measure("engagedSessions", d, null).value());
        assertEquals(0.25, HealthMeasures.measure("returningUserShare", d, null).value(), 1e-12);
    }

    @Test
    @DisplayName("ratingScore is the Wilson lower bound of the average normalised score; thumbs and response rate are rates")
    void quality() {
        PeriodData d = data(row(100, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()), new HealthUsage(50, 40, 100, 30, 0), new RatingStats(20, 0.8, 18, 2));
        Measure score = HealthMeasures.measure("ratingScore", d, null);
        assertEquals(HealthStatistics.wilson(16, 20).low(), score.value(), 1e-12);
        assertTrue(score.value() < 0.8, "the bound is below the plain average");
        assertEquals(0.4, HealthMeasures.measure("ratingResponseRate", d, null).value(), 1e-12);
        assertEquals(0.9, HealthMeasures.measure("replyThumbsUp", d, null).value(), 1e-12);
    }

    @Test
    @DisplayName("effectiveness: null values with the outcomes hint, coverage null, resolution with its four-part breakdown")
    void effectiveness() {
        Map<String, Object> sections = HealthMeasures.sections(data(null, HealthUsage.EMPTY, RatingStats.EMPTY), null, false);
        assertEquals(HealthDefaults.SECTIONS, List.copyOf(sections.keySet()));
        EffectivenessSection e = (EffectivenessSection) sections.get(HealthDefaults.EFFECTIVENESS);
        assertNull(e.coverage());
        for (Measure m : e.measures()) {
            assertNull(m.value(), m.id());
            assertEquals(HealthMeasures.HINT_NEEDS_OUTCOMES, m.hint());
        }
        Measure resolution = e.measures().stream().filter(m -> m.id().equals("resolutionRate")).findFirst().orElseThrow();
        assertEquals(List.of("confirmed", "selfReported", "agentClosed", "silence"),
                resolution.breakdown().stream().map(BreakdownEntry::key).toList());
        assertTrue(((Section) sections.get(HealthDefaults.BUSINESS)).measures().isEmpty());
        assertNull(HealthMeasures.measure("costPerResolvedSession", data(null, null, null), null).value());
    }

    @Test
    @DisplayName("a caller who may not read ratings gets the quality section restricted and empty")
    void restrictedQuality() {
        Map<String, Object> sections = HealthMeasures.sections(data(null, HealthUsage.EMPTY, null), null, true);
        Section quality = (Section) sections.get(HealthDefaults.QUALITY);
        assertTrue(quality.restricted());
        assertTrue(quality.measures().isEmpty());
        assertTrue(HealthMeasures.deltas(data(null, HealthUsage.EMPTY, null), data(null, HealthUsage.EMPTY, null), true).stream()
                .noneMatch(d -> d.measureId().startsWith("rating")));
    }

    @Test
    @DisplayName("compare deltas say whether the change is better, by the measure's direction")
    void deltas() {
        PeriodData v7 = data(row(1000, 10, 0, 0, 1.0, 0, 0, 0, 0, Map.of()), new HealthUsage(100, 0, 1000, 0, 0), null);
        PeriodData v8 = data(row(1000, 50, 0, 0, 0.5, 0, 0, 0, 0, Map.of()), new HealthUsage(100, 0, 1000, 0, 0), null);
        List<CompareDelta> deltas = HealthMeasures.deltas(v7, v8, true);
        CompareDelta failures = deltas.stream().filter(d -> d.measureId().equals("agentFailureRate")).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, failures.better());
        assertTrue(failures.significant());
        CompareDelta cost = deltas.stream().filter(d -> d.measureId().equals("costPerSession")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, cost.better());
        assertFalse(cost.significant(), "no variance stored for a cost average");
        assertTrue(deltas.stream().noneMatch(d -> d.measureId().equals("resolutionRate")), "no value on either side");
    }

    @Test
    @DisplayName("unknown measure ids answer null")
    void unknown() {
        assertNull(HealthMeasures.measure("nope", data(null, null, null), null));
    }
}
