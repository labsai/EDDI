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
 * (90 days by default), once a day.
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
    private final HealthSettings settings;
    private final Clock clock;

    @Inject
    public HealthTurnRetention(IHealthTurnStore store, HealthSettings settings) {
        this(store, settings, Clock.systemUTC());
    }

    HealthTurnRetention(IHealthTurnStore store, HealthSettings settings, Clock clock) {
        this.store = store;
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
    }

    /**
     * The first UTC day kept: today minus the retention in days. Rows of earlier
     * days are deleted.
     */
    LocalDate cutoff() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(settings.factsRetentionDays());
    }
}
