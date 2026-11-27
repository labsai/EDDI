/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthStatusRules.Verdict;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.EffectivenessSection;
import ai.labs.eddi.engine.health.report.HealthReports.Marker;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.health.report.HealthReports.Section;
import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthStatusRules — status, reasons, overrides, silence")
class HealthStatusRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final Thresholds FAILURES = new Thresholds(0.01, 0.05, false);
    static final Thresholds RESOLUTION = new Thresholds(0.7, 0.4, true);

    static Measure measure(String id, boolean critical, String unit, Double value, long n, Double baseline, Double delta, boolean significant,
                           String band, Thresholds thresholds) {
        return new Measure(id, HealthDefaults.of(id).label(), "SCORED", critical, unit, value, n, null, baseline, delta, significant, band,
                thresholds, null, null);
    }

    static Measure failures(double value, String band, Double delta, boolean significant) {
        return measure("agentFailureRate", true, "ratio", value, 1000, delta == null ? null : value - delta, delta, significant, band, FAILURES);
    }

    static Measure apdex(double value, String band) {
        return measure("apdex", false, "score", value, 1000, value, 0.0, false, band, new Thresholds(0.85, 0.7, true));
    }

    static Measure grey(String id) {
        return new Measure(id, HealthDefaults.of(id).label(), "SCORED", HealthDefaults.of(id).critical(), "ratio", null, 0, null, null, null,
                false, "GREY", null, null, "Fewer than 30 turns in this period");
    }

    static Map<String, Object> sections(List<Measure> reliability, List<Measure> effectiveness, List<Measure> quality) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put(HealthDefaults.RELIABILITY, new Section(false, reliability));
        s.put(HealthDefaults.EFFECTIVENESS, new EffectivenessSection(false, null, effectiveness));
        s.put(HealthDefaults.QUALITY, new Section(false, quality));
        s.put(HealthDefaults.EFFICIENCY, new Section(false, List.of()));
        return s;
    }

    static EffectiveHealthProfile support() {
        return EffectiveHealthProfile.ofPreset(HealthDefaults.PRESET_SUPPORT);
    }

    static Verdict evaluate(Map<String, Object> sections, EffectiveHealthProfile profile) {
        return HealthStatusRules.evaluate(sections, profile, List.of());
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("every counted measure GREY → NOT_ENOUGH_DATA, with an INFO reason from the critical measure's hint")
        void notEnoughData() {
            Verdict v = evaluate(sections(List.of(grey("agentFailureRate"), grey("apdex")), List.of(grey("resolutionRate")), List.of()),
                    support());
            assertEquals(HealthDefaults.STATUS_NOT_ENOUGH_DATA, v.status());
            assertEquals(1, v.reasons().size());
            assertEquals("INFO", v.reasons().get(0).severity());
            assertEquals("Not enough data: fewer than 30 turns in this period", v.reasons().get(0).text());
        }

        @Test
        @DisplayName("no measures at all → NOT_ENOUGH_DATA")
        void empty() {
            assertEquals(HealthDefaults.STATUS_NOT_ENOUGH_DATA, evaluate(sections(List.of(), List.of(), List.of()), support()).status());
        }

        @Test
        @DisplayName("a critical measure RED → NEEDS_ATTENTION")
        void criticalRed() {
            Verdict v = evaluate(sections(List.of(failures(0.08, "RED", null, false), apdex(0.9, "GREEN")), List.of(), List.of()), support());
            assertEquals(HealthDefaults.STATUS_NEEDS_ATTENTION, v.status());
            assertEquals("RED", v.reasons().get(0).severity());
            assertEquals("Agent-caused failures 8 % (target ≤ 1 %)", v.reasons().get(0).text());
        }

        @Test
        @DisplayName("a non-critical measure RED → WATCH, not NEEDS_ATTENTION")
        void nonCriticalRed() {
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false), apdex(0.5, "RED")), List.of(), List.of()), support());
            assertEquals(HealthDefaults.STATUS_WATCH, v.status());
            assertEquals("RED", v.reasons().get(0).severity());
        }

        @Test
        @DisplayName("an AMBER measure → WATCH")
        void amber() {
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false), apdex(0.8, "AMBER")), List.of(), List.of()), support());
            assertEquals(HealthDefaults.STATUS_WATCH, v.status());
            assertEquals("AMBER", v.reasons().get(0).severity());
            assertEquals("Response time (Apdex) 0.80 (target ≥ 0.85)", v.reasons().get(0).text());
        }

        @Test
        @DisplayName("a significant change for the worse on a GREEN measure → WATCH")
        void trend() {
            Measure escalation = measure("escalationRate", false, "ratio", 0.12, 200, 0.05, 0.07, true, "GREEN", new Thresholds(0.15, 0.3, false));
            Verdict v = evaluate(sections(List.of(apdex(0.9, "GREEN")), List.of(escalation), List.of()), support());
            assertEquals(HealthDefaults.STATUS_WATCH, v.status());
            assertEquals("AMBER", v.reasons().get(0).severity());
            assertEquals("Escalation 12 % (+7 pp vs. the previous period)", v.reasons().get(0).text());
            // 0.7 pp is below the 2 pp effect floor for rates: not a trend.
            assertEquals(HealthDefaults.STATUS_HEALTHY,
                    evaluate(sections(List.of(failures(0.008, "GREEN", 0.007, true)), List.of(), List.of()), support()).status());
        }

        @Test
        @DisplayName("a trend needs the effect floor: +3 pp counts, +1 pp does not, insignificant never")
        void trendFloor() {
            Measure worse = measure("resolutionRate", true, "ratio", 0.75, 200, 0.78, -0.03, true, "GREEN", RESOLUTION);
            Measure tiny = measure("resolutionRate", true, "ratio", 0.77, 200, 0.78, -0.01, true, "GREEN", RESOLUTION);
            Measure noise = measure("resolutionRate", true, "ratio", 0.72, 200, 0.78, -0.06, false, "GREEN", RESOLUTION);
            assertEquals(HealthDefaults.STATUS_WATCH, evaluate(sections(List.of(), List.of(worse), List.of()), support()).status());
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(sections(List.of(), List.of(tiny), List.of()), support()).status());
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(sections(List.of(), List.of(noise), List.of()), support()).status());
        }

        @Test
        @DisplayName("an improvement is no trend")
        void improvement() {
            Measure better = measure("resolutionRate", true, "ratio", 0.85, 200, 0.75, 0.10, true, "GREEN", RESOLUTION);
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(sections(List.of(), List.of(better), List.of()), support()).status());
        }

        @Test
        @DisplayName("all GREEN, nothing worse → HEALTHY, no reasons")
        void healthy() {
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false), apdex(0.9, "GREEN")), List.of(), List.of()), support());
            assertEquals(HealthDefaults.STATUS_HEALTHY, v.status());
            assertTrue(v.reasons().isEmpty());
        }

        @Test
        @DisplayName("quality never counts: a RED rating leaves the status HEALTHY")
        void qualityExcluded() {
            Measure rating = measure("ratingScore", true, "score", 0.3, 100, null, null, false, "RED", new Thresholds(0.7, 0.5, true));
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false)), List.of(), List.of(rating)), support());
            assertEquals(HealthDefaults.STATUS_HEALTHY, v.status());
        }

        @Test
        @DisplayName("an informational measure never counts")
        void informationalExcluded() {
            Measure info = new Measure("platformFailureRate", "x", "INFORMATIONAL", false, "ratio", 0.9, 1000, null, 0.0, 0.9, true, "RED", null,
                    null, null);
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false), info), List.of(), List.of()), support());
            assertEquals(HealthDefaults.STATUS_HEALTHY, v.status());
        }

        @Test
        @DisplayName("a restricted section counts for nothing")
        void restricted() {
            Map<String, Object> s = sections(List.of(failures(0.08, "RED", null, false)), List.of(), List.of());
            s.put(HealthDefaults.RELIABILITY, new Section(true, List.of(failures(0.08, "RED", null, false))));
            assertEquals(HealthDefaults.STATUS_NOT_ENOUGH_DATA, evaluate(s, support()).status());
        }
    }

    @Nested
    @DisplayName("overrides")
    class Overrides {

        private EffectiveHealthProfile withOverride(String measure, String section, String until) {
            HealthProfile p = new HealthProfile(null, null, null, null, null, null,
                    List.of(new MeasureOverride(measure, section, MeasureOverride.IGNORED, "CRM outage", until)), null, null, null);
            return EffectiveHealthProfile.of(p, HealthDefaults.PRESET_SUPPORT, TODAY);
        }

        @Test
        @DisplayName("an active measure override leaves a RED critical measure out")
        void measureIgnored() {
            Map<String, Object> s = sections(List.of(failures(0.08, "RED", null, false), apdex(0.9, "GREEN")), List.of(), List.of());
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(s, withOverride("agentFailureRate", null, "2026-11-14")).status());
        }

        @Test
        @DisplayName("an override until today still applies; one that ended yesterday is ignored")
        void expiry() {
            Map<String, Object> s = sections(List.of(failures(0.08, "RED", null, false), apdex(0.9, "GREEN")), List.of(), List.of());
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(s, withOverride("agentFailureRate", null, TODAY.toString())).status());
            assertEquals(HealthDefaults.STATUS_NEEDS_ATTENTION,
                    evaluate(s, withOverride("agentFailureRate", null, TODAY.minusDays(1).toString())).status());
        }

        @Test
        @DisplayName("a section override leaves every measure of the section out")
        void sectionIgnored() {
            Map<String, Object> s = sections(List.of(failures(0.08, "RED", null, false), apdex(0.5, "RED")),
                    List.of(measure("resolutionRate", true, "ratio", 0.8, 100, null, null, false, "GREEN", RESOLUTION)), List.of());
            assertEquals(HealthDefaults.STATUS_HEALTHY, evaluate(s, withOverride(null, HealthDefaults.RELIABILITY, "2026-11-30")).status());
        }

        @Test
        @DisplayName("everything ignored → NOT_ENOUGH_DATA")
        void allIgnored() {
            Map<String, Object> s = sections(List.of(failures(0.08, "RED", null, false)), List.of(), List.of());
            assertEquals(HealthDefaults.STATUS_NOT_ENOUGH_DATA, evaluate(s, withOverride(null, HealthDefaults.RELIABILITY, "2026-11-30")).status());
        }
    }

    @Nested
    @DisplayName("reasons")
    class Reasons {

        @Test
        @DisplayName("ordered by severity, critical first, then the size of the change; at most five")
        void ordering() {
            Measure redCritical = failures(0.08, "RED", null, false);
            Measure redOther = apdex(0.5, "RED");
            Measure amberSmall = measure("fallbackRate", false, "ratio", 0.05, 1000, 0.02, 0.03, true, "AMBER", new Thresholds(0.02, 0.1, false));
            Measure amberBig = measure("escalationRate", false, "ratio", 0.25, 200, 0.1, 0.15, true, "AMBER", new Thresholds(0.15, 0.3, false));
            Measure trendGreen = measure("resolutionRate", true, "ratio", 0.72, 200, 0.82, -0.10, true, "GREEN", RESOLUTION);
            Measure abandonment = measure("abandonmentRate", false, "ratio", 0.3, 200, 0.3, 0.0, false, "AMBER", new Thresholds(0.2, 0.35, false));
            Verdict v = evaluate(sections(List.of(redOther, amberSmall, redCritical), List.of(amberBig, trendGreen, abandonment), List.of()),
                    support());
            assertEquals(HealthDefaults.STATUS_NEEDS_ATTENTION, v.status());
            assertEquals(List.of("agentFailureRate", "apdex", "resolutionRate", "escalationRate", "fallbackRate"),
                    v.reasons().stream().map(Reason::measureId).toList());
            assertEquals(0.15, v.worsening(), 1e-9);
        }

        @Test
        @DisplayName("a change is explained by the newest deployment or version start of the period")
        void marker() {
            Measure trend = measure("resolutionRate", true, "ratio", 0.41, 200, 0.53, -0.12, true, "AMBER", RESOLUTION);
            List<Marker> markers = List.of(new Marker("m-1", "2026-11-01T08:00:00Z", "DEPLOYMENT", "v7 deployed (production)", 7),
                    new Marker("g-1", "2026-11-04T08:00:00Z", "GLOBAL_VARIABLE_CHANGE", "Global variables changed", null),
                    new Marker("m-2", "2026-11-03T10:12:00Z", "DEPLOYMENT", "v8 deployed (production)", 8));
            Verdict v = HealthStatusRules.evaluate(sections(List.of(), List.of(trend), List.of()), support(), markers);
            Reason r = v.reasons().get(0);
            assertEquals("Resolution 41 % (−12 pp since v8 on 3 Nov)", r.text());
            assertEquals("m-2", r.markerId());
        }

        @Test
        @DisplayName("without a marker the change is against the previous period")
        void noMarker() {
            Measure trend = measure("resolutionRate", true, "ratio", 0.41, 200, 0.53, -0.12, true, "AMBER", RESOLUTION);
            Reason r = evaluate(sections(List.of(), List.of(trend), List.of()), support()).reasons().get(0);
            assertEquals("Resolution 41 % (−12 pp vs. the previous period)", r.text());
            assertNull(r.markerId());
        }

        @Test
        @DisplayName("'mostly assumed from silence' when silence is more than half of the resolutions — never at half")
        void silence() {
            Measure mostly = resolution(10, 0, 5, 30);
            Verdict v = evaluate(sections(List.of(failures(0.005, "GREEN", null, false)), List.of(mostly), List.of()), support());
            assertEquals(HealthDefaults.STATUS_HEALTHY, v.status(), "an INFO reason does not change the status");
            Reason r = v.reasons().get(0);
            assertEquals("INFO", r.severity());
            assertEquals("resolutionRate", r.measureId());
            assertEquals("Resolution rests mostly on silence: 67 % of resolved sessions are assumed because people went quiet", r.text());

            Measure half = resolution(10, 0, 5, 15);
            assertTrue(evaluate(sections(List.of(failures(0.005, "GREEN", null, false)), List.of(half), List.of()), support()).reasons().isEmpty());
        }

        @Test
        @DisplayName("the silence reason shows even while the rate is withheld, and not when resolution is ignored")
        void silenceWithheldAndIgnored() {
            Measure withheld = new Measure("resolutionRate", "Resolution", "SCORED", true, "ratio", null, 40, null, null, null, false, "GREY",
                    RESOLUTION, List.of(new BreakdownEntry("confirmed", "Confirmed", null, 1), new BreakdownEntry("silence", "Silence", null, 9)),
                    "Outcome coverage too low");
            Map<String, Object> s = sections(List.of(failures(0.005, "GREEN", null, false)), List.of(withheld), List.of());
            assertTrue(evaluate(s, support()).reasons().stream().anyMatch(r -> r.text().startsWith("Resolution rests mostly on silence: 90 %")));
            HealthProfile ignore = new HealthProfile(null, null, null, null, null, null,
                    List.of(new MeasureOverride("resolutionRate", null, "IGNORED", "pilot", "2026-12-01")), null, null, null);
            assertTrue(evaluate(s, EffectiveHealthProfile.of(ignore, HealthDefaults.PRESET_SUPPORT, TODAY)).reasons().isEmpty());
        }

        private Measure resolution(long confirmed, long self, long closed, long silence) {
            return new Measure("resolutionRate", "Resolution", "SCORED", true, "ratio", 0.8, 100, null, 0.8, 0.0, false, "GREEN", RESOLUTION,
                    List.of(new BreakdownEntry("confirmed", "Confirmed", (double) confirmed / 100, confirmed),
                            new BreakdownEntry("selfReported", "Self-reported", (double) self / 100, self),
                            new BreakdownEntry("agentClosed", "Agent closed", (double) closed / 100, closed),
                            new BreakdownEntry("silence", "Silence", (double) silence / 100, silence)),
                    null);
        }
    }

    @Nested
    @DisplayName("formatting")
    class Formatting {

        @Test
        @DisplayName("values and changes in their units")
        void units() {
            assertEquals("41 %", HealthStatusRules.format("ratio", 0.41));
            assertEquals("3.2 %", HealthStatusRules.format("ratio", 0.032));
            assertEquals("0.72", HealthStatusRules.format("score", 0.7234));
            assertEquals("$0.52", HealthStatusRules.format("usd", 0.5234));
            assertEquals("$0.0042", HealthStatusRules.format("usd", 0.0042));
            assertEquals("850 ms", HealthStatusRules.format("ms", 850));
            assertEquals("1.2 s", HealthStatusRules.format("ms", 1234));
            assertEquals("1,234", HealthStatusRules.format("tokens", 1234));
            assertEquals("−12 pp", HealthStatusRules.formatDelta("ratio", -0.12));
            assertEquals("+1.8 pp", HealthStatusRules.formatDelta("ratio", 0.018));
            assertEquals("+$0.12", HealthStatusRules.formatDelta("usd", 0.12));
        }

        @Test
        @DisplayName("severity ranks: needs attention, watch, healthy, not enough data")
        void ranks() {
            assertTrue(Verdict.rank("NEEDS_ATTENTION") > Verdict.rank("WATCH"));
            assertTrue(Verdict.rank("WATCH") > Verdict.rank("HEALTHY"));
            assertTrue(Verdict.rank("HEALTHY") > Verdict.rank("NOT_ENOUGH_DATA"));
            assertTrue(Verdict.rank("NOT_ENOUGH_DATA") > Verdict.rank(null));
        }
    }
}
