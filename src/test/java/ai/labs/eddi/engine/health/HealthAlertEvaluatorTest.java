/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.alerts.AlertEngine;
import ai.labs.eddi.engine.alerts.AlertSettings;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertCondition.HealthTrigger;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.health.HealthAlertEvaluator.Fired;
import ai.labs.eddi.engine.health.HealthPortfolioService.AgentRef;
import ai.labs.eddi.engine.health.HealthPortfolioService.Assessment;
import ai.labs.eddi.engine.health.HealthStatusRules.Verdict;
import ai.labs.eddi.engine.health.model.HealthAlertState;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthAlertEvaluator — status change, burn rate, critical trend")
class HealthAlertEvaluatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final Instant NOW = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);

    private IAlertRuleStore ruleStore;
    private AlertEngine engine;
    private AlertSettings settings;
    private HealthPortfolioService portfolio;
    private IHealthAlertStateStore stateStore;
    private HealthAlertEvaluator evaluator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ruleStore = mock(IAlertRuleStore.class);
        engine = mock(AlertEngine.class);
        Instance<AlertEngine> engines = mock(Instance.class);
        when(engines.isResolvable()).thenReturn(true);
        when(engines.get()).thenReturn(engine);
        settings = mock(AlertSettings.class);
        when(settings.enabled()).thenReturn(true);
        portfolio = mock(HealthPortfolioService.class);
        stateStore = mock(IHealthAlertStateStore.class);
        evaluator = new HealthAlertEvaluator(ruleStore, engines, settings, portfolio, stateStore, mock(IDocumentDescriptorStore.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static AlertRule rule(List<HealthTrigger> triggers, List<String> statuses) {
        return new AlertRule("r1", "Health", true, new AlertScope(false, List.of("a")), AlertSource.HEALTH,
                AlertCondition.health(triggers, statuses), List.of(), null, null, "admin", NOW, "admin", NOW);
    }

    static Assessment assessment(String status, List<Measure> measures, List<HealthTurnAggregate> rows, EffectiveHealthProfile profile) {
        Verdict verdict = new Verdict(status, List.of(new Reason("agentFailureRate", "RED", "Agent-caused failures 8 % (target ≤ 1 %)", null),
                new Reason("resolutionRate", "INFO", "Resolution rests mostly on silence", null)), 0);
        return new Assessment(new AgentRef("a", null), profile, verdict, measures, List.of(), 10L, null, null, 10, rows);
    }

    static Assessment assessment(String status) {
        return assessment(status, List.of(), List.of(), EffectiveHealthProfile.ofPreset("support"));
    }

    static HealthTurnAggregate day(LocalDate day, long turns, long failed) {
        List<Long> buckets = new ArrayList<>();
        for (int i = 0; i <= HealthTurnAggregator.DURATION_BOUNDS_MS.length; i++) {
            buckets.add(0L);
        }
        return new HealthTurnAggregate("a", 1, "api", day, turns, turns, turns, failed, Map.of(), 0, 0, Map.of(), 0, 0.0, 0, 0, 0, 0, 0, 0, 0,
                failed, 0, 0, 0, buckets, 4000, 0, 0);
    }

    private List<Fired> run(AlertRule rule, Assessment a, HealthAlertState before) {
        List<Fired> fired = new ArrayList<>();
        HealthAlertState after = evaluator.evaluate(rule, a, before, TODAY, NOW, fired);
        lastState = after;
        return fired;
    }

    private HealthAlertState lastState;

    @Nested
    @DisplayName("STATUS_CHANGE")
    class StatusChange {

        private final AlertRule rule = rule(List.of(HealthTrigger.STATUS_CHANGE), null);

        @Test
        @DisplayName("a rule that has not seen the agent reports a status in its list once")
        void firstSight() {
            List<Fired> fired = run(rule, assessment("NEEDS_ATTENTION"), null);
            assertEquals(1, fired.size());
            AlertMatch m = fired.get(0).match();
            assertEquals("NEEDS_ATTENTION", m.healthStatus());
            assertEquals(null, m.previousHealthStatus());
            assertEquals(List.of("status NEEDS_ATTENTION", "Agent-caused failures 8 % (target ≤ 1 %)"), m.matched(),
                    "INFO reasons are left out of the alert");
            assertEquals("status:NEEDS_ATTENTION", fired.get(0).condition());
            assertEquals("NEEDS_ATTENTION", lastState.status());
        }

        @Test
        @DisplayName("the same status again fires nothing; a change into the list fires with the previous status")
        void change() {
            assertTrue(run(rule, assessment("WATCH"), state("WATCH", List.of())).isEmpty());
            List<Fired> fired = run(rule, assessment("NEEDS_ATTENTION"), state("WATCH", List.of()));
            assertEquals("WATCH", fired.get(0).match().previousHealthStatus());
            assertEquals("status WATCH → NEEDS_ATTENTION", fired.get(0).match().matched().get(0));
        }

        @Test
        @DisplayName("HEALTHY and NOT_ENOUGH_DATA never fire; the rule's statuses narrow the list")
        void statuses() {
            assertTrue(run(rule, assessment("HEALTHY"), state("WATCH", List.of())).isEmpty());
            assertTrue(run(rule, assessment("NOT_ENOUGH_DATA"), null).isEmpty());
            AlertRule onlyRed = rule(List.of(HealthTrigger.STATUS_CHANGE), List.of("NEEDS_ATTENTION"));
            assertTrue(run(onlyRed, assessment("WATCH"), state("HEALTHY", List.of())).isEmpty());
            assertEquals(1, run(onlyRed, assessment("NEEDS_ATTENTION"), state("WATCH", List.of())).size());
        }
    }

    static HealthAlertState state(String status, List<String> active) {
        return new HealthAlertState("r1", "a", status, active, NOW.minusSeconds(300));
    }

    @Nested
    @DisplayName("BURN_RATE")
    class BurnRate {

        private final AlertRule rule = rule(List.of(HealthTrigger.BURN_RATE), null);

        @Test
        @DisplayName("both windows at 2× the error budget, above the volume floor → fires once, with the burn")
        void fires() {
            // 1 % budget; 3 % failures over three days, 4 % today.
            List<HealthTurnAggregate> rows = List.of(day(TODAY.minusDays(2), 100, 3), day(TODAY.minusDays(1), 100, 2), day(TODAY, 100, 4),
                    day(TODAY.minusDays(5), 1000, 900));
            List<Fired> fired = run(rule, assessment("WATCH", List.of(), rows, EffectiveHealthProfile.ofPreset("support")), null);
            assertEquals(1, fired.size());
            assertEquals("burn", fired.get(0).condition());
            assertEquals("agentFailureRate", fired.get(0).match().measureId());
            assertEquals("error budget burning 3.0x over the last 3 days and 4.0x today (SLO 99 % of turns without an agent-caused failure; "
                    + "300 and 100 turns)", fired.get(0).match().matched().get(0));
            assertEquals(List.of("burn"), lastState.active());
            // Still burning on the next pass: no second alert.
            assertTrue(run(rule, assessment("WATCH", List.of(), rows, EffectiveHealthProfile.ofPreset("support")),
                    state("WATCH", List.of("burn"))).isEmpty());
        }

        @Test
        @DisplayName("the short window alone, or the long one alone, is not enough")
        void bothWindows() {
            EffectiveHealthProfile p = EffectiveHealthProfile.ofPreset("support");
            // Long window 2 %, today 0.5 %.
            List<HealthTurnAggregate> longOnly = List.of(day(TODAY.minusDays(2), 100, 5), day(TODAY.minusDays(1), 100, 0), day(TODAY, 100, 1));
            assertTrue(run(rule, assessment("WATCH", List.of(), longOnly, p), null).isEmpty());
            // Today 10 %, but the three days 1.2 %.
            List<HealthTurnAggregate> shortOnly = List.of(day(TODAY.minusDays(2), 1000, 0), day(TODAY.minusDays(1), 1000, 0), day(TODAY, 100, 10),
                    day(TODAY.minusDays(3), 1000, 1000));
            assertTrue(run(rule, assessment("WATCH", List.of(), shortOnly, p), null).isEmpty());
        }

        @Test
        @DisplayName("the volume floor: fewer than minTurnsForBurnAlert turns in a window fires nothing")
        void floor() {
            List<HealthTurnAggregate> small = List.of(day(TODAY.minusDays(1), 30, 15), day(TODAY, 30, 15));
            assertTrue(run(rule, assessment("WATCH", List.of(), small, EffectiveHealthProfile.ofPreset("support")), null).isEmpty(),
                    "30 turns today is under the default floor of 50");
            HealthProfile lowFloor = new HealthProfile("a", null, null, null, null, new Slo(null, null, 20), null, null, null, null);
            assertEquals(1, run(rule, assessment("WATCH", List.of(), small, EffectiveHealthProfile.of(lowFloor, "support", TODAY)), null).size());
        }

        @Test
        @DisplayName("a looser SLO has a bigger budget: the same failures do not burn it")
        void slo() {
            List<HealthTurnAggregate> rows = List.of(day(TODAY.minusDays(1), 100, 3), day(TODAY, 100, 3));
            HealthProfile loose = new HealthProfile("a", null, null, null, null, new Slo(0.95, null, null), null, null, null, null);
            assertTrue(run(rule, assessment("WATCH", List.of(), rows, EffectiveHealthProfile.of(loose, "support", TODAY)), null).isEmpty());
        }
    }

    @Nested
    @DisplayName("CRITICAL_TREND")
    class CriticalTrend {

        private final AlertRule rule = rule(List.of(HealthTrigger.CRITICAL_TREND), null);

        private Measure resolution(boolean critical, double delta, boolean significant) {
            return new Measure("resolutionRate", "Resolution", "SCORED", critical, "ratio", 0.41, 200, null, 0.41 - delta, delta, significant,
                    "AMBER", new Thresholds(0.7, 0.4, true), null, null);
        }

        @Test
        @DisplayName("a critical measure significantly worse fires once per start")
        void fires() {
            List<Fired> fired = run(rule, assessment("WATCH", List.of(resolution(true, -0.12, true)), List.of(),
                    EffectiveHealthProfile.ofPreset("support")), null);
            assertEquals(1, fired.size());
            assertEquals("trend:resolutionRate", fired.get(0).condition());
            assertEquals("critical measure worse: Resolution 41 % (−12 pp vs. the previous period)", fired.get(0).match().matched().get(0));
            assertTrue(run(rule, assessment("WATCH", List.of(resolution(true, -0.12, true)), List.of(), EffectiveHealthProfile.ofPreset("support")),
                    state("WATCH", List.of("trend:resolutionRate"))).isEmpty());
        }

        @Test
        @DisplayName("not critical, not significant, too small, or ignored: nothing")
        void quiet() {
            EffectiveHealthProfile support = EffectiveHealthProfile.ofPreset("support");
            assertTrue(run(rule, assessment("WATCH", List.of(resolution(false, -0.12, true)), List.of(), support), null).isEmpty());
            assertTrue(run(rule, assessment("WATCH", List.of(resolution(true, -0.12, false)), List.of(), support), null).isEmpty());
            assertTrue(run(rule, assessment("WATCH", List.of(resolution(true, -0.01, true)), List.of(), support), null).isEmpty());
            HealthProfile ignored = new HealthProfile("a", null, null, null, null, null,
                    List.of(new MeasureOverride("resolutionRate", null, "IGNORED", "pilot", "2026-12-01")), null, null, null);
            assertTrue(run(rule, assessment("WATCH", List.of(resolution(true, -0.12, true)), List.of(),
                    EffectiveHealthProfile.of(ignored, "support", TODAY)), null).isEmpty());
        }
    }

    @Nested
    @DisplayName("a pass")
    class Pass {

        @Test
        @DisplayName("evaluates enabled HEALTH rules only, fires through the engine with the condition, and stores the states")
        @SuppressWarnings("unchecked")
        void pass() {
            AlertRule health = rule(List.of(HealthTrigger.STATUS_CHANGE), null);
            AlertRule rating = new AlertRule("r2", "Rating", true, new AlertScope(false, List.of("a")), AlertSource.RATING,
                    new AlertCondition(0.4, null, null, null), List.of(), null, null, null, null, null, null);
            when(ruleStore.findEnabled()).thenReturn(List.of(health, rating));
            when(portfolio.assess(any(), any())).thenReturn(Map.of("a", assessment("NEEDS_ATTENTION")));
            when(stateStore.read(eq("r1"), any())).thenReturn(Map.of());

            List<Fired> fired = evaluator.evaluateRules();

            assertEquals(1, fired.size());
            verify(engine).fireHealth(eq(health), any(AlertMatch.class), eq("a"), eq("status:NEEDS_ATTENTION"));
            ArgumentCaptor<Collection<HealthAlertState>> states = ArgumentCaptor.forClass(Collection.class);
            verify(stateStore).write(states.capture());
            assertEquals("NEEDS_ATTENTION", states.getValue().iterator().next().status());
            verify(stateStore, never()).read(eq("r2"), any());
        }

        @Test
        @DisplayName("alerts off, or no HEALTH rule: nothing is judged")
        void nothing() {
            when(settings.enabled()).thenReturn(false);
            assertTrue(evaluator.evaluateRules().isEmpty());
            verify(ruleStore, never()).findEnabled();
            when(settings.enabled()).thenReturn(true);
            when(ruleStore.findEnabled()).thenReturn(List.of());
            assertTrue(evaluator.evaluateRules().isEmpty());
            verify(portfolio, never()).assess(any(), any());
        }

        @Test
        @DisplayName("a failing store never escapes evaluate()")
        void neverThrows() {
            when(ruleStore.findEnabled()).thenThrow(new IllegalStateException("down"));
            evaluator.evaluate();
            verify(engine, never()).fireHealth(any(), any(), anyString(), anyString());
        }
    }
}
