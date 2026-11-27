/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.OutcomeStats;
import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthStatusRules.Verdict;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.PlatformIncident;
import ai.labs.eddi.engine.health.report.HealthReports.Portfolio;
import ai.labs.eddi.engine.health.report.HealthReports.PortfolioItem;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The portfolio — every agent the caller may view, with its status, top reason,
 * traffic, a few key measures and the platform incidents of the period.
 * <p>
 * <b>Status</b> (H4) is computed per agent by the same rules as the agent's own
 * report ({@link HealthStatusRules}), over the reliability, effectiveness and
 * efficiency measures under the agent's effective health profile — from the
 * daily rows and the session outcomes, each read for all agents at once. The
 * quality section never counts towards a status, so the portfolio reads no
 * ratings. Its reasons carry no change marker (markers are read per agent, by
 * the report).
 * <p>
 * <b>Ordering by attention</b>: the status's severity ({@code NEEDS_ATTENTION},
 * {@code WATCH}, {@code HEALTHY}, then {@code NOT_ENOUGH_DATA}); then the size
 * of the largest significant change for the worse
 * ({@link HealthMeasures#worsening}); then traffic; then name.
 * <p>
 * <b>Paging</b>: {@code nextCursor} is an offset into this order, recomputed on
 * every page (from cached numbers, see {@code HealthQueryCache}); an agent
 * whose numbers change between two pages can move, so a page may repeat or skip
 * one.
 * <p>
 * <b>Platform incidents</b> are days on which one platform failure class
 * ({@code TRANSIENT}, {@code RATE_LIMITED}, {@code TIMEOUT}) hit at least
 * {@value HealthDefaults#INCIDENT_MIN_AGENTS} of the listed agents at once — an
 * agent counts as hit when it had at least
 * {@value HealthDefaults#INCIDENT_MIN_TURNS} turns that day, at least
 * {@value HealthDefaults#INCIDENT_MIN_FAILURES} failed attempts of the class,
 * and at least 5 % of its turns touched by the platform. Consecutive days of
 * the same class merge into one incident. Daily granularity, because that is
 * what the rollup keeps. Only the agents the caller may view are looked at, so
 * the count never reveals other agents.
 */
@ApplicationScoped
public class HealthPortfolioService {

    /** The key measures of a portfolio row. */
    static final List<String> KEY_MEASURES = List.of("agentFailureRate", "apdex", "costPerSession");

    /** An agent the caller may view. */
    public record AgentRef(String agentId, String agentName) {
    }

    /**
     * One agent judged over a window.
     *
     * @param measures
     *            every measure the status counts from (all sections but quality)
     * @param rows
     *            the agent's daily rows of the window and its baseline, in the
     *            origins its preset counts
     * @param traffic
     *            what {@code sort=traffic} orders by: the sessions, or the turns of
     *            the daily rows when sessions cannot be counted
     */
    public record Assessment(AgentRef agent, EffectiveHealthProfile profile, Verdict verdict, List<Measure> measures, List<Measure> keyMeasures,
            Long sessions, Double sessionsDelta, String hint, long traffic, List<HealthTurnAggregate> rows) {
    }

    private final HealthReportService reports;

    @Inject
    public HealthPortfolioService(HealthReportService reports) {
        this.reports = reports;
    }

    private record Row(PortfolioItem item, Verdict verdict, long traffic) {
    }

    /**
     * Judges every agent over the window: status, reasons, measures — what the
     * portfolio lists and what the {@code HEALTH} alerts compare.
     */
    public Map<String, Assessment> assess(List<AgentRef> agents, Window window) {
        Window baseline = window.baseline();
        List<String> ids = agents.stream().map(AgentRef::agentId).toList();
        Map<String, List<HealthTurnAggregate>> rowsByAgent = HealthReportService
                .byAgent(ids.isEmpty() ? List.of() : reports.dailyRows(ids, baseline.from(), window.to()));
        Map<String, HealthPortfolioSessions> traffic = ids.isEmpty() ? Map.of() : reports.portfolioSessions(ids, window);
        Map<String, List<HealthOutcomeCount>> outcomesNow = ids.isEmpty() ? Map.of() : reports.outcomeCountsByAgent(ids, window);
        Map<String, List<HealthOutcomeCount>> outcomesBefore = ids.isEmpty() ? Map.of() : reports.outcomeCountsByAgent(ids, baseline);
        Map<String, EffectiveHealthProfile> profiles = reports.profiles(ids, rowsByAgent);
        double nowExposure = reports.exposureDays(window);
        // While outcomes are still being evaluated (a backfill), session counts are
        // partial: their change is not presented as real.
        String pendingHint = reports.outcomesPendingHint(window);
        double beforeExposure = baseline.days();
        double minCoverage = reports.minOutcomeCoverage();

        Map<String, Assessment> out = new LinkedHashMap<>();
        for (AgentRef agent : agents) {
            List<HealthTurnAggregate> agentRows = rowsByAgent.getOrDefault(agent.agentId(), List.of());
            EffectiveHealthProfile profile = profiles.get(agent.agentId());
            if (profile == null) {
                profile = EffectiveHealthProfile.ofPreset(HealthReportService.preset(agentRows));
            }
            String preset = profile.preset();
            Set<String> origins = Set.copyOf(HealthPeriods.defaultOrigins(preset));
            boolean task = HealthDefaults.PRESET_TASK.equals(preset);
            HealthPortfolioSessions agentTraffic = traffic.getOrDefault(agent.agentId(), HealthPortfolioSessions.NONE);
            Long sessions = task ? agentTraffic.allNow() : agentTraffic.interactiveNow();
            Long sessionsBefore = task ? agentTraffic.allBefore() : agentTraffic.interactiveBefore();
            List<HealthTurnAggregate> selected = agentRows.stream().filter(r -> origins.contains(r.origin())).toList();
            List<HealthTurnAggregate> now = selected.stream().filter(r -> window.contains(r.day())).toList();
            List<HealthTurnAggregate> before = selected.stream().filter(r -> baseline.contains(r.day())).toList();
            OutcomeStats nowOutcomes = outcomes(outcomesNow, agent.agentId(), origins, minCoverage, pendingHint);
            OutcomeStats beforeOutcomes = outcomes(outcomesBefore, agent.agentId(), origins, minCoverage, null);
            // Sessions that cannot be counted are null usage, as in the report: cost per
            // session then says why it has no value, and cost itself still comes from
            // the daily rows.
            Totals nowTotals = Totals.of(now);
            PeriodData current = new PeriodData(nowTotals, sessions == null ? null : new HealthUsage(sessions, 0, 0, 0, 0), null, nowExposure,
                    nowOutcomes);
            PeriodData previous = new PeriodData(Totals.of(before), sessionsBefore == null ? null : new HealthUsage(sessionsBefore, 0, 0, 0, 0),
                    null, beforeExposure, beforeOutcomes);
            Map<String, Object> sections = HealthMeasures.sections(current, previous, true, profile);
            Verdict verdict = HealthStatusRules.evaluate(sections, profile, List.of());
            List<Measure> measures = HealthStatusRules.statusMeasures(sections);
            List<Measure> key = new ArrayList<>();
            for (String id : KEY_MEASURES) {
                measures.stream().filter(m -> id.equals(m.id())).findFirst().ifPresent(key::add);
            }
            // Relative to the baseline at the window's exposure (today is not over yet).
            Double delta = null;
            if (sessions != null && sessionsBefore != null && pendingHint == null) {
                double expected = sessionsBefore * (nowExposure / beforeExposure);
                delta = expected > 0 ? (sessions - expected) / expected : null;
            }
            String hint = sessions == null ? HealthMeasures.HINT_USAGE_UNAVAILABLE : pendingHint;
            long trafficKey = sessions != null ? sessions : nowTotals.turns();
            out.put(agent.agentId(), new Assessment(agent, profile, verdict, measures, key, sessions, delta, hint, trafficKey, selected));
        }
        return out;
    }

    /**
     * An agent's outcome counts in its preset's origins, or {@code null} when the
     * outcomes could not be counted at all.
     */
    private static OutcomeStats outcomes(Map<String, List<HealthOutcomeCount>> byAgent, String agentId, Set<String> origins, double minCoverage,
                                         String pending) {
        if (byAgent == null) {
            return null;
        }
        List<HealthOutcomeCount> counts = byAgent.getOrDefault(agentId, List.of()).stream().filter(c -> origins.contains(c.group())).toList();
        return OutcomeStats.of(counts, minCoverage).withPending(pending);
    }

    public Portfolio portfolio(List<AgentRef> agents, Window window, String sort, List<String> statuses, int limit, int offset) {
        Map<String, Assessment> assessed = assess(agents, window);
        Map<LocalDate, Map<String, Set<String>>> hits = new TreeMap<>();
        for (Assessment a : assessed.values()) {
            collectHits(a.agent().agentId(), a.rows(), window, hits);
        }
        List<PlatformIncident> incidents = new ArrayList<>();
        Set<String> affected = incidents(hits, incidents);
        List<Row> listed = new ArrayList<>();
        for (Assessment a : assessed.values()) {
            Verdict v = a.verdict();
            Reason top = v.reasons().isEmpty() ? null : v.reasons().get(0);
            listed.add(new Row(new PortfolioItem(a.agent().agentId(), a.agent().agentName(), v.status(), a.profile().preset(), top, a.sessions(),
                    a.sessionsDelta(), a.keyMeasures(), affected.contains(a.agent().agentId()), a.hint()), v, a.traffic()));
        }
        if (statuses != null && !statuses.isEmpty()) {
            listed.removeIf(r -> r.item().status() == null || !statuses.contains(r.item().status()));
        }
        listed.sort(comparator(sort));
        int from = Math.min(offset, listed.size());
        int to = Math.min(from + limit, listed.size());
        List<PortfolioItem> page = listed.subList(from, to).stream().map(Row::item).toList();
        String next = to < listed.size() ? String.valueOf(to) : null;
        return new Portfolio(page, incidents, next);
    }

    private static Comparator<Row> comparator(String sort) {
        Comparator<Row> byName = Comparator.comparing(r -> r.item().agentName() == null ? "" : r.item().agentName().toLowerCase(Locale.ROOT));
        Comparator<Row> byTraffic = Comparator.comparingLong(Row::traffic).reversed();
        return switch (sort) {
            case "name" -> byName.thenComparing(r -> r.item().agentId());
            case "traffic" -> byTraffic.thenComparing(byName);
            default -> Comparator.comparingInt((Row r) -> r.verdict().rank()).reversed()
                    .thenComparing(Comparator.comparingDouble((Row r) -> r.verdict().worsening()).reversed()).thenComparing(byTraffic)
                    .thenComparing(byName);
        };
    }

    /** Notes, per day and platform class, whether the agent was hit. */
    static void collectHits(String agentId, List<HealthTurnAggregate> rows, Window window, Map<LocalDate, Map<String, Set<String>>> hits) {
        Map<LocalDate, List<HealthTurnAggregate>> byDay = new TreeMap<>();
        for (HealthTurnAggregate row : rows) {
            if (window.contains(row.day())) {
                byDay.computeIfAbsent(row.day(), k -> new ArrayList<>()).add(row);
            }
        }
        byDay.forEach((day, dayRows) -> {
            Totals t = Totals.of(dayRows);
            if (t.turns() < HealthDefaults.INCIDENT_MIN_TURNS || t.platformAffectedTurns() < HealthDefaults.INCIDENT_MIN_SHARE * t.turns()) {
                return;
            }
            t.failuresByClass().forEach((cls, count) -> {
                if (HealthAttribution.isPlatformClass(cls) && count >= HealthDefaults.INCIDENT_MIN_FAILURES) {
                    hits.computeIfAbsent(day, k -> new TreeMap<>()).computeIfAbsent(cls, k -> new HashSet<>()).add(agentId);
                }
            });
        });
    }

    /**
     * Turns the hits into incidents (consecutive days of one class merged) and
     * answers the agents hit on an incident day.
     */
    static Set<String> incidents(Map<LocalDate, Map<String, Set<String>>> hits, List<PlatformIncident> out) {
        Set<String> affected = new HashSet<>();
        Map<String, LocalDate[]> open = new HashMap<>();
        Map<String, Integer> openAgents = new HashMap<>();
        Map<LocalDate, Map<String, Set<String>>> sorted = new TreeMap<>(hits);
        LocalDate last = null;
        for (Map.Entry<LocalDate, Map<String, Set<String>>> day : sorted.entrySet()) {
            for (String cls : List.copyOf(open.keySet())) {
                LocalDate[] range = open.get(cls);
                Set<String> today = day.getValue().get(cls);
                boolean continues = range[1].plusDays(1).equals(day.getKey()) && today != null && today.size() >= HealthDefaults.INCIDENT_MIN_AGENTS;
                if (!continues) {
                    out.add(new PlatformIncident(range[0].toString(), range[1].toString(), cls, openAgents.get(cls)));
                    open.remove(cls);
                    openAgents.remove(cls);
                }
            }
            day.getValue().forEach((cls, agents) -> {
                if (agents.size() < HealthDefaults.INCIDENT_MIN_AGENTS) {
                    return;
                }
                affected.addAll(agents);
                LocalDate[] range = open.get(cls);
                if (range == null) {
                    open.put(cls, new LocalDate[]{day.getKey(), day.getKey()});
                    openAgents.put(cls, agents.size());
                } else {
                    range[1] = day.getKey();
                    openAgents.merge(cls, agents.size(), Math::max);
                }
            });
            last = day.getKey();
        }
        if (last != null) {
            open.forEach((cls, range) -> out.add(new PlatformIncident(range[0].toString(), range[1].toString(), cls, openAgents.get(cls))));
        }
        out.sort(Comparator.comparing(PlatformIncident::from).thenComparing(PlatformIncident::failureClass));
        return affected;
    }
}
