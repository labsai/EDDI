/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Removes counters whose window ended more than the grace period ago.
 * <p>
 * Retention, not enforcement: the window is part of the counter's key, so a
 * stale row is never read again whether or not this has run. MongoDB also has a
 * TTL index; PostgreSQL has nothing else, which is what this is for. Every node
 * runs it, and a delete of already-deleted rows is a no-op, so no coordination
 * is needed.
 */
@ApplicationScoped
public class ToolQuotaMaintenance {

    private static final Logger LOGGER = Logger.getLogger(ToolQuotaMaintenance.class);

    private final IToolQuotaStore store;

    @Inject
    public ToolQuotaMaintenance(IToolQuotaStore store) {
        this.store = store;
    }

    @Scheduled(every = "1h", delayed = "10m", identity = "tool-quota-retention")
    void sweepExpiredCounters() {
        try {
            int deleted = store.deleteExpired(Instant.now());
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d expired tool quota counter(s)", deleted);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Failed to sweep expired tool quota counters; they are never read again and the next sweep will retry");
        }
    }
}
