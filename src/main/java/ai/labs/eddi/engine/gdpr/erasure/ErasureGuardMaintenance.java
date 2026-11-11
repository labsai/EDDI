/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Retries the pending removals of the erasure guarantee and deletes tombstones
 * past {@code eddi.gdpr.tombstone-retention}.
 * <p>
 * The retry is what makes a pending removal go away without anyone re-running
 * the erasure; until it has, every erasure of that user or conversation reports
 * the writer's step as failed. Both halves are idempotent, so every node runs
 * them. The tombstone sweep is retention, not enforcement: an expired tombstone
 * is already ignored by every read (and MongoDB's TTL index removes it too).
 */
@ApplicationScoped
public class ErasureGuardMaintenance {

    private static final Logger LOGGER = Logger.getLogger(ErasureGuardMaintenance.class);

    private final ErasureGuard guard;

    @Inject
    public ErasureGuardMaintenance(ErasureGuard guard) {
        this.guard = guard;
    }

    @Scheduled(every = "5m", delayed = "2m", identity = "gdpr-erasure-guard-maintenance")
    void run() {
        try {
            int cleared = guard.retryPending(null, null, null);
            if (cleared > 0) {
                LOGGER.infof("GDPR: cleared %d pending removal(s) of erased rows", cleared);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("GDPR: pending removals could not be retried; the next pass will: %s", e.toString());
        }
        try {
            long expired = guard.deleteExpiredTombstones();
            if (expired > 0) {
                LOGGER.debugf("GDPR: deleted %d expired erasure tombstone(s)", expired);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("GDPR: expired erasure tombstones could not be deleted; they are ignored either way: %s", e.toString());
        }
    }
}
