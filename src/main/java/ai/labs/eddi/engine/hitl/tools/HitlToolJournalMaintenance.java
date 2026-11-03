/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Periodically asks the HITL tool journal to repair retention drift (see
 * {@link IHitlToolJournalStore#reconcileRetention()}): during a rolling update
 * an old pod can recreate the unfiltered TTL index after a new pod removed it,
 * which would silently expire EXECUTING claims again. The store also reconciles
 * once at startup; this covers the time after. Idempotent, so every replica may
 * run it.
 */
@ApplicationScoped
public class HitlToolJournalMaintenance {

    private static final Logger LOGGER = Logger.getLogger(HitlToolJournalMaintenance.class);

    private final IHitlToolJournalStore journalStore;

    @Inject
    public HitlToolJournalMaintenance(IHitlToolJournalStore journalStore) {
        this.journalStore = journalStore;
    }

    @Scheduled(every = "${eddi.hitl.tool.journal-reconcile-interval:15m}", delayed = "2m", identity = "hitl-tool-journal-retention-reconcile",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcile() {
        try {
            journalStore.reconcileRetention();
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "HITL tool journal: could not reconcile the retention index; the next pass retries");
        }
    }
}
