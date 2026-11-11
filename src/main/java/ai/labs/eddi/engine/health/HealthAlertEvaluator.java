/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.alerts.AlertEngine;
import ai.labs.eddi.engine.alerts.AlertRuleValidator;
import ai.labs.eddi.engine.alerts.AlertSettings;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertCondition.HealthTrigger;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthPortfolioService.AgentRef;
import ai.labs.eddi.engine.health.HealthPortfolioService.Assessment;
import ai.labs.eddi.engine.health.model.HealthAlertState;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Evaluates the {@code HEALTH} alert rules (plan §6.4) after each rollup pass —
 * never in a turn — and fires through the existing alert engine, so dedupe,
 * throttle, digest and delivery are those of every other alert. Health only
 * alerts, it never acts (plan decision 7).
 * <p>
 * Every agent a rule covers is judged over the last {@value #STATUS_DAYS} days
 * — the portfolio's default period, and with the same rules
 * ({@link HealthPortfolioService#assess}) — and three conditions are checked:
 * <ul>
 * <li><b>STATUS_CHANGE</b>: the status is one of the rule's statuses
 * ({@code WATCH}, {@code NEEDS_ATTENTION} by default) and differs from the one
 * the rule saw last. A rule that has not seen the agent yet reports a status in
 * its list once — so a new rule tells you what already needs attention.</li>
 * <li><b>BURN_RATE</b>: the agent-caused failure rate over the SLO's error
 * budget ({@code 1 − slo.agentTurnSuccess}) is at least {@value #BURN_FACTOR}×
 * in the long window (the last {@value #LONG_WINDOW_DAYS} UTC days, today
 * included) <em>and</em> in the short one (today), each with at least
 * {@code slo.minTurnsForBurnAlert} turns — the volume floor that keeps two
 * failures out of three turns from paging anyone. Whole days, because the
 * rollup keeps daily rows.</li>
 * <li><b>CRITICAL_TREND</b>: a critical measure of the profile got
 * significantly worse against the previous {@value #STATUS_DAYS} days, by at
 * least the status rules' effect floor.</li>
 * </ul>
 * Each condition fires when it <em>starts</em> (what the rule saw last is kept
 * per rule and agent in {@link IHealthAlertStateStore}), not again while it
 * lasts. Never throws: a failure is logged and the next pass tries again.
 */
@ApplicationScoped
public class HealthAlertEvaluator {

    private static final Logger LOGGER = Logger.getLogger(HealthAlertEvaluator.class);

    static final int STATUS_DAYS = 7;
    static final int LONG_WINDOW_DAYS = 3;
    static final double BURN_FACTOR = 2.0;
    /** States not refreshed this long belong to rules no longer evaluated. */
    static final Duration STATE_RETENTION = Duration.ofDays(30);
    /** Descriptors read per page while listing every agent. */
    static final int PAGE = 200;

    static final String CONDITION_BURN = "burn";
    static final String CONDITION_TREND = "trend:";

    private final IAlertRuleStore ruleStore;
    private final Instance<AlertEngine> engine;
    private final AlertSettings alertSettings;
    private final HealthPortfolioService portfolio;
    private final IHealthAlertStateStore stateStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final Clock clock;

    @Inject
    public HealthAlertEvaluator(IAlertRuleStore ruleStore, Instance<AlertEngine> engine, AlertSettings alertSettings,
            HealthPortfolioService portfolio, IHealthAlertStateStore stateStore, IDocumentDescriptorStore descriptorStore) {
        this(ruleStore, engine, alertSettings, portfolio, stateStore, descriptorStore, Clock.systemUTC());
    }

    HealthAlertEvaluator(IAlertRuleStore ruleStore, Instance<AlertEngine> engine, AlertSettings alertSettings, HealthPortfolioService portfolio,
            IHealthAlertStateStore stateStore, IDocumentDescriptorStore descriptorStore, Clock clock) {
        this.ruleStore = ruleStore;
        this.engine = engine;
        this.alertSettings = alertSettings;
        this.portfolio = portfolio;
        this.stateStore = stateStore;
        this.descriptorStore = descriptorStore;
        this.clock = clock;
    }

    /** One fire, for tests and logs. */
    record Fired(String ruleId, String agentId, String condition, AlertMatch match) {
    }

    /** Evaluates every enabled HEALTH rule; never throws. */
    public void evaluate() {
        try {
            evaluateRules();
        } catch (RuntimeException e) {
            LOGGER.warnf("The Agent Health alert evaluation failed; the next rollup pass tries again: %s", sanitize(e.getMessage()));
        }
    }

    /** @return what fired */
    List<Fired> evaluateRules() {
        List<Fired> fired = new ArrayList<>();
        if (alertSettings != null && !alertSettings.enabled()) {
            return fired;
        }
        List<AlertRule> rules = ruleStore.findEnabled().stream().filter(r -> r.enabled() && r.source() == AlertSource.HEALTH
                && r.condition() != null && r.condition().healthTriggers() != null && r.scope() != null).toList();
        if (rules.isEmpty()) {
            return fired;
        }
        Set<String> agentIds = new LinkedHashSet<>();
        boolean everyAgent = rules.stream().anyMatch(r -> r.scope().allAgents());
        if (everyAgent) {
            agentIds.addAll(allAgents());
        }
        rules.forEach(r -> agentIds.addAll(r.scope().agentIds()));
        if (agentIds.isEmpty()) {
            return fired;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Window window = new Window(today.minusDays(STATUS_DAYS - 1L), today);
        Map<String, Assessment> assessed = portfolio.assess(agentIds.stream().map(id -> new AgentRef(id, null)).toList(), window);
        Instant now = clock.instant();
        for (AlertRule rule : rules) {
            List<String> covered = rule.scope().allAgents()
                    ? List.copyOf(assessed.keySet())
                    : rule.scope().agentIds().stream().filter(assessed::containsKey).toList();
            if (covered.isEmpty()) {
                continue;
            }
            Map<String, HealthAlertState> before = stateStore.read(rule.id(), covered);
            List<HealthAlertState> after = new ArrayList<>();
            for (String agentId : covered) {
                Assessment a = assessed.get(agentId);
                after.add(evaluate(rule, a, before.get(agentId), today, now, fired));
            }
            stateStore.write(after);
        }
        try {
            stateStore.deleteEvaluatedBefore(now.minus(STATE_RETENTION));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not delete stale Agent Health alert states: %s", e.getMessage());
        }
        return fired;
    }

    /** One rule, one agent: fires what started and answers the new state. */
    HealthAlertState evaluate(AlertRule rule, Assessment a, HealthAlertState before, LocalDate today, Instant now, List<Fired> fired) {
        String agentId = a.agent().agentId();
        List<HealthTrigger> triggers = rule.condition().healthTriggers();
        String status = a.verdict().status();
        List<String> active = new ArrayList<>();
        List<String> wasActive = before == null ? List.of() : before.active();

        if (triggers.contains(HealthTrigger.STATUS_CHANGE)) {
            List<String> statuses = rule.condition().healthStatuses() == null
                    ? AlertRuleValidator.HEALTH_STATUSES
                    : rule.condition().healthStatuses();
            String previous = before == null ? null : before.status();
            if (status != null && statuses.contains(status) && !status.equals(previous)) {
                List<String> matched = new ArrayList<>();
                matched.add("status " + (previous == null ? "" : previous + " → ") + status);
                a.verdict().reasons().stream().filter(r -> !"INFO".equals(r.severity())).limit(2).map(Reason::text).forEach(matched::add);
                fire(rule, AlertMatch.health(matched, status, previous, null), agentId, "status:" + status, fired);
            }
        }
        if (triggers.contains(HealthTrigger.BURN_RATE)) {
            Burn burn = burn(a.rows(), today, a.profile());
            if (burn.active()) {
                active.add(CONDITION_BURN);
                if (!wasActive.contains(CONDITION_BURN)) {
                    fire(rule, AlertMatch.health(List.of(burn.describe(a.profile())), status, null, "agentFailureRate"), agentId, CONDITION_BURN,
                            fired);
                }
            }
        }
        if (triggers.contains(HealthTrigger.CRITICAL_TREND)) {
            for (Measure m : a.measures()) {
                if (!m.critical() || a.profile().ignored(m.id()) != null || !HealthStatusRules.worsened(m)) {
                    continue;
                }
                String key = CONDITION_TREND + m.id();
                active.add(key);
                if (!wasActive.contains(key)) {
                    String text = HealthStatusRules.reason(m, HealthStatusRules.AMBER, true, null).text();
                    fire(rule, AlertMatch.health(List.of("critical measure worse: " + text), status, null, m.id()), agentId, key, fired);
                }
            }
        }
        return new HealthAlertState(rule.id(), agentId, status, active, now);
    }

    private void fire(AlertRule rule, AlertMatch match, String agentId, String condition, List<Fired> fired) {
        fired.add(new Fired(rule.id(), agentId, condition, match));
        if (engine == null || !engine.isResolvable()) {
            return;
        }
        try {
            engine.get().fireHealth(rule, match, agentId, condition);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not record the HEALTH alert of rule %s for agent %s: %s", sanitize(rule.id()), sanitize(agentId),
                    sanitize(e.getMessage()));
        }
    }

    /**
     * The error-budget burn of the long and the short window: turns and
     * agent-caused failures of each, the budget ({@code 1 − SLO}) and the volume
     * floor.
     */
    record Burn(long longTurns, long longFailed, long shortTurns, long shortFailed, double budget, int floor) {

        Double longBurn() {
            return longTurns <= 0 || budget <= 0 ? null : ((double) longFailed / longTurns) / budget;
        }

        Double shortBurn() {
            return shortTurns <= 0 || budget <= 0 ? null : ((double) shortFailed / shortTurns) / budget;
        }

        boolean active() {
            Double l = longBurn();
            Double s = shortBurn();
            return longTurns >= floor && shortTurns >= floor && l != null && s != null && l >= BURN_FACTOR && s >= BURN_FACTOR;
        }

        String describe(EffectiveHealthProfile profile) {
            return String.format(Locale.ROOT,
                    "error budget burning %.1fx over the last %d days and %.1fx today (SLO %s of turns without an agent-caused failure; "
                            + "%d and %d turns)",
                    longBurn(), LONG_WINDOW_DAYS, shortBurn(), HealthStatusRules.format("ratio", profile.agentTurnSuccess()), longTurns,
                    shortTurns);
        }
    }

    static Burn burn(List<HealthTurnAggregate> rows, LocalDate today, EffectiveHealthProfile profile) {
        LocalDate longFrom = today.minusDays(LONG_WINDOW_DAYS - 1L);
        long longTurns = 0;
        long longFailed = 0;
        long shortTurns = 0;
        long shortFailed = 0;
        for (HealthTurnAggregate row : rows) {
            if (row.day().isBefore(longFrom) || row.day().isAfter(today)) {
                continue;
            }
            longTurns += row.turns();
            longFailed += row.agentFailedTurns();
            if (row.day().equals(today)) {
                shortTurns += row.turns();
                shortFailed += row.agentFailedTurns();
            }
        }
        return new Burn(longTurns, longFailed, shortTurns, shortFailed, 1 - profile.agentTurnSuccess(), profile.minTurnsForBurnAlert());
    }

    /** Every agent of the deployment, at most {@link HealthAccess#MAX_AGENTS}. */
    List<String> allAgents() {
        List<String> agents = new ArrayList<>();
        try {
            for (int index = 0; agents.size() < HealthAccess.MAX_AGENTS; index++) {
                List<DocumentDescriptor> page = descriptorStore.readDescriptors(HealthAccess.AGENT_DESCRIPTOR_TYPE, "", index, PAGE, false,
                        AccessScope.unrestricted());
                for (DocumentDescriptor descriptor : page) {
                    IResourceStore.IResourceId id = descriptor.getResource() == null
                            ? null
                            : RestUtilities.extractResourceId(descriptor.getResource());
                    if (id != null && id.getId() != null) {
                        agents.add(id.getId());
                    }
                }
                if (page.size() < PAGE) {
                    break;
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return agents;
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not list the agents for the HEALTH alerts: %s", sanitize(e.getMessage()));
        }
        return agents;
    }
}
