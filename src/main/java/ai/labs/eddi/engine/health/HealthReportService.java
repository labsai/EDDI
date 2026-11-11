/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.agenttests.IAgentTestRunStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.health.HealthMeasures.PeriodData;
import ai.labs.eddi.engine.health.HealthMeasures.RatingStats;
import ai.labs.eddi.engine.health.HealthMeasures.Totals;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthMarker;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * ratings.</li>
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

    @Inject
    public HealthReportService(IHealthTurnStore turnStore, IHealthDailyStore dailyStore, IHealthDirtyDayStore dirtyStore,
            IHealthMarkerStore markerStore, IConversationRatingStore ratingStore, IAgentTestRunStore testRunStore,
            IDeploymentStore deploymentStore, HealthSettings settings) {
        this(turnStore, dailyStore, dirtyStore, markerStore, ratingStore, testRunStore, deploymentStore, settings, Clock.systemUTC());
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
    public record Scope(String agentId, Window window, Integer version, List<String> origins) {
    }

    // ------------------------------------------------------------------ report

    /** {@code GET /agents/{agentId}/health}. */
    public AgentHealth report(Scope scope, String agentName, boolean qualityAllowed) {
        Window window = scope.window();
        Window baseline = window.baseline();
        List<HealthTurnAggregate> rows = dailyStore.find(scope.agentId(), baseline.from(), window.to());
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
        return new PeriodData(totals, usage, ratings);
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
        return turnStore.usage(new HealthUsageQuery(agentId, window.from(), window.to(), version, origins), oldest);
    }

    /**
     * Ratings of a window: conversation ratings (score) and reply ratings (thumbs).
     * Agent-test conversations rate on the {@code test} environment, so unless the
     * caller asked for {@code agent-test} only production ratings count. Ratings
     * have no origin; the origin filter does not narrow them further.
     */
    RatingStats ratings(String agentId, Window window, Integer version, List<String> origins) {
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

    /** The last day the rollup has processed for the agent. */
    LocalDate dataThrough(String agentId) {
        try {
            return dirtyStore.earliestPending(agentId).map(d -> d.minusDays(1)).filter(d -> d.isBefore(today())).orElse(today());
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
     * and {@code n: 0}. Rating measures exist per day only ungrouped; measures that
     * need outcomes, and returning users, have no daily points yet.
     */
    public HealthSeries series(Scope scope, List<String> measures, String groupBy, boolean qualityAllowed) {
        Window window = scope.window();
        List<HealthTurnAggregate> rows = dailyStore.find(scope.agentId(), window.from(), window.to());
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
            for (HealthUsagePoint point : turnStore
                    .dailyUsage(new HealthUsageQuery(scope.agentId(), window.from(), window.to(), scope.version(), origins), groupBy)) {
                usageByGroup.computeIfAbsent(point.group(), k -> new HashMap<>()).put(point.day(), point);
                rowsByGroup.computeIfAbsent(point.group(), k -> new TreeMap<>());
            }
        }
        if (rowsByGroup.isEmpty()) {
            rowsByGroup.put(null, new TreeMap<>());
        }
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
                    Measure m = HealthMeasures.pointOf(measure, new PeriodData(Totals.of(dayRows), dayUsage, ratings));
                    points.add(point(measure, day, m, dayRows, u));
                }
                series.add(new Series(measure, grouped ? group.getKey() : null, unit, points));
            }
        }
        return new HealthSeries(scope.agentId(), period(window), series, markers(scope.agentId(), window));
    }

    /**
     * One series point. A day with no turn rows and no sessions is {@code value:
     * null, n: 0} — counts included, so "no traffic" never reads as a measured 0 —
     * and returning users have no daily value.
     */
    private static SeriesPoint point(String measure, LocalDate day, Measure m, List<HealthTurnAggregate> rows, HealthUsagePoint usage) {
        boolean empty = rows.isEmpty() && (usage == null || usage.sessions() == 0);
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

    /**
     * The change markers of a window: the stored ones (the agent's and the
     * deployment-wide ones) and the derived first turn of each version.
     */
    List<Marker> markers(String agentId, Window window) {
        List<HealthMarker> found = new ArrayList<>();
        Instant from = window.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = window.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        try {
            found.addAll(markerStore.find(agentId, from, to, MAX_MARKERS));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read Agent Health markers: %s", e.getMessage());
        }
        try {
            dailyStore.firstDayByVersion(agentId).forEach((version, day) -> {
                if (window.contains(day)) {
                    found.add(new HealthMarker("vft-" + version, agentId, day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                            HealthMarker.KIND_VERSION_FIRST_TURN, "First turn on v" + version, version));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not derive version markers: %s", e.getMessage());
        }
        found.sort(Comparator.comparing(HealthMarker::at).thenComparing(HealthMarker::id));
        List<Marker> markers = new ArrayList<>();
        for (HealthMarker m : found.subList(0, Math.min(found.size(), MAX_MARKERS))) {
            markers.add(new Marker(m.id(), m.at().toString(), m.kind(), m.label(), m.version()));
        }
        return markers;
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
        Map<Integer, LocalDate> firstDays = dailyStore.firstDayByVersion(agentId);
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
        List<HealthTurnAggregate> rows = dailyStore.find(agentId, earliest, today);
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

    /** For the portfolio: the daily rows of many agents. */
    List<HealthTurnAggregate> dailyRows(List<String> agentIds, LocalDate from, LocalDate to) {
        return dailyStore.findForAgents(agentIds, from, to);
    }

    /** For the portfolio: sessions per agent. */
    Map<String, Long> sessionsByAgent(List<String> agentIds, Window window, List<String> origins) {
        if (window.from().isBefore(oldestTurnDay())) {
            return Map.of();
        }
        return turnStore.sessionsByAgent(agentIds, window.from(), window.to(), origins);
    }

    /** The daily rows grouped by agent, for the portfolio. */
    static Map<String, List<HealthTurnAggregate>> byAgent(List<HealthTurnAggregate> rows) {
        Map<String, List<HealthTurnAggregate>> out = new LinkedHashMap<>();
        rows.forEach(r -> out.computeIfAbsent(r.agentId(), k -> new ArrayList<>()).add(r));
        return out;
    }
}
