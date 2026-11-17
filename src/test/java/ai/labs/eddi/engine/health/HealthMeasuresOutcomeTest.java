/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.OutcomeStats;
import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.EffectivenessSection;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthMeasures — effectiveness from session outcomes")
class HealthMeasuresOutcomeTest {

    static HealthOutcomeCount c(String outcome, String evidence, long sessions) {
        return new HealthOutcomeCount(null, null, outcome, evidence, true, false, sessions, sessions * 3);
    }

    /**
     * 100 closed engaged sessions: 20 confirmed, 5 self-reported, 10 agent-closed
     * and 25 silence resolutions; 10 escalated, 8 abandoned, 2 deflected, 5 failed,
     * 15 ended quietly. Plus 7 open, 9 not engaged and 4 not judged.
     */
    static List<HealthOutcomeCount> sample() {
        List<HealthOutcomeCount> counts = new ArrayList<>(List.of(c("RESOLVED", "agent_action", 20), c("RESOLVED", "self_reported", 5),
                c("ASSUMED_RESOLVED", "agent_closed", 10), c("ASSUMED_RESOLVED", "silence", 25), c("ESCALATED", "agent_action", 10),
                c("ABANDONED", "inferred", 8), c("DEFLECTED", "agent_action", 2), c("FAILED", "structural", 5), c("ENDED_QUIETLY", "inferred", 15)));
        counts.add(new HealthOutcomeCount(null, null, "OPEN", null, true, true, 7, 7));
        counts.add(new HealthOutcomeCount(null, null, "ASSUMED_RESOLVED", "silence", false, false, 9, 9));
        counts.add(new HealthOutcomeCount(null, null, null, null, false, false, 4, 4));
        return counts;
    }

    static PeriodData period(List<HealthOutcomeCount> counts, double minCoverage, double cost) {
        Totals totals = new Totals(10, 0, 0, 0, Map.of(), Map.of(), cost, 0, 0, 0, 0, 0, List.of(), null);
        return new PeriodData(totals, new HealthUsage(999, 999, 999, 5, 1), null, 1.0, OutcomeStats.of(counts, minCoverage));
    }

    @Test
    @DisplayName("OutcomeStats: denominators are engaged, closed, judged sessions; sessions count everything")
    void stats() {
        OutcomeStats o = OutcomeStats.of(sample(), 0.5);
        assertEquals(120, o.sessions());
        assertEquals(107, o.engagedSessions());
        assertEquals(100, o.closed());
        assertEquals(60, o.resolved());
        assertEquals(85, o.covered());
        assertEquals(0.85, o.coverage(), 1e-9);
    }

    @Test
    @DisplayName("resolutionRate with the four-part breakdown adding up to the rate")
    void resolution() {
        Measure m = HealthMeasures.pointOf("resolutionRate", period(sample(), 0.5, 1.0));
        assertEquals(0.6, m.value(), 1e-9);
        assertEquals(100, m.n());
        assertNotNull(m.interval());
        assertEquals("AMBER", m.band(), "between 0.4 and 0.7");
        List<BreakdownEntry> parts = m.breakdown();
        assertEquals(List.of("confirmed", "selfReported", "agentClosed", "silence"), parts.stream().map(BreakdownEntry::key).toList());
        assertEquals(List.of(0.2, 0.05, 0.1, 0.25), parts.stream().map(BreakdownEntry::value).toList());
        assertEquals(List.of(20L, 5L, 10L, 25L), parts.stream().map(BreakdownEntry::n).toList());
        assertEquals(m.value(), parts.stream().mapToDouble(BreakdownEntry::value).sum(), 1e-9);
    }

    @Test
    @DisplayName("escalation, abandonment and deflection over the same denominator; coverage untested")
    void otherRates() {
        PeriodData data = period(sample(), 0.5, 1.0);
        assertEquals(0.10, HealthMeasures.pointOf("escalationRate", data).value(), 1e-9);
        assertEquals(0.08, HealthMeasures.pointOf("abandonmentRate", data).value(), 1e-9);
        assertEquals(0.02, HealthMeasures.pointOf("deflectionRate", data).value(), 1e-9);
        Measure coverage = HealthMeasures.measure("outcomeCoverage", data, data);
        assertEquals(0.85, coverage.value(), 1e-9);
        assertFalse(coverage.significant(), "never tested");
        assertEquals("GREEN", coverage.band());
    }

    @Test
    @DisplayName("below the coverage minimum the rates are withheld with a hint; n and the breakdown counts stay")
    void lowCoverage() {
        List<HealthOutcomeCount> counts = List.of(c("ASSUMED_RESOLVED", "agent_closed", 20), c("ENDED_QUIETLY", "inferred", 80));
        PeriodData data = period(counts, 0.5, 1.0);
        Measure m = HealthMeasures.pointOf("resolutionRate", data);
        assertNull(m.value());
        assertEquals(100, m.n());
        assertTrue(m.hint().contains("20 %") && m.hint().contains("50 %"), m.hint());
        assertEquals(4, m.breakdown().size(), "always four parts");
        assertNull(m.breakdown().get(2).value());
        assertEquals(20, m.breakdown().get(2).n());
        assertNull(HealthMeasures.pointOf("escalationRate", data).value());
        assertEquals(0.2, HealthMeasures.pointOf("outcomeCoverage", data).value(), 1e-9, "coverage itself is shown");
        assertEquals("RED", HealthMeasures.pointOf("outcomeCoverage", data).band(), "below the minimum sample it would be grey; 100 is enough");

        assertNotNull(HealthMeasures.pointOf("resolutionRate", period(counts, 0.2, 1.0)).value(), "a lower minimum shows it");
    }

    @Test
    @DisplayName("no closed sessions, or no outcome data, give null values — the breakdown keeps its four keys")
    void noData() {
        Measure none = HealthMeasures.pointOf("resolutionRate", period(List.of(), 0.5, 0));
        assertNull(none.value());
        assertEquals(HealthMeasures.HINT_NO_CLOSED_SESSIONS, none.hint());
        assertEquals(4, none.breakdown().size());
        Measure unavailable = HealthMeasures.pointOf("resolutionRate", new PeriodData(Totals.EMPTY, null, null));
        assertNull(unavailable.value());
        assertEquals(4, unavailable.breakdown().size());
        assertEquals(HealthMeasures.HINT_OUTCOMES_UNAVAILABLE, unavailable.hint());
        assertNull(HealthMeasures.pointOf("outcomeCoverage", new PeriodData(Totals.EMPTY, null, null)).value());
        assertNull(HealthMeasures.pointOf("escalationRate", period(List.of(), 0.5, 0)).value());
    }

    @Test
    @DisplayName("cost per resolved session: the period's spend over resolved sessions")
    void costPerResolved() {
        Measure m = HealthMeasures.pointOf("costPerResolvedSession", period(sample(), 0.5, 6.0));
        assertEquals(0.1, m.value(), 1e-9);
        assertEquals(60, m.n());
    }

    @Test
    @DisplayName("adoption counts real sessions when outcomes are present, conversations otherwise")
    void adoptionSessions() {
        PeriodData data = period(sample(), 0.5, 12.0);
        assertEquals(120.0, HealthMeasures.pointOf("sessions", data).value());
        assertEquals(107.0, HealthMeasures.pointOf("engagedSessions", data).value());
        assertEquals(320.0 / 120, HealthMeasures.pointOf("turnsPerSession", data).value(), 1e-9, "turns of the outcome rows");
        assertEquals(0.1, HealthMeasures.pointOf("costPerSession", data).value(), 1e-9);
        PeriodData old = new PeriodData(Totals.EMPTY, new HealthUsage(8, 4, 16, 2, 0), null);
        assertEquals(8.0, HealthMeasures.pointOf("sessions", old).value());
        assertEquals(4.0, HealthMeasures.pointOf("engagedSessions", old).value());
    }

    @Test
    @DisplayName("resolution change between periods is tested with the two-proportion z-test")
    void significance() {
        List<HealthOutcomeCount> before = List.of(c("ASSUMED_RESOLVED", "silence", 300), c("ABANDONED", "inferred", 100));
        List<HealthOutcomeCount> after = List.of(c("ASSUMED_RESOLVED", "silence", 150), c("ABANDONED", "inferred", 250));
        Measure m = HealthMeasures.measure("resolutionRate", period(after, 0.5, 1), period(before, 0.5, 1));
        assertTrue(m.significant());
        assertEquals(-0.375, m.delta(), 1e-9);
    }

    @Test
    @DisplayName("the effectiveness section states its coverage")
    void sectionCoverage() {
        Map<String, Object> sections = HealthMeasures.sections(period(sample(), 0.5, 1), null, true);
        EffectivenessSection effectiveness = (EffectivenessSection) sections.get("effectiveness");
        assertEquals(0.85, effectiveness.coverage(), 1e-9);
        assertEquals(5, effectiveness.measures().size());
    }

    @Test
    @DisplayName("nit: cost per resolved session is withheld with the rates when coverage is too low")
    void costPerResolvedWithheld() {
        List<HealthOutcomeCount> counts = List.of(c("ASSUMED_RESOLVED", "agent_closed", 20), c("ENDED_QUIETLY", "inferred", 80));
        Measure m = HealthMeasures.pointOf("costPerResolvedSession", period(counts, 0.5, 4.0));
        assertNull(m.value());
        assertTrue(m.hint().contains("coverage"), m.hint());
        assertEquals(0.2, HealthMeasures.pointOf("costPerResolvedSession", period(counts, 0.1, 4.0)).value(), 1e-9);
    }

    @Test
    @DisplayName("M4: a pending evaluation's hint rides on every outcome-based measure, values kept")
    void pendingHint() {
        Totals totals = new Totals(10, 0, 0, 0, Map.of(), Map.of(), 1.0, 0, 0, 0, 0, 0, List.of(), null);
        PeriodData data = new PeriodData(totals, null, null, 1.0, OutcomeStats.of(sample(), 0.5).withPending("Outcomes are still being evaluated"));
        for (String id : List.of("resolutionRate", "sessions", "engagedSessions", "outcomeCoverage")) {
            Measure m = HealthMeasures.pointOf(id, data);
            assertNotNull(m.value(), id);
            assertEquals("Outcomes are still being evaluated", m.hint(), id);
        }
        assertNotEquals("Outcomes are still being evaluated", HealthMeasures.pointOf("tokensPerTurn", data).hint(), "not an outcome measure");
    }

    @Test
    @DisplayName("a pending evaluation's hint is added to the reason a value is withheld, never in its place")
    void pendingHintKeepsWithholdingReason() {
        List<HealthOutcomeCount> counts = List.of(c("ASSUMED_RESOLVED", "agent_closed", 20), c("ENDED_QUIETLY", "inferred", 80));
        Totals totals = new Totals(10, 0, 0, 0, Map.of(), Map.of(), 1.0, 0, 0, 0, 0, 0, List.of(), null);
        PeriodData data = new PeriodData(totals, null, null, 1.0, OutcomeStats.of(counts, 0.5).withPending("Outcomes are still being evaluated"));
        Measure m = HealthMeasures.pointOf("resolutionRate", data);
        assertNull(m.value());
        assertTrue(m.hint().contains("coverage"), m.hint());
        assertTrue(m.hint().endsWith("Outcomes are still being evaluated"), m.hint());
    }
}
