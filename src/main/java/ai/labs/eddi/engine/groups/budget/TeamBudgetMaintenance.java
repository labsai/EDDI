/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Removes month documents past their retention (F6). Retention, not
 * enforcement: the month is part of the key, so a stale row is never read
 * again. MongoDB also has a TTL index; PostgreSQL has only this. Every node
 * runs it and deleting already-deleted rows is a no-op.
 */
@ApplicationScoped
public class TeamBudgetMaintenance {

    private static final Logger LOGGER = Logger.getLogger(TeamBudgetMaintenance.class);

    private final ITeamBudgetStore store;

    @Inject
    public TeamBudgetMaintenance(ITeamBudgetStore store) {
        this.store = store;
    }

    @Scheduled(every = "6h", delayed = "15m", identity = "team-budget-retention")
    void sweepExpiredMonths() {
        try {
            int deleted = store.deleteExpired(Instant.now());
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d expired team budget month(s)", deleted);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Failed to sweep expired team budget months; the next sweep will retry");
        }
    }
}
