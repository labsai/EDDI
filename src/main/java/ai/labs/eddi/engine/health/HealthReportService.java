/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.agenttests.IAgentTestRunStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.health.HealthMeasures.OutcomeStats;
import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.RatingStats;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.Check;
import ai.labs.eddi.engine.health.report.HealthReports.CompareSide;
import ai.labs.eddi.engine.health.report.HealthReports.DateRange;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.Marker;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Period;
import ai.labs.eddi.engine.health.report.HealthReports.Series;
import ai.labs.eddi.engine.health.report.HealthReports.SeriesPoint;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Builds the Agent Health views of one agent — the report, the series and the
 * version compare — from the stores. Access is the caller's concern
 * ({@code RestAgentHealth} checks it); this service only reads.
 * <p>
 * Where each number comes from:
 * <ul>
 * <li>reliability, efficiency and guardrails — the daily rows
 * ({@code healthdaily}), so a period costs one indexed read of at most days ×
 * versions × origins rows, and the baseline reaches back past the turn-row
 * retention;</li>
 * <li>sessions, engagement, distinct and returning users — the turn rows,
 * counted in the database ({@link IHealthTurnStore#usage}): distinct counts do
 * not add up across days. A period (or baseline) older than the turn-row
 * retention has none;</li>
 * <li>ratings — the rating store's summary, only for callers who may read
 * ratings;</li>
 * <li>sessions, effectiveness and cost per resolved session — the session
 * outcome rows, counted in the database ({@link IHealthOutcomeStore#counts}) by
 * the day each session ended. Like the turn rows they are kept for the turn-row
 * retention.</li>
 * </ul>
 */
@ApplicationScoped
public class HealthReportService {

    private static final Logger LOGGER = Logger.getLogger(HealthReportService.class);

    /** Change markers returned with one series, at most. */
    static final int MAX_MARKERS = 200;
    /** Agent test runs looked at for the golden-suite check. */
    static final int TEST_RUNS_SCANNED = 50;
    /** The default window of the older version in a compare. */
    public static final int DEFAULT_COMPARE_DAYS = 14;

    private final IHealthTurnStore turnStore;
    private final IHealthDailyStore dailyStore;
    private final IHealthDirtyDayStore dirtyStore;
    private final IHealthMarkerStore markerStore;
    private final IConversationRatingStore ratingStore;
    private final IAgentTestRunStore testRunStore;
    private final IDeploymentStore deploymentStore;
    private final HealthSettings settings;
    private final Clock clock;
    private final HealthQueryCache cache;
    /** The session outcomes; {@code null} when built by hand without them. */
    private IHealthOutcomeStore outcomeStore;

    /** How far before a window the returning-user look-back reaches, at most. */
    static final int RETURNING_LOOKBACK_DAYS = 30;

    @Inject
    public HealthReportService(IHealthTurnStore turnStore, IHealthDailyStore dailyStore, IHealthDirtyDayStore dirtyStore,
            IHealthMarkerStore markerStore, IConversationRatingStore ratingStore, IAgentTestRunStore testRunStore,
            IDeploymentStore deploymentStore, HealthSettings settings, IHealthOutcomeStore outcomeStore) {
        this(turnStore, dailyStore, dirtyStore, markerStore, ratingStore, testRunStore, deploymentStore, settings, Clock.systemUTC());
        this.outcomeStore = outcomeStore;
    }

    /** With the session outcomes — for tests. */
    HealthReportService withOutcomes(IHealthOutcomeStore outcomeStore) {
        this.outcomeStore = outcomeStore;
        return this;
    }

    HealthReportService(IHealthTurnStore turnStore, IHealthDailyStore dailyStore, IHealthDirtyDayStore dirtyStore, IHealthMarkerStore markerStore,
            IConversationRatingStore ratingStore, IAgentTestRunStore testRunStore, IDeploymentStore deploymentStore, HealthSettings settings,
            Clock clock) {
        this.turnStore = turnStore;
        this.dailyStore = dailyStore;
        this.dirtyStore = dirtyStore;
        this.markerStore = markerStore;
        this.ratingStore = ratingStore;
        this.testRunStore = testRunStore;
        this.deploymentStore = deploymentStore;
        this.settings = settings;
        this.clock = clock;
        this.cache = new HealthQueryCache(settings.cacheTtl(), clock);
    }

    /** Today, UTC. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The first day whose turn rows are still kept. */
    LocalDate oldestTurnDay() {
        return today().minusDays(settings.factsRetentionDays());
    }

    /**
     * What a request asked for, resolved.
     *
     * @param origins
     *            the origins asked for, or {@code null} for the preset's default
     */
    public record Scope(String agentId, Window window, Integer version, List<String> origins, List<String> markerTenants) {

        /** With the default marker tenant only. */
        public Scope(String agentId, Window window, Integer version, List<String> origins) {
            this(agentId, window, version, origins, List.of(HealthMarker.DEFAULT_TENANT));
        }
    }

    /**
     * How many days of data a window holds: its days, less what of today has not
     * happened yet when it ends today. Count measures compare their baseline at
     * this exposure, so a window ending this morning is not "significantly" behind
     * a full previous period.
     */
    double exposureDays(Window window) {
        if (!window.to().equals(today())) {
            return window.days();
        }
        Instant now = clock.instant();
        double elapsed = Duration.between(today().atStartOfDay(ZoneOffset.UTC).toInstant(), now).toMillis() / 86_400_000.0;
        return window.days() - 1 + Math.max(1.0 / 24, Math.min(1.0, elapsed));
    }

    // ------------------------------------------------------------------ report

    /** {@code GET /agents/{agentId}/health}. */
    public AgentHealth report(Scope scope, String agentName, boolean qualityAllowed) {
        Window window = scope.window();
        Window baseline = window.baseline();
        List<HealthTurnAggregate> rows = daily(scope.agentId(), baseline.from(), window.to());
        String preset = preset(rows);
        List<String> origins = scope.origins() != null ? scope.origins() : HealthPeriods.defaultOrigins(preset);
        Predicate<HealthTurnAggregate> selected = selected(scope.version(), origins);

        PeriodData current = periodData(scope.agentId(), window, scope.version(), origins, rows, selected, qualityAllowed);
        PeriodData before = periodData(scope.agentId(), baseline, scope.version(), origins, rows, selected, qualityAllowed);
        Map<String, Object> sections = HealthMeasures.sections(current, before, !qualityAllowed);

        return new AgentHealth(scope.agentId(), agentName, period(window), new DateRange(baseline.from().toString(), baseline.to().toString()),
                scope.version(), origins, null, List.of(), preset, clock.instant().toString(), dataThrough(scope.agentId()).toString(), sections,
                List.of(goldenSuite(scope.agentId())));
    }

    /**
     * The origins a request counts: those asked for, else the default of the
     * agent's preset over the window — for the session views.
     */
    public List<String> effectiveOrigins(String agentId, Window window, List<String> requested) {
        if (requested != null) {
            return requested;
        }
        return HealthPeriods.defaultOrigins(preset(daily(agentId, window.from(), window.to())));
    }

    /**
     * {@code support} when the agent has interactive traffic in the rows — or no
     * traffic at all — else {@code task}: an agent driven only by schedules,
     * webhooks, A2A or groups (plan §4.6).
     */
    static String preset(List<HealthTurnAggregate> rows) {
        boolean any = false;
        for (HealthTurnAggregate row : rows) {
            if (HealthOrigins.AGENT_TEST.equals(row.origin()) || row.turns() == 0) {
                continue;
            }
            any = true;
            if (HealthOrigins.INTERACTIVE.contains(row.origin())) {
                return HealthDefaults.PRESET_SUPPORT;
            }
        }
        return any ? HealthDefaults.PRESET_TASK : HealthDefaults.PRESET_SUPPORT;
    }

    private static Predicate<HealthTurnAggregate> selected(Integer version, List<String> origins) {
        Set<String> wanted = Set.copyOf(origins);
        return row -> wanted.contains(row.origin()) && (version == null || version.equals(row.agentVersion()));
    }

    private PeriodData periodData(String agentId, Window window, Integer version, List<String> origins, List<HealthTurnAggregate> rows,
                                  Predicate<HealthTurnAggregate> selected, boolean qualityAllowed) {
        Totals totals = Totals.of(rows.stream().filter(selected).filter(r -> window.contains(r.day())).toList());
        HealthUsage usage = usage(agentId, window, version, origins);
        RatingStats ratings = qualityAllowed ? ratings(agentId, window, version, origins) : null;
        return new PeriodData(totals, usage, ratings, exposureDays(window), outcomes(agentId, window, version, origins));
    }

    /**
     * The sessions that ended in a window, or null when they cannot be counted —
     * the window reaches past the retention, the agent has no outcome store, or it
     * could not be read.
     */
    OutcomeStats outcomes(String agentId, Window window, Integer version, List<String> origins) {
        List<HealthOutcomeCount> counts = outcomeCounts(new HealthUsageQuery(agentId, window.from(), window.to(), version, origins), false, "none");
        return counts == null ? null : OutcomeStats.of(counts, settings.minOutcomeCoverage()).withPending(outcomesPendingHint(window));
    }

    /**
     * The hint the outcome-based measures carry while the outcome evaluation has
     * not reached the window's end — after an upgrade it backfills the turn-row
     * retention a batch per pass — or {@code null} when it has (within an hour and
     * two rollup intervals, which a held-back failure or a pass in progress can
     * take).
     */
    public String outcomesPendingHint(Window window) {
        if (outcomeStore == null) {
            return null;
        }
        Instant watermark;
        try {
            watermark = cache.get(List.of("outcomeWatermark"), () -> outcomeStore.watermark().orElse(Instant.EPOCH));
        } catch (RuntimeException e) {
            return null;
        }
        Instant now = clock.instant();
        Instant end = window.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant needed = (end.isBefore(now) ? end : now).minus(Duration.ofHours(1)).minus(settings.rollupInterval().multipliedBy(2));
        if (!watermark.isBefore(needed)) {
            return null;
        }
        return Instant.EPOCH.equals(watermark)
                ? "Outcomes are still being evaluated"
                : "Outcomes are still being evaluated (through " + LocalDate.ofInstant(watermark, ZoneOffset.UTC) + ")";
    }

    /** Outcome counts, cached; null when unavailable. */
    private List<HealthOutcomeCount> outcomeCounts(HealthUsageQuery query, boolean perDay, String groupBy) {
        if (outcomeStore == null || query.from().isBefore(oldestTurnDay())) {
            return null;
        }
        try {
            return cache.get(List.of("outcomes", query, perDay, groupBy == null ? "none" : groupBy),
                    () -> outcomeStore.counts(query, perDay, groupBy));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not count the session outcomes of agent %s for Agent Health: %s", sanitize(query.agentId()),
                    sanitize(e.getMessage()));
            return null;
        }
    }

    /** An agent's daily rows, cached for {@code eddi.health.cache-ttl}. */
    private List<HealthTurnAggregate> daily(String agentId, LocalDate from, LocalDate to) {
        return cache.get(List.of("daily", agentId, from, to), () -> dailyStore.find(agentId, from, to));
    }

    /**
     * People and sessions of a window, or null when it reaches past the turn
     * retention.
     */
    HealthUsage usage(String agentId, Window window, Integer version, List<String> origins) {
        LocalDate oldest = oldestTurnDay();
        if (window.from().isBefore(oldest)) {
            return null;
        }
        // The same length of look-back for every window (clipped to the retention),
        // so a current period is not judged "more returning" than its baseline just
        // because it had more history behind it.
        LocalDate lookback = window.from().minusDays(RETURNING_LOOKBACK_DAYS);
        LocalDate from = lookback.isBefore(oldest) ? oldest : lookback;
        HealthUsageQuery query = new HealthUsageQuery(agentId, window.from(), window.to(), version, origins);
        return cache.get(List.of("usage", query, from), () -> turnStore.usage(query, from));
    }

    /**
     * Ratings of a window: conversation ratings (score) and reply ratings (thumbs).
     * Agent-test conversations rate on the {@code test} environment, so unless the
     * caller asked for {@code agent-test} only production ratings count. Ratings
     * have no origin; the origin filter does not narrow them further.
     */
    RatingStats ratings(String agentId, Window window, Integer version, List<String> origins) {
        return cache.get(List.of("ratings", agentId, window, version == null ? "" : version, origins.contains(HealthOrigins.AGENT_TEST)),
                () -> readRatings(agentId, window, version, origins));
    }

    private RatingStats readRatings(String agentId, Window window, Integer version, List<String> origins) {
        try {
            Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = window.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            String environment = origins.contains(HealthOrigins.AGENT_TEST) ? null : Deployment.Environment.production.name();
            RatingSummary conversations = first(ratingStore.summarize(
                    new RatingFilter(agentId, version, environment, from, to, null, null, RatingKind.CONVERSATION, null, null), RatingGroupBy.NONE));
            RatingSummary replies = first(ratingStore.summarize(
                    new RatingFilter(agentId, version, environment, from, to, null, null, RatingKind.MESSAGE, null, null), RatingGroupBy.NONE));
            long up = replies == null || replies.distribution() == null ? 0 : replies.distribution().thumbsUp();
            long down = replies == null || replies.distribution() == null ? 0 : replies.distribution().thumbsDown();
            return new RatingStats(conversations == null ? 0 : conversations.count(), conversations == null ? null : conversations.avgNormalized(),
                    up, down);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the ratings of agent %s for Agent Health: %s", sanitize(agentId), sanitize(e.getMessage()));
            return null;
        }
    }

    private static RatingSummary first(List<RatingSummary> summaries) {
        return summaries == null || summaries.isEmpty() ? null : summaries.get(0);
    }

    /**
     * The last day the rollup has fully processed for the agent: the day before the
     * earliest day that has waited longer than one rollup interval, else today. A
     * day marked a moment ago (any active agent's today) is merely waiting for the
     * next pass, not behind.
     */
    LocalDate dataThrough(String agentId) {
        try {
            Instant overdue = clock.instant().minus(settings.rollupInterval());
            return dirtyStore.earliestPending(agentId, overdue).map(d -> d.minusDays(1)).filter(d -> d.isBefore(today())).orElse(today());
        } catch (RuntimeException e) {
            return today().minusDays(1);
        }
    }

    /**
     * The golden-suite check: the newest finished agent-test run of the version
     * deployed to production (else of any version, when none is deployed).
     * {@code PASS} / {@code FAIL}; {@code UNKNOWN} when no run finished — an
     * {@code ERROR}, {@code ABORTED} or {@code CANCELLED} run says nothing about
     * the agent. {@code stale} after {@link HealthDefaults#GOLDEN_STALE_AFTER}.
     */
    Check goldenSuite(String agentId) {
        Integer deployed = deployedVersion(agentId);
        try {
            for (AgentTestRun run : testRunStore.listByAgent(agentId, 0, TEST_RUNS_SCANNED)) {
                if (deployed != null && !deployed.equals(run.agentVersion())) {
                    continue;
                }
                if (run.status() != AgentTestRun.Status.PASSED && run.status() != AgentTestRun.Status.FAILED) {
                    continue;
                }
                Long finished = run.finishedAt() != null ? run.finishedAt() : run.startedAt();
                Instant at = finished == null ? null : Instant.ofEpochMilli(finished);
                boolean stale = at == null || at.isBefore(clock.instant().minus(HealthDefaults.GOLDEN_STALE_AFTER));
                return new Check("goldenSuite", "Golden conversations", run.status() == AgentTestRun.Status.PASSED ? "PASS" : "FAIL",
                        at == null ? null : at.toString(), stale, run.agentVersion());
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read the agent test runs of %s: %s", sanitize(agentId), e.getMessage());
        }
        return new Check("goldenSuite", "Golden conversations", "UNKNOWN", null, false, deployed);
    }

    /** The highest version deployed to production, or null. */
    Integer deployedVersion(String agentId) {
        try {
            Integer best = null;
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (agentId.equals(info.getAgentId()) && info.getEnvironment() == Deployment.Environment.production && info.getAgentVersion() != null
                        && (best == null || info.getAgentVersion() > best)) {
                    best = info.getAgentVersion();
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ series

    /** The measure ids a series accepts: every known one. */
    public static boolean knownMeasure(String id) {
        return HealthDefaults.of(id) != null;
    }

    /**
     * {@code GET /agents/{agentId}/health/series}: one point per day for each
     * measure (and group). A day without data is a point with {@code value: null}
     * and {@code n: 0}. Rating measures exist per day only ungrouped; outcome
     * measures count the sessions that ended on the day; returning users have no
     * daily points.
     */
    public HealthSeries series(Scope scope, List<String> measures, String groupBy, boolean qualityAllowed) {
        Window window = scope.window();
        List<HealthTurnAggregate> rows = daily(scope.agentId(), window.from(), window.to());
        String preset = preset(rows);
        List<String> origins = scope.origins() != null ? scope.origins() : HealthPeriods.defaultOrigins(preset);
        List<HealthTurnAggregate> chosen = rows.stream().filter(selected(scope.version(), origins)).toList();

        Map<String, Map<LocalDate, List<HealthTurnAggregate>>> rowsByGroup = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<String, Map<LocalDate, HealthUsagePoint>> usageByGroup = new HashMap<>();
        for (HealthTurnAggregate row : chosen) {
            rowsByGroup.computeIfAbsent(groupOf(row, groupBy), k -> new TreeMap<>()).computeIfAbsent(row.day(), k -> new ArrayList<>()).add(row);
        }
        boolean needsUsage = measures.stream().anyMatch(HealthReportService::usesUsage);
        if (needsUsage && !window.from().isBefore(oldestTurnDay())) {
            HealthUsageQuery usageQuery = new HealthUsageQuery(scope.agentId(), window.from(), window.to(), scope.version(), origins);
            for (HealthUsagePoint point : cache.get(List.of("dailyUsage", usageQuery, groupBy == null ? "none" : groupBy),
                    () -> turnStore.dailyUsage(usageQuery, groupBy))) {
                usageByGroup.computeIfAbsent(point.group(), k -> new HashMap<>()).put(point.day(), point);
                rowsByGroup.computeIfAbsent(point.group(), k -> new TreeMap<>());
            }
        }
        if (rowsByGroup.isEmpty()) {
            rowsByGroup.put(null, new TreeMap<>());
        }
        Map<String, Map<LocalDate, List<HealthOutcomeCount>>> outcomesByGroup = new HashMap<>();
        boolean outcomesAvailable = false;
        if (measures.stream().anyMatch(HealthReportService::usesOutcomes)) {
            List<HealthOutcomeCount> counts = outcomeCounts(
                    new HealthUsageQuery(scope.agentId(), window.from(), window.to(), scope.version(), origins), true, groupBy);
            if (counts != null) {
                outcomesAvailable = true;
                for (HealthOutcomeCount c : counts) {
                    outcomesByGroup.computeIfAbsent(c.group(), k -> new HashMap<>()).computeIfAbsent(c.day(), k -> new ArrayList<>()).add(c);
                    rowsByGroup.computeIfAbsent(c.group(), k -> new TreeMap<>());
                }
            }
        }
        String pendingHint = outcomesAvailable ? outcomesPendingHint(window) : null;
        boolean grouped = groupBy != null && !"none".equals(groupBy);
        Map<LocalDate, RatingStats> ratingsByDay = !grouped && qualityAllowed && measures.stream().anyMatch(HealthReportService::usesRatings)
                ? dailyRatings(scope.agentId(), window, scope.version(), origins)
                : Map.of();
        boolean usageAvailable = !window.from().isBefore(oldestTurnDay());

        List<Series> series = new ArrayList<>();
        for (String measure : measures) {
            String unit = HealthDefaults.of(measure).unit();
            boolean restricted = HealthDefaults.QUALITY.equals(HealthDefaults.of(measure).section()) && !qualityAllowed;
            for (Map.Entry<String, Map<LocalDate, List<HealthTurnAggregate>>> group : rowsByGroup.entrySet()) {
                Map<LocalDate, HealthUsagePoint> usage = usageByGroup.getOrDefault(group.getKey(), Map.of());
                Map<LocalDate, List<HealthOutcomeCount>> outcomeDays = outcomesByGroup.getOrDefault(group.getKey(), Map.of());
                List<SeriesPoint> points = new ArrayList<>(window.days());
                for (LocalDate day = window.from(); !day.isAfter(window.to()); day = day.plusDays(1)) {
                    if (restricted) {
                        points.add(new SeriesPoint(day.toString(), null, 0));
                        continue;
                    }
                    List<HealthTurnAggregate> dayRows = group.getValue().getOrDefault(day, List.of());
                    HealthUsagePoint u = usage.get(day);
                    HealthUsage dayUsage = !usageAvailable
                            ? null
                            : u == null ? HealthUsage.EMPTY : new HealthUsage(u.sessions(), u.engagedSessions(), u.turns(), u.distinctUsers(), 0);
                    RatingStats ratings = grouped ? null : ratingsByDay.getOrDefault(day, qualityAllowed ? RatingStats.EMPTY : null);
                    List<HealthOutcomeCount> dayOutcomes = outcomeDays.getOrDefault(day, List.of());
                    OutcomeStats outcomes = outcomesAvailable
                            ? OutcomeStats.of(dayOutcomes, settings.minOutcomeCoverage()).withPending(pendingHint)
                            : null;
                    Measure m = HealthMeasures.pointOf(measure, new PeriodData(Totals.of(dayRows), dayUsage, ratings, 1.0, outcomes));
                    points.add(point(measure, day, m, dayRows, u, dayOutcomes));
                }
                series.add(new Series(measure, grouped ? group.getKey() : null, unit, points));
            }
        }
        return new HealthSeries(scope.agentId(), period(window), series,
                markers(scope.agentId(), window, scope.markerTenants(), firstTurnOrigins(scope.origins())));
    }

    /**
     * One series point. A day with no turn rows and no sessions is {@code value:
     * null, n: 0} — counts included, so "no traffic" never reads as a measured 0 —
     * and returning users have no daily value.
     */
    private static SeriesPoint point(String measure, LocalDate day, Measure m, List<HealthTurnAggregate> rows, HealthUsagePoint usage,
                                     List<HealthOutcomeCount> outcomes) {
        boolean empty = rows.isEmpty() && (usage == null || usage.sessions() == 0) && (outcomes == null || outcomes.isEmpty());
        if (m == null || "returningUserShare".equals(measure) || empty && !usesRatings(measure)) {
            return new SeriesPoint(day.toString(), null, 0);
        }
        return new SeriesPoint(day.toString(), m.value(), m.value() == null ? 0 : m.n());
    }

    private static String groupOf(HealthTurnAggregate row, String groupBy) {
        if ("version".equals(groupBy)) {
            return row.agentVersion() == null ? null : String.valueOf(row.agentVersion());
        }
        if ("origin".equals(groupBy)) {
            return row.origin();
        }
        return null;
    }

    private static boolean usesUsage(String measure) {
        return Set.of("sessions", "engagedSessions", "distinctUsers", "turnsPerSession", "costPerSession", "ratingResponseRate").contains(measure);
    }

    /** Measures read (in part) from the session outcome rows. */
    static boolean usesOutcomes(String measure) {
        return Set.of("resolutionRate", "escalationRate", "abandonmentRate", "deflectionRate", "outcomeCoverage", "costPerResolvedSession",
                "sessions", "engagedSessions", "turnsPerSession", "costPerSession", "ratingResponseRate").contains(measure);
    }

    private static boolean usesRatings(String measure) {
        return Set.of("ratingScore", "ratingResponseRate", "replyThumbsUp").contains(measure);
    }

    private Map<LocalDate, RatingStats> dailyRatings(String agentId, Window window, Integer version, List<String> origins) {
        try {
            Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = window.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            String environment = origins.contains(HealthOrigins.AGENT_TEST) ? null : Deployment.Environment.production.name();
            Map<LocalDate, RatingStats> byDay = new HashMap<>();
            for (RatingSummary s : ratingStore.summarize(
                    new RatingFilter(agentId, version, environment, from, to, null, null, RatingKind.CONVERSATION, null, null), RatingGroupBy.DAY)) {
                byDay.put(LocalDate.parse(s.bucket()), new RatingStats(s.count(), s.avgNormalized(), 0, 0));
            }
            for (RatingSummary s : ratingStore.summarize(
                    new RatingFilter(agentId, version, environment, from, to, null, null, RatingKind.MESSAGE, null, null), RatingGroupBy.DAY)) {
                LocalDate day = LocalDate.parse(s.bucket());
                RatingStats c = byDay.getOrDefault(day, RatingStats.EMPTY);
                long up = s.distribution() == null ? 0 : s.distribution().thumbsUp();
                long down = s.distribution() == null ? 0 : s.distribution().thumbsDown();
                byDay.put(day, new RatingStats(c.conversationRatings(), c.conversationAverage(), up, down));
            }
            return byDay;
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read the daily ratings of agent %s for Agent Health: %s", sanitize(agentId), sanitize(e.getMessage()));
            return Map.of();
        }
    }

    /** The agent's own markers read per series, at most. */
    static final int MAX_AGENT_MARKERS = 150;
    /**
     * Deployment-wide markers read per series, at most (they are coalesced per
     * hour).
     */
    static final int MAX_GLOBAL_MARKERS = 50;

    /**
     * The change markers of a window: the agent's own, the deployment-wide ones of
     * the agent's tenants, and the derived first turn of each version. Read
     * separately and newest first, so a flood of one kind cannot crowd out the
     * agent's deployments; returned oldest first, at most {@link #MAX_MARKERS} (the
     * newest).
     */
    List<Marker> markers(String agentId, Window window, List<String> tenants, List<String> firstTurnOrigins) {
        List<HealthMarker> found = new ArrayList<>();
        Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = window.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        try {
            found.addAll(markerStore.findForAgent(agentId, from, to, MAX_AGENT_MARKERS));
            found.addAll(markerStore.findGlobal(tenants == null ? List.of(HealthMarker.DEFAULT_TENANT) : tenants, from, to, MAX_GLOBAL_MARKERS));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read Agent Health markers: %s", e.getMessage());
        }
        try {
            firstDays(agentId, firstTurnOrigins).forEach((version, day) -> {
                if (window.contains(day)) {
                    found.add(new HealthMarker("vft-" + version, agentId, day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                            HealthMarker.KIND_VERSION_FIRST_TURN, "First turn on v" + version, version));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not derive version markers: %s", e.getMessage());
        }
        found.sort(Comparator.comparing(HealthMarker::at).thenComparing(HealthMarker::id).reversed());
        List<HealthMarker> newest = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_MARKERS)));
        Collections.reverse(newest);
        List<Marker> markers = new ArrayList<>();
        for (HealthMarker m : newest) {
            markers.add(new Marker(m.id(), m.at().toString(), m.kind(), m.label(), m.version()));
        }
        return markers;
    }

    private Map<Integer, LocalDate> firstDays(String agentId, List<String> origins) {
        return cache.get(List.of("firstDays", agentId, origins), () -> dailyStore.firstDayByVersion(agentId, origins));
    }

    /**
     * The origins a version's first turn is looked for in: the ones asked for, else
     * every origin but {@code agent-test} — a golden-suite run before a version
     * went live is not its first turn.
     */
    static List<String> firstTurnOrigins(List<String> requested) {
        return requested != null ? requested : HealthPeriods.defaultOrigins(HealthDefaults.PRESET_TASK);
    }

    // ------------------------------------------------------------------ compare

    /**
     * {@code GET /agents/{agentId}/health/compare}: the newer of the two versions
     * since its first turn (at most {@value HealthPeriods#MAX_DAYS} days), the
     * older one over its last {@code days} days before the newer took over. A
     * version without turns gets empty sections, never an error. Each side is
     * computed on its own (no baseline); {@code deltas} lists every measure with a
     * value on both sides.
     */
    public HealthCompare compare(String agentId, int fromVersion, int toVersion, int days, List<String> requestedOrigins,
                                 boolean qualityAllowed) {
        int newer = Math.max(fromVersion, toVersion);
        int older = Math.min(fromVersion, toVersion);
        LocalDate today = today();
        Map<Integer, LocalDate> firstDays = firstDays(agentId, firstTurnOrigins(requestedOrigins));
        LocalDate newerStart = firstDays.get(newer);
        Window newerWindow;
        Window olderWindow;
        if (newerStart == null) {
            newerWindow = new Window(today, today);
            olderWindow = new Window(today.minusDays(days - 1L), today);
        } else {
            LocalDate start = newerStart.isBefore(today.minusDays(HealthPeriods.MAX_DAYS - 1L))
                    ? today.minusDays(HealthPeriods.MAX_DAYS - 1L)
                    : newerStart;
            newerWindow = new Window(start, today);
            LocalDate olderEnd = newerStart.minusDays(1);
            olderWindow = new Window(olderEnd.minusDays(days - 1L), olderEnd);
        }
        LocalDate earliest = olderWindow.from().isBefore(newerWindow.from()) ? olderWindow.from() : newerWindow.from();
        List<HealthTurnAggregate> rows = daily(agentId, earliest, today);
        String preset = preset(rows);
        List<String> origins = requestedOrigins != null ? requestedOrigins : HealthPeriods.defaultOrigins(preset);

        Window fromWindow = fromVersion == newer ? newerWindow : olderWindow;
        Window toWindow = toVersion == newer ? newerWindow : olderWindow;
        PeriodData fromData = periodData(agentId, fromWindow, fromVersion, origins, rows, selected(fromVersion, origins), qualityAllowed);
        PeriodData toData = periodData(agentId, toWindow, toVersion, origins, rows, selected(toVersion, origins), qualityAllowed);
        return new HealthCompare(agentId,
                new CompareSide(fromVersion, range(fromWindow), HealthMeasures.sections(fromData, null, !qualityAllowed)),
                new CompareSide(toVersion, range(toWindow), HealthMeasures.sections(toData, null, !qualityAllowed)),
                HealthMeasures.deltas(fromData, toData, !qualityAllowed));
    }

    // ------------------------------------------------------------------ helpers

    static Period period(Window window) {
        return new Period(window.from().toString(), window.to().toString(), window.days());
    }

    static DateRange range(Window window) {
        return new DateRange(window.from().toString(), window.to().toString());
    }

    /**
     * For the portfolio: the daily rows of many agents. Cached per agent and range,
     * so a page of the portfolio and the next one read the database once; the
     * caller's VIEW filtering happens before (on the agent list), never inside the
     * cache.
     */
    List<HealthTurnAggregate> dailyRows(List<String> agentIds, LocalDate from, LocalDate to) {
        List<HealthTurnAggregate> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, List<HealthTurnAggregate>> cached = new LinkedHashMap<>();
        for (String agentId : agentIds) {
            List<HealthTurnAggregate> hit = cache.peek(List.of("daily", agentId, from, to));
            if (hit == null) {
                missing.add(agentId);
            } else {
                cached.put(agentId, hit);
            }
        }
        if (!missing.isEmpty()) {
            // Identical portfolio requests arriving together share this one read.
            Map<String, List<HealthTurnAggregate>> read = cache.shared(List.of("dailyBatch", List.copyOf(missing), from, to),
                    () -> byAgent(dailyStore.findForAgents(missing, from, to)));
            for (String agentId : missing) {
                List<HealthTurnAggregate> agentRows = read.getOrDefault(agentId, List.of());
                cache.put(List.of("daily", agentId, from, to), agentRows);
                cached.put(agentId, agentRows);
            }
        }
        cached.values().forEach(rows::addAll);
        return rows;
    }

    /**
     * For the portfolio: every agent's traffic of the window and its baseline in
     * one pass over the turn rows, cached per agent the same way. A window that
     * reaches past the turn-row retention cannot be counted: the window's counts
     * are then {@link HealthPortfolioSessions#UNCOUNTED}, and a baseline that does
     * (the 90-day portfolio's) leaves only the baseline's counts {@code null}.
     */
    Map<String, HealthPortfolioSessions> portfolioSessions(List<String> agentIds, Window window) {
        Window baseline = window.baseline();
        LocalDate oldest = oldestTurnDay();
        if (window.from().isBefore(oldest)) {
            Map<String, HealthPortfolioSessions> none = new LinkedHashMap<>();
            agentIds.forEach(id -> none.put(id, HealthPortfolioSessions.UNCOUNTED));
            return none;
        }
        boolean baselineCountable = !baseline.from().isBefore(oldest);
        // Without a countable baseline, read the window alone (no baseline days).
        LocalDate readFrom = baselineCountable ? baseline.from() : window.from();
        List<String> all = HealthPeriods.defaultOrigins(HealthDefaults.PRESET_TASK);
        Map<String, HealthPortfolioSessions> sessions = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String agentId : agentIds) {
            HealthPortfolioSessions hit = cache.peek(List.of("portfolioSessions", agentId, window));
            if (hit == null) {
                missing.add(agentId);
            } else {
                sessions.put(agentId, hit);
            }
        }
        if (!missing.isEmpty()) {
            // Real sessions once outcomes exist; the turn rows' conversations without them.
            // Either way only the countable days are read (readFrom).
            Map<String, HealthPortfolioSessions> read = cache.shared(List.of("portfolioSessionsBatch", List.copyOf(missing), window),
                    () -> outcomeStore != null
                            ? outcomeStore.portfolioSessions(missing, readFrom, window.from(), window.to(), HealthOrigins.INTERACTIVE, all)
                            : turnStore.portfolioSessions(missing, readFrom, window.from(), window.to(), HealthOrigins.INTERACTIVE, all));
            for (String agentId : missing) {
                HealthPortfolioSessions counted = read.getOrDefault(agentId, HealthPortfolioSessions.NONE);
                HealthPortfolioSessions agentSessions = baselineCountable ? counted : counted.withoutBaseline();
                cache.put(List.of("portfolioSessions", agentId, window), agentSessions);
                sessions.put(agentId, agentSessions);
            }
        }
        return sessions;
    }

    /** The daily rows grouped by agent, for the portfolio. */
    static Map<String, List<HealthTurnAggregate>> byAgent(List<HealthTurnAggregate> rows) {
        Map<String, List<HealthTurnAggregate>> out = new LinkedHashMap<>();
        rows.forEach(r -> out.computeIfAbsent(r.agentId(), k -> new ArrayList<>()).add(r));
        return out;
    }
}
