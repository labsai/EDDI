/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Retention for the persisted database logs
 * ({@code eddi.logs.db-retention-days}).
 * <p>
 * The compliance pages described database-log retention as "configurable" while
 * nothing in the code ever deleted a log entry: the {@code logs} collection /
 * {@code database_logs} table grew for the life of the deployment, holding user
 * ids (pseudonymised only on erasure) and whatever WARN/ERROR messages said
 * about a turn. This is the missing sweep. It follows the two retention sweeps
 * that already exist — ended conversations and user memories in
 * {@code RestConversationStore} — rather than adding a scheduler of its own: a
 * daily Quarkus {@code @Scheduled} job, idempotent, so every replica can run it
 * without coordination.
 * <p>
 * Off by default ({@code -1}). Switching it on deletes history, and a sweep
 * that starts deleting on the first boot after an upgrade is exactly the
 * surprise the EDDI 5 conversation-retention hold exists to prevent.
 */
@ApplicationScoped
public class DatabaseLogRetention {

    private static final Logger LOGGER = Logger.getLogger(DatabaseLogRetention.class);

    private final IDatabaseLogs databaseLogs;
    private final int retentionDays;
    private final boolean dbEnabled;
    private final Counter deletedCounter;

    @Inject
    public DatabaseLogRetention(IDatabaseLogs databaseLogs,
            @ConfigProperty(name = "eddi.logs.db-retention-days", defaultValue = "-1") int retentionDays,
            @ConfigProperty(name = "eddi.logs.db-enabled", defaultValue = "true") boolean dbEnabled,
            MeterRegistry meterRegistry) {
        this.databaseLogs = databaseLogs;
        this.retentionDays = retentionDays;
        this.dbEnabled = dbEnabled;
        this.deletedCounter = meterRegistry.counter("eddi.logs.db.retention.deleted");
    }

    @Scheduled(every = "${eddi.logs.db-retention-interval:24h}", delayed = "${eddi.logs.db-retention-initial-delay:4m}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledSweep() {
        sweep();
    }

    /**
     * Delete the persisted log entries older than the configured retention.
     * <p>
     * Runs even when {@code eddi.logs.db-enabled=false}: turning persistence off
     * stops new writes, it does not make the entries already stored exempt from the
     * retention an operator set.
     *
     * @return entries deleted; 0 when retention is off or the sweep failed
     */
    long sweep() {
        if (retentionDays <= 0) {
            LOGGER.debugf("Database log retention is off (eddi.logs.db-retention-days=%d)", retentionDays);
            return 0;
        }
        try {
            long deleted = databaseLogs.deleteOlderThan(retentionDays);
            deletedCounter.increment(deleted);
            if (deleted > 0) {
                LOGGER.infof("Database log retention: deleted %d log entries older than %d days%s", deleted, retentionDays,
                        dbEnabled ? "" : " (persistence is off; these were written earlier)");
            }
            return deleted;
        } catch (Exception e) {
            LOGGER.errorf(e, "Database log retention sweep failed (eddi.logs.db-retention-days=%d); it is retried on the next run",
                    retentionDays);
            return 0;
        }
    }
}
