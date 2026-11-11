/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.PlatformIncident;
import ai.labs.eddi.engine.health.report.HealthReports.Portfolio;
import ai.labs.eddi.engine.health.report.HealthReports.PortfolioItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The portfolio — every agent the caller may view, with its traffic, a few key
 * measures and the platform incidents of the period.
 * <p>
 * <b>Ordering by attention</b> (until H4 adds a status): the agents whose key
 * measures got <em>significantly</em> worse come first, ranked by the largest
 * such change ({@link HealthMeasures#worsening}); then by the worst band of the
 * key measures (red before amber before green); then by traffic; then by name.
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

    private final HealthReportService reports;

    @Inject
    public HealthPortfolioService(HealthReportService reports) {
        this.reports = reports;
    }

    private record Row(PortfolioItem item, double worsening, int worstBand) {
    }

    public Portfolio portfolio(List<AgentRef> agents, Window window, String sort, List<String> statuses, int limit, int offset) {
        Window baseline = window.baseline();
        List<String> ids = agents.stream().map(AgentRef::agentId).toList();
        Map<String, List<HealthTurnAggregate>> rowsByAgent = HealthReportService
                .byAgent(ids.isEmpty() ? List.of() : reports.dailyRows(ids, baseline.from(), window.to()));
        Map<String, HealthPortfolioSessions> traffic = ids.isEmpty() ? Map.of() : reports.portfolioSessions(ids, window);
        double nowExposure = reports.exposureDays(window);
        // While outcomes are still being evaluated (a backfill), session counts are
        // partial: their change is not presented as real.
        boolean sessionsPending = reports.outcomesPendingHint(window) != null;
        double beforeExposure = baseline.days();

        Map<LocalDate, Map<String, Set<String>>> hits = new TreeMap<>();
        List<Row> rows = new ArrayList<>();
        for (AgentRef agent : agents) {
            List<HealthTurnAggregate> agentRows = rowsByAgent.getOrDefault(agent.agentId(), List.of());
            String preset = HealthReportService.preset(agentRows);
            Set<String> origins = Set.copyOf(HealthPeriods.defaultOrigins(preset));
            boolean task = HealthDefaults.PRESET_TASK.equals(preset);
            HealthPortfolioSessions agentTraffic = traffic.getOrDefault(agent.agentId(), HealthPortfolioSessions.NONE);
            long sessions = task ? agentTraffic.allNow() : agentTraffic.interactiveNow();
            long sessionsBefore = task ? agentTraffic.allBefore() : agentTraffic.interactiveBefore();
            List<HealthTurnAggregate> now = agentRows.stream().filter(r -> origins.contains(r.origin()) && window.contains(r.day())).toList();
            List<HealthTurnAggregate> before = agentRows.stream().filter(r -> origins.contains(r.origin()) && baseline.contains(r.day())).toList();
            PeriodData current = new PeriodData(Totals.of(now), new HealthUsage(sessions, 0, 0, 0, 0), null, nowExposure);
            PeriodData previous = new PeriodData(Totals.of(before), new HealthUsage(sessionsBefore, 0, 0, 0, 0), null, beforeExposure);
            List<Measure> key = new ArrayList<>();
            double worsening = 0;
            int worstBand = 0;
            for (String id : KEY_MEASURES) {
                Measure m = HealthMeasures.measure(id, current, previous);
                key.add(m);
                worsening = Math.max(worsening, HealthMeasures.worsening(m));
                worstBand = Math.max(worstBand, bandRank(m.band()));
            }
            // Relative to the baseline at the window's exposure (today is not over yet).
            double expected = sessionsBefore * (nowExposure / beforeExposure);
            Double delta = expected > 0 && !sessionsPending ? (sessions - expected) / expected : null;
            collectHits(agent.agentId(), now, window, hits);
            rows.add(new Row(new PortfolioItem(agent.agentId(), agent.agentName(), null, preset, null, sessions, delta, key, false), worsening,
                    worstBand));
        }

        List<PlatformIncident> incidents = new ArrayList<>();
        Set<String> affected = incidents(hits, incidents);
        List<Row> listed = new ArrayList<>();
        for (Row row : rows) {
            PortfolioItem i = row.item();
            boolean hit = affected.contains(i.agentId());
            listed.add(new Row(new PortfolioItem(i.agentId(), i.agentName(), i.status(), i.preset(), i.topReason(), i.sessions(), i.sessionsDelta(),
                    i.keyMeasures(), hit), row.worsening(), row.worstBand()));
        }
        // Until H4 no agent has a status, so any status filter matches none.
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
        Comparator<Row> byTraffic = Comparator.comparingLong((Row r) -> r.item().sessions()).reversed();
        return switch (sort) {
            case "name" -> byName.thenComparing(r -> r.item().agentId());
            case "traffic" -> byTraffic.thenComparing(byName);
            default ->
                Comparator.comparingDouble((Row r) -> r.worsening()).reversed().thenComparing(Comparator.comparingInt(Row::worstBand).reversed())
                        .thenComparing(byTraffic).thenComparing(byName);
        };
    }

    private static int bandRank(String band) {
        return switch (band) {
            case "RED" -> 3;
            case "AMBER" -> 2;
            case "GREEN" -> 1;
            default -> 0;
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
