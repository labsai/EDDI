/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * One pass of the {@value HealthSchedules#SCHEDULE_NAME} system job.
 * <ol>
 * <li><b>Outcomes</b> (H3): the session outcome evaluation
 * ({@link HealthOutcomeEvaluator}) runs first, within {@link #OUTCOME_BUDGET}
 * of the pass — under the same schedule claim, so one node judges sessions at a
 * time without a job of its own. Outcomes are read from their own rows by the
 * views, so they mark no day dirty.</li>
 * <li><b>Self-heal</b>: re-marks dirty the (agent, day) pairs of turn rows
 * written, or discarded by an undo, since the last pass (a durable watermark in
 * the dirty store, minus a {@link #SELF_HEAL_GRACE} so a row whose own mark is
 * still on its way is covered next time). A mark lost on a node — killed before
 * its debounced flush — therefore never leaves a day stale; the re-mark of a
 * day already dirty only gives its entry a new token. At most
 * {@link #SELF_HEAL_MAX} pairs per pass: a window holding more is halved until
 * one fits, and the watermark moves only to the end of what was marked. A row
 * that reaches the store after the grace has passed its capture time marks its
 * days before it is written instead ({@link HealthDirtyDays#markLateWrite}),
 * since this scan may already be past it.</li>
 * <li><b>Drain</b>: reads the oldest dirty entries (with their tokens); for
 * each streams the agent-day's turn rows through a
 * {@link HealthTurnAggregator}, {@link IHealthDailyStore#replaceDay replaces}
 * the day's daily rows with the result, then clears the entry <em>only if its
 * token is unchanged</em> — a write that marked it meanwhile keeps it
 * dirty.</li>
 * <li>A day older than the turn-row retention is cleared without recomputing:
 * its turns are gone, and recomputing would wipe a daily row nothing can
 * rebuild.</li>
 * <li>A day whose rollup fails is moved to the back of the queue
 * ({@link IHealthDirtyDayStore#defer}) so it cannot block the entries behind
 * it. It is never dropped — the day may have no later write to mark it again —
 * and past {@link #MAX_ATTEMPTS} failed attempts its WARN says it keeps
 * failing.</li>
 * </ol>
 * <b>Idempotent</b>: a day recomputed twice from the same rows produces the
 * same rows, so a retried pass, a duplicated schedule or two nodes racing do no
 * harm. A pass stops after {@link #TIME_BUDGET} and leaves the rest for the
 * next.
 */
@ApplicationScoped
public class HealthRollupService {

    private static final Logger LOGGER = Logger.getLogger(HealthRollupService.class);

    /** Dirty entries read per batch. */
    static final int BATCH = 200;
    /** How long one pass may run before it leaves the rest for the next. */
    static final Duration TIME_BUDGET = Duration.ofSeconds(90);
    /** Failed rollups of one entry in a row before it is dropped. */
    static final int MAX_ATTEMPTS = 5;
    /** Rows younger than this are left to their own marks (still being flushed). */
    static final Duration SELF_HEAL_GRACE = Duration.ofMinutes(1);
    /** How far back the first self-heal pass looks. */
    static final Duration SELF_HEAL_FIRST_WINDOW = Duration.ofDays(1);
    /** The most (agent, day) pairs one self-heal pass re-marks. */
    static final int SELF_HEAL_MAX = 10_000;
    /**
     * The narrowest window the self-heal halves an overfull one down to; past it,
     * it marks what fits and moves on.
     */
    static final Duration SELF_HEAL_MIN_WINDOW = Duration.ofSeconds(1);

    /** How much of a pass the outcome evaluation may take. */
    static final Duration OUTCOME_BUDGET = Duration.ofSeconds(45);

    /**
     * What one pass did; {@link #isEmpty()} when nothing at all.
     *
     * @param sessions
     *            session outcome rows the outcome evaluation wrote
     */
    public record Result(int days, int rows, int skipped, int failed, long pending, int sessions) {

        public Result(int days, int rows, int skipped, int failed, long pending) {
            this(days, rows, skipped, failed, pending, 0);
        }

        public boolean isEmpty() {
            return days == 0 && skipped == 0 && failed == 0 && sessions == 0;
        }
    }

    /**
     * The outcome evaluation, run at the start of every pass. Field-injected and
     * null-checked: tests build this class by hand.
     */
    @Inject
    Instance<HealthOutcomeEvaluator> outcomeEvaluator;

    private final IHealthTurnStore turnStore;
    private final IHealthDailyStore dailyStore;
    private final IHealthDirtyDayStore dirtyStore;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;

    @Inject
    public HealthRollupService(IHealthTurnStore turnStore, IHealthDailyStore dailyStore, IHealthDirtyDayStore dirtyStore, HealthSettings settings,
            HealthMetrics metrics) {
        this(turnStore, dailyStore, dirtyStore, settings, metrics, Clock.systemUTC());
    }

    HealthRollupService(IHealthTurnStore turnStore, IHealthDailyStore dailyStore, IHealthDirtyDayStore dirtyStore, HealthSettings settings,
            HealthMetrics metrics, Clock clock) {
        this.turnStore = turnStore;
        this.dailyStore = dailyStore;
        this.dirtyStore = dirtyStore;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
        metrics.registerBacklog(dirtyStore, clock);
    }

    public Result run() {
        Instant started = clock.instant();
        int sessions = 0;
        if (outcomeEvaluator != null && outcomeEvaluator.isResolvable()) {
            sessions = outcomeEvaluator.get().run(started.plus(OUTCOME_BUDGET)).sessions();
        }
        selfHeal(clock.instant());
        Instant deadline = started.plus(TIME_BUDGET);
        LocalDate oldestRollable = LocalDate.ofInstant(started, ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
        int days = 0;
        int rows = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        boolean more = true;
        while (more && clock.instant().isBefore(deadline)) {
            List<HealthDirtyDay> batch = dirtyStore.oldest(BATCH);
            more = batch.size() == BATCH;
            int progress = 0;
            for (HealthDirtyDay entry : batch) {
                if (!clock.instant().isBefore(deadline)) {
                    more = false;
                    break;
                }
                if (!seen.add(entry.agentId() + "|" + entry.day())) {
                    // Deferred earlier in this pass and back at the head: next pass.
                    continue;
                }
                try {
                    if (entry.day().isBefore(oldestRollable)) {
                        // Its turn rows are gone; keep the daily rows as they are.
                        dirtyStore.clear(entry);
                        skipped++;
                    } else {
                        rows += rollUp(entry.agentId(), entry.day());
                        dirtyStore.clear(entry);
                        days++;
                    }
                    progress++;
                } catch (RuntimeException e) {
                    failed++;
                    progress++;
                    defer(entry, e);
                }
            }
            if (progress == 0) {
                more = false;
            }
        }
        metrics.rolledUp(days, rows, failed, Duration.between(started, clock.instant()));
        long pending = 0;
        try {
            pending = dirtyStore.count();
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not count dirty Agent Health days: %s", e.getMessage());
        }
        return new Result(days, rows, skipped, failed, pending, sessions);
    }

    /**
     * Moves a failed entry to the back of the queue. It is never dropped: its day
     * may have no later write to mark it again, and the self-heal is already past
     * its rows, so a dropped day would keep stale counts until its retention. A
     * failing day costs one attempt per pass; past {@link #MAX_ATTEMPTS} its WARN
     * says so.
     */
    private void defer(HealthDirtyDay entry, RuntimeException cause) {
        try {
            int attempts = dirtyStore.defer(entry, clock.instant());
            if (attempts >= MAX_ATTEMPTS) {
                LOGGER.warnf("Agent Health rollup of agent %s, day %s has failed %d times; it stays queued and is retried every pass: %s",
                        sanitize(entry.agentId()), entry.day(), attempts, sanitize(cause.getMessage()));
            } else {
                LOGGER.warnf("Agent Health rollup of agent %s, day %s failed (attempt %d, retried later): %s", sanitize(entry.agentId()),
                        entry.day(), attempts, sanitize(cause.getMessage()));
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Agent Health rollup of agent %s, day %s failed and could not be deferred: %s", sanitize(entry.agentId()),
                    entry.day(), sanitize(e.getMessage()));
        }
    }

    /**
     * Re-marks the days of the rows written since the last pass; see the class
     * comment. Never throws: a failure leaves the watermark where it was. A window
     * with more than {@link #SELF_HEAL_MAX} pairs is halved until one fits, and the
     * watermark only moves to the end of the part fully marked; the rest is the
     * next pass's.
     */
    void selfHeal(Instant now) {
        try {
            Instant end = now.minus(SELF_HEAL_GRACE);
            Instant since = dirtyStore.selfHealWatermark().orElse(end.minus(SELF_HEAL_FIRST_WINDOW));
            if (!since.isBefore(end)) {
                return;
            }
            Instant until = end;
            boolean[] overflow = {false};
            Map<String, Set<LocalDate>> pairs = scan(since, until, overflow);
            while (overflow[0] && Duration.between(since, until).compareTo(SELF_HEAL_MIN_WINDOW) > 0) {
                until = since.plus(Duration.between(since, until).dividedBy(2));
                pairs = scan(since, until, overflow);
            }
            if (overflow[0]) {
                // Even the narrowest window holds more: mark what fits and move on.
                LOGGER.warnf("The Agent Health self-heal pass re-marks only %d agent days written in %s .. %s; the others rely on their "
                        + "own marks", SELF_HEAL_MAX, since, until);
            } else if (until.isBefore(end)) {
                LOGGER.infof("The Agent Health self-heal pass re-marked rows written up to %s; the rest, up to %s, is the next pass's", until,
                        end);
            }
            pairs.forEach(dirtyStore::mark);
            dirtyStore.setSelfHealWatermark(until);
        } catch (RuntimeException e) {
            LOGGER.warnf("The Agent Health self-heal pass failed; the next pass retries from the same watermark: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * The (agent, day) pairs of rows written in [{@code since}, {@code until}), at
     * most {@link #SELF_HEAL_MAX}; {@code overflow[0]} says whether there were
     * more.
     */
    private Map<String, Set<LocalDate>> scan(Instant since, Instant until, boolean[] overflow) {
        Map<String, Set<LocalDate>> pairs = new LinkedHashMap<>();
        int[] count = {0};
        turnStore.forEachAgentDayUpdatedSince(since, until, SELF_HEAL_MAX + 1, (agentId, day) -> {
            if (count[0]++ < SELF_HEAL_MAX) {
                pairs.computeIfAbsent(agentId, k -> new TreeSet<>()).add(day);
            }
        });
        overflow[0] = count[0] > SELF_HEAL_MAX;
        return pairs;
    }

    /**
     * Recomputes one agent-day from its turn rows and replaces its daily rows.
     *
     * @return how many daily rows the day has now
     */
    public int rollUp(String agentId, LocalDate day) {
        HealthTurnAggregator aggregator = new HealthTurnAggregator(settings.apdexTMs());
        turnStore.forEachByAgentAndDay(agentId, day, aggregator);
        List<HealthTurnAggregate> result = aggregator.result();
        dailyStore.replaceDay(agentId, day, result);
        return result.size();
    }
}
