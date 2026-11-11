/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDirtyDay;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * One pass of the {@value HealthSchedules#SCHEDULE_NAME} system job: drains the
 * dirty-day queue and recomputes each (agent, day) from its turn rows.
 * <ol>
 * <li>read the oldest dirty entries (with their tokens);</li>
 * <li>for each: stream the agent-day's turn rows through a
 * {@link HealthTurnAggregator}, {@link IHealthDailyStore#replaceDay replace}
 * the day's daily rows with the result, then clear the entry <em>only if its
 * token is unchanged</em> — a write that marked it meanwhile keeps it
 * dirty;</li>
 * <li>a day older than the turn-row retention is cleared without recomputing:
 * its turns are gone, and recomputing would wipe a daily row nothing can
 * rebuild.</li>
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

    /** What one pass did; {@link #isEmpty()} when nothing at all. */
    public record Result(int days, int rows, int skipped, int failed, long pending) {
        public boolean isEmpty() {
            return days == 0 && skipped == 0 && failed == 0;
        }
    }

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
    }

    public Result run() {
        Instant started = clock.instant();
        Instant deadline = started.plus(TIME_BUDGET);
        LocalDate oldestRollable = LocalDate.ofInstant(started, ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
        int days = 0;
        int rows = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> failedKeys = new HashSet<>();
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
                String key = entry.agentId() + "|" + entry.day();
                if (failedKeys.contains(key)) {
                    continue;
                }
                if (entry.day().isBefore(oldestRollable)) {
                    // Its turn rows are gone; keep the daily rows as they are.
                    dirtyStore.clear(entry);
                    skipped++;
                    progress++;
                    continue;
                }
                try {
                    rows += rollUp(entry.agentId(), entry.day());
                    dirtyStore.clear(entry);
                    days++;
                    progress++;
                } catch (RuntimeException e) {
                    failed++;
                    failedKeys.add(key);
                    LOGGER.warnf("Agent Health rollup of agent %s, day %s failed (retried next pass): %s", sanitize(entry.agentId()),
                            entry.day(), sanitize(e.getMessage()));
                }
            }
            if (progress == 0) {
                // Nothing in the batch could be done (it failed now or earlier in this
                // pass): do not spin on the same entries.
                more = false;
            }
        }
        long pending = dirtyStore.count();
        long lag = 0;
        if (pending > 0) {
            List<HealthDirtyDay> oldest = dirtyStore.oldest(1);
            if (!oldest.isEmpty() && oldest.get(0).markedAt() != null) {
                lag = Math.max(0, Duration.between(oldest.get(0).markedAt(), clock.instant()).toSeconds());
            }
        }
        metrics.rolledUp(days, rows, failed, Duration.between(started, clock.instant()), pending, lag);
        return new Result(days, rows, skipped, failed, pending);
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
