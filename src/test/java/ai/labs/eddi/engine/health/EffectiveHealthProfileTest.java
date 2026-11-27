/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Budgets;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EffectiveHealthProfile — presets with the stored profile laid over them")
class EffectiveHealthProfileTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    static HealthProfile profile(String preset, List<String> critical, Map<String, Limits> thresholds, Budgets budgets, Slo slo,
                                 List<MeasureOverride> overrides) {
        return new HealthProfile("a", preset, critical, thresholds, budgets, slo, overrides, null, null, null);
    }

    @Test
    @DisplayName("the four presets: support and sales differ in their critical measures; task scores no outcomes")
    void presets() {
        assertEquals(List.of("support", "sales", "assistant", "task"), List.copyOf(HealthDefaults.PRESETS.keySet()));
        EffectiveHealthProfile support = EffectiveHealthProfile.ofPreset("support");
        assertEquals(Set.of("agentFailureRate", "resolutionRate"), support.criticalMeasures());
        assertEquals(new Thresholds(0.15, 0.3, false), support.thresholds("escalationRate"));
        EffectiveHealthProfile sales = EffectiveHealthProfile.ofPreset("sales");
        assertEquals(Set.of("agentFailureRate", "abandonmentRate"), sales.criticalMeasures());
        assertEquals(0.4, sales.thresholds("resolutionRate").green());
        EffectiveHealthProfile assistant = EffectiveHealthProfile.ofPreset("assistant");
        assertNull(assistant.thresholds("escalationRate"), "an assistant's escalation is not banded");
        EffectiveHealthProfile task = EffectiveHealthProfile.ofPreset("task");
        assertEquals(Set.of("agentFailureRate"), task.criticalMeasures());
        assertNull(task.thresholds("resolutionRate"));
        // Every preset bands only known measures, with limits in the right order.
        HealthDefaults.PRESETS.values().forEach(p -> p.thresholds().forEach((id, t) -> {
            assertNotNull(HealthDefaults.of(id), id);
            assertEquals(HealthDefaults.of(id).direction() == HealthDefaults.Direction.HIGHER, t.higherIsBetter(), p.id() + " " + id);
            assertTrue(t.higherIsBetter() ? t.green() > t.red() : t.green() < t.red(), p.id() + " " + id);
        }));
    }

    @Test
    @DisplayName("without a preset in the profile the traffic decides; an unknown auto preset falls back to support")
    void presetSelection() {
        assertEquals("task", EffectiveHealthProfile.of(null, "task", TODAY).preset());
        assertEquals("sales", EffectiveHealthProfile.of(profile("sales", null, null, null, null, null), "task", TODAY).preset());
        assertEquals("task", EffectiveHealthProfile.of(profile(null, null, null, null, null, null), "task", TODAY).preset());
        assertEquals("support", EffectiveHealthProfile.of(null, "nonsense", TODAY).preset());
        assertTrue(EffectiveHealthProfile.of(profile(null, null, null, null, null, null), "task", TODAY).stored());
        assertFalse(EffectiveHealthProfile.of(null, "task", TODAY).stored());
    }

    @Test
    @DisplayName("critical: the profile's list replaces the preset's; an empty list means none")
    void critical() {
        EffectiveHealthProfile p = EffectiveHealthProfile.of(profile("support", List.of("apdex"), null, null, null, null), "support", TODAY);
        assertTrue(p.critical("apdex"));
        assertFalse(p.critical("resolutionRate"));
        assertTrue(EffectiveHealthProfile.of(profile("support", List.of(), null, null, null, null), "support", TODAY).criticalMeasures().isEmpty());
    }

    @Test
    @DisplayName("thresholds: the profile's limits win; the measure decides the direction")
    void thresholds() {
        EffectiveHealthProfile p = EffectiveHealthProfile.of(
                profile("support", null, Map.of("resolutionRate", new Limits(0.6, 0.3), "escalationRate", new Limits(0.1, 0.2)), null, null, null),
                "support", TODAY);
        assertEquals(new Thresholds(0.6, 0.3, true), p.thresholds("resolutionRate"));
        assertEquals(new Thresholds(0.1, 0.2, false), p.thresholds("escalationRate"));
        assertEquals(new Thresholds(0.85, 0.7, true), p.thresholds("apdex"), "untouched limits stay the preset's");
    }

    @Test
    @DisplayName("an SLO sets the failure bands at the error budget and five times it, unless they are given")
    void slo() {
        EffectiveHealthProfile p = EffectiveHealthProfile.of(profile("support", null, null, null, new Slo(0.995, 3000L, 20), null), "support",
                TODAY);
        assertEquals(0.005, p.thresholds("agentFailureRate").green(), 1e-12);
        assertEquals(0.025, p.thresholds("agentFailureRate").red(), 1e-12);
        assertEquals(0.995, p.agentTurnSuccess());
        assertEquals(3000L, p.apdexTMs());
        assertEquals(20, p.minTurnsForBurnAlert());
        EffectiveHealthProfile explicit = EffectiveHealthProfile.of(
                profile("support", null, Map.of("agentFailureRate", new Limits(0.02, 0.04)), null, new Slo(0.995, null, null), null), "support",
                TODAY);
        assertEquals(new Thresholds(0.02, 0.04, false), explicit.thresholds("agentFailureRate"));
        EffectiveHealthProfile defaults = EffectiveHealthProfile.ofPreset("support");
        assertEquals(0.99, defaults.agentTurnSuccess());
        assertNull(defaults.apdexTMs());
        assertEquals(50, defaults.minTurnsForBurnAlert());
    }

    @Test
    @DisplayName("a budget makes its cost measure scored: green up to it, red from 1.5× it")
    void budgets() {
        EffectiveHealthProfile p = EffectiveHealthProfile.of(profile("support", null, null, new Budgets(0.5, null, 2000.0), null, null), "support",
                TODAY);
        assertEquals(new Thresholds(0.5, 0.75, false), p.thresholds("costPerResolvedSession"));
        assertEquals("SCORED", p.kind(HealthDefaults.of("costPerResolvedSession")));
        assertEquals("SCORED", p.kind(HealthDefaults.of("tokensPerTurn")));
        assertEquals("INFORMATIONAL", p.kind(HealthDefaults.of("costPerSession")));
        assertEquals("INFORMATIONAL", p.kind(HealthDefaults.of("platformFailureRate")));
    }

    @Test
    @DisplayName("overrides: active ones by measure or section; expired, unparsable or non-IGNORED ones not")
    void overrides() {
        EffectiveHealthProfile p = EffectiveHealthProfile.of(profile("support", null, null, null, null,
                List.of(new MeasureOverride("apdex", null, "IGNORED", "load test", "2026-11-10"),
                        new MeasureOverride(null, "effectiveness", "IGNORED", "pilot", "2026-12-01"),
                        new MeasureOverride("fallbackRate", null, "IGNORED", "old", "2026-11-09"),
                        new MeasureOverride("agentFailureRate", null, "IGNORED", "bad day", "next week"),
                        new MeasureOverride("tokensPerTurn", null, "OTHER", "x", "2026-12-01"))),
                "support", TODAY);
        assertNotNull(p.ignored("apdex"), "until today still applies");
        assertNotNull(p.ignored("resolutionRate"), "by section");
        assertNull(p.ignored("fallbackRate"), "expired yesterday");
        assertNull(p.ignored("agentFailureRate"), "not a day");
        assertNull(p.ignored("tokensPerTurn"), "not IGNORED");
        assertNull(EffectiveHealthProfile.of(profile("support", null, null, null, null,
                List.of(new MeasureOverride("apdex", null, "IGNORED", "x", "2026-12-01"))), "support", null).ignored("apdex"));
    }

    @Test
    @DisplayName("the response is the effective profile, so writing it back keeps the meaning")
    void response() {
        HealthProfile stored = new HealthProfile("a", null, null, Map.of("resolutionRate", new Limits(0.6, 0.3)), new Budgets(0.5, null, null),
                new Slo(null, 2500L, null), List.of(), null, "2026-11-10T09:00:00Z", "alice");
        HealthProfile view = EffectiveHealthProfile.of(stored, "support", TODAY).toResponse("a", stored);
        assertEquals("support", view.preset());
        assertEquals(List.of("agentFailureRate", "resolutionRate"), view.critical());
        assertEquals(new Limits(0.6, 0.3), view.thresholds().get("resolutionRate"));
        assertFalse(view.thresholds().containsKey("costPerResolvedSession"), "budgets have their own field");
        assertEquals(0.99, view.slo().agentTurnSuccess());
        assertEquals(2500L, view.slo().apdexTMs());
        assertEquals(50, view.slo().minTurnsForBurnAlert());
        assertTrue(view.stored());
        assertEquals("alice", view.updatedBy());
        HealthProfile defaults = EffectiveHealthProfile.ofPreset("task").toResponse("a", null);
        assertFalse(defaults.stored());
        assertEquals("task", defaults.preset());
        assertTrue(defaults.overrides().isEmpty());
    }
}
