/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Deletes Agent Health turn rows older than {@code eddi.health.facts-retention}
 * (90 days by default), and daily rows and change markers older than
 * {@code eddi.health.daily-retention} (730 days), once a day.
 * <p>
 * <b>Why a plain {@code @Scheduled} sweep and not a
 * {@code ScheduleFireExecutor} system job</b> (the {@code alerts-maintenance}
 * pattern): the system-job path exists for work that must run on exactly one
 * node — a CAS claim, a fire log, retries. This sweep needs none of it. It is
 * one indexed {@code delete where day < cutoff}, idempotent (a second node
 * deleting the same rows deletes nothing), cheap, and harmless when missed (the
 * next day's sweep takes what was left; nothing reads past the retention
 * anyway). That is exactly the shape {@code ToolQuotaMaintenance} uses for the
 * same reason. The H2b rollups, which recompute and must not run twice, are the
 * ones that will take the system-job path.
 * <p>
 * Runs whether or not {@code eddi.health.enabled} is set: switching collection
 * off must not keep old rows forever.
 */
@ApplicationScoped
public class HealthTurnRetention {

    private static final Logger LOGGER = Logger.getLogger(HealthTurnRetention.class);

    private final IHealthTurnStore store;
    private final IHealthDailyStore dailyStore;
    private final IHealthMarkerStore markerStore;
    private final HealthSettings settings;
    private final Clock clock;

    @Inject
    public HealthTurnRetention(IHealthTurnStore store, IHealthDailyStore dailyStore, IHealthMarkerStore markerStore, HealthSettings settings) {
        this(store, dailyStore, markerStore, settings, Clock.systemUTC());
    }

    /** Turn rows only — for tests. */
    HealthTurnRetention(IHealthTurnStore store, HealthSettings settings, Clock clock) {
        this(store, null, null, settings, clock);
    }

    HealthTurnRetention(IHealthTurnStore store, IHealthDailyStore dailyStore, IHealthMarkerStore markerStore, HealthSettings settings,
            Clock clock) {
        this.store = store;
        this.dailyStore = dailyStore;
        this.markerStore = markerStore;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(every = "24h", delayed = "15m", identity = "health-turn-retention")
    void sweep() {
        LocalDate cutoff = cutoff();
        try {
            long deleted = store.deleteOlderThan(cutoff);
            if (deleted > 0) {
                LOGGER.infof("Deleted %d Agent Health turn row(s) from before %s", deleted, cutoff);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Failed to delete expired Agent Health turn rows; the next daily sweep retries");
        }
        LocalDate dailyCutoff = dailyCutoff();
        if (dailyStore != null) {
            try {
                long deleted = dailyStore.deleteOlderThan(dailyCutoff);
                if (deleted > 0) {
                    LOGGER.infof("Deleted %d Agent Health daily row(s) from before %s", deleted, dailyCutoff);
                }
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Failed to delete expired Agent Health daily rows; the next daily sweep retries");
            }
        }
        if (markerStore != null) {
            try {
                markerStore.deleteOlderThan(dailyCutoff.atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Failed to delete expired Agent Health change markers; the next daily sweep retries");
            }
        }
    }

    /** The first UTC day whose daily rows and markers are kept. */
    LocalDate dailyCutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.dailyRetentionDays());
    }

    /**
     * The first UTC day kept: today minus the retention in days. Rows of earlier
     * days are deleted.
     */
    LocalDate cutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
    }
}
