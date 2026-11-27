/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthProfileValidator.Violation;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Budgets;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthProfileValidator — dotted violation paths")
class HealthProfileValidatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    static List<String> paths(HealthProfile p) {
        return HealthProfileValidator.validate("a", p, TODAY).stream().map(Violation::path).toList();
    }

    static HealthProfile of(String preset, List<String> critical, Map<String, Limits> thresholds, Budgets budgets, Slo slo,
                            List<MeasureOverride> overrides) {
        return new HealthProfile(null, preset, critical, thresholds, budgets, slo, overrides, null, null, null);
    }

    @Test
    @DisplayName("the plan's example profile is valid; so is an empty one")
    void valid() {
        HealthProfile example = of("support", List.of("agentFailureRate", "resolutionRate"), Map.of("resolutionRate", new Limits(0.7, 0.4)),
                new Budgets(0.5, null, null), new Slo(0.99, 4000L, 50),
                List.of(new MeasureOverride(null, "business", "IGNORED", "CRM outage", "2026-11-14")));
        assertTrue(paths(example).isEmpty(), () -> HealthProfileValidator.validate("a", example, TODAY).toString());
        assertTrue(paths(of(null, null, null, null, null, null)).isEmpty());
    }

    @Test
    @DisplayName("an expired override is not refused — it is ignored later")
    void expiredOverride() {
        assertTrue(paths(of(null, null, null, null, null, List.of(new MeasureOverride("apdex", null, "IGNORED", "x", "2026-01-01")))).isEmpty());
    }

    @Test
    @DisplayName("a missing body, another agent, an unknown preset")
    void basics() {
        assertEquals(List.of(""), HealthProfileValidator.validate("a", null, TODAY).stream().map(Violation::path).toList());
        HealthProfile other = new HealthProfile("b", null, null, null, null, null, null, null, null, null);
        assertEquals(List.of("agentId"), paths(other));
        assertEquals(List.of("preset"), paths(of("retail", null, null, null, null, null)));
    }

    @Test
    @DisplayName("critical: unknown, repeated, quality, informational without a budget — each at its index")
    void critical() {
        assertEquals(List.of("critical[1]", "critical[2]", "critical[3]", "critical[4]"),
                paths(of(null, List.of("apdex", "nope", "apdex", "ratingScore", "platformFailureRate"), null, null, null, null)));
        assertTrue(paths(of(null, List.of("costPerResolvedSession"), null, new Budgets(0.5, null, null), null, null)).isEmpty(),
                "a budgeted cost measure may be critical");
        assertEquals(List.of("critical[0]"), paths(of(null, List.of("costPerResolvedSession"), null, null, null, null)));
        assertEquals(List.of("critical"), paths(of(null, Collections.nCopies(11, "apdex"), null, null, null, null)).subList(0, 1));
    }

    @Test
    @DisplayName("thresholds: unknown, budget measures, informational, missing, out of range and inverted limits")
    void thresholds() {
        Map<String, Limits> t = new LinkedHashMap<>();
        t.put("nope", new Limits(0.1, 0.2));
        t.put("costPerSession", new Limits(1.0, 2.0));
        t.put("deflectionRate", new Limits(0.1, 0.2));
        t.put("apdex", new Limits(null, 0.5));
        t.put("fallbackRate", new Limits(0.1, 1.5));
        t.put("resolutionRate", new Limits(0.4, 0.7));
        t.put("escalationRate", new Limits(0.3, 0.2));
        t.put("abandonmentRate", null);
        assertEquals(List.of("thresholds.nope", "thresholds.costPerSession", "thresholds.deflectionRate", "thresholds.apdex.green",
                "thresholds.fallbackRate.red", "thresholds.resolutionRate.red", "thresholds.escalationRate.red", "thresholds.abandonmentRate"),
                paths(of(null, null, t, null, null, null)));
        assertTrue(paths(of(null, null, Map.of("resolutionRate", new Limits(0.7, 0.4)), null, null, null)).isEmpty());
    }

    @Test
    @DisplayName("budgets and SLO ranges")
    void budgetsAndSlo() {
        assertEquals(List.of("budgets.costPerResolvedUsd", "budgets.costPerSessionUsd", "budgets.tokensPerTurn"),
                paths(of(null, null, null, new Budgets(0.0, -1.0, Double.NaN), null, null)));
        assertEquals(List.of("slo.agentTurnSuccess", "slo.apdexTMs", "slo.minTurnsForBurnAlert"),
                paths(of(null, null, null, null, new Slo(1.0, 50L, 0), null)));
        assertEquals(List.of("slo.agentTurnSuccess"), paths(of(null, null, null, null, new Slo(0.4, null, null), null)));
    }

    @Test
    @DisplayName("overrides: measure xor section, known names, IGNORED, a reason and a day, not too far ahead")
    void overrides() {
        List<MeasureOverride> o = new ArrayList<>();
        o.add(new MeasureOverride("apdex", "reliability", "IGNORED", "x", "2026-11-14"));
        o.add(new MeasureOverride(null, null, "IGNORED", "x", "2026-11-14"));
        o.add(new MeasureOverride("nope", null, "IGNORED", "x", "2026-11-14"));
        o.add(new MeasureOverride(null, "nope", "IGNORED", "x", "2026-11-14"));
        o.add(new MeasureOverride("apdex", null, "MUTED", "x", "2026-11-14"));
        o.add(new MeasureOverride("apdex", null, "IGNORED", " ", "2026-11-14"));
        o.add(new MeasureOverride("apdex", null, "IGNORED", "x".repeat(201), "2026-11-14"));
        o.add(new MeasureOverride("apdex", null, "IGNORED", "x", null));
        o.add(new MeasureOverride("apdex", null, "IGNORED", "x", "14.11.2026"));
        o.add(new MeasureOverride("apdex", null, "IGNORED", "x", "2028-01-01"));
        o.add(null);
        assertEquals(List.of("overrides[0].measure", "overrides[1].measure", "overrides[2].measure", "overrides[3].section", "overrides[4].state",
                "overrides[5].reason", "overrides[6].reason", "overrides[7].until", "overrides[8].until", "overrides[9].until", "overrides[10]"),
                paths(of(null, null, null, null, null, o)));
        assertEquals("overrides", paths(of(null, null, null, null, null,
                Collections.nCopies(51, new MeasureOverride("apdex", null, "IGNORED", "x", "2026-11-14")))).get(0));
    }
}
