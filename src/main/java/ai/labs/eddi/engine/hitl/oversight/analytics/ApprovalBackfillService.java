/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rebuilds approval-decision facts from the audit ledger: pages the
 * {@code hitl.approval} entries newest first, parses each with
 * {@link HitlApprovalAudit#parse} (schema 1 and 2) and stores the units through
 * the projector. Idempotent — a unit already stored is skipped — so it repairs
 * a gap left by a lost event or a failed write, and imports the decisions made
 * before 6.7 (as {@code legacy} rows).
 * <p>
 * One rebuild runs at a time per node.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ApprovalBackfillService {

    private static final Logger LOGGER = Logger.getLogger(ApprovalBackfillService.class);

    static final int PAGE_SIZE = 500;
    /** Upper bound of entries one rebuild reads (2 million). */
    static final int MAX_PAGES = 4_000;

    private final IAuditStore auditStore;
    private final ApprovalDecisionProjector projector;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public ApprovalBackfillService(IAuditStore auditStore, ApprovalDecisionProjector projector) {
        this.auditStore = auditStore;
        this.projector = projector;
    }

    /** What a rebuild did. */
    public record Result(long entriesRead, long unitsStored, boolean truncated) {
    }

    /** Whether a rebuild is running on this node. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Rebuilds the decisions in {@code [from, to)}.
     *
     * @return the result, or null when another rebuild is already running here
     */
    public Result rebuild(Instant from, Instant to) {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        long read = 0;
        long stored = 0;
        boolean truncated = true;
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                List<AuditEntry> entries = auditStore.getEntriesByTask(HitlApprovalAudit.TASK_ID, null, page * PAGE_SIZE, PAGE_SIZE);
                boolean reachedStart = false;
                for (AuditEntry entry : entries) {
                    read++;
                    Instant at = entry.timestamp();
                    if (at != null && to != null && !at.isBefore(to)) {
                        continue;
                    }
                    if (at != null && from != null && at.isBefore(from)) {
                        // Newest first: everything after this is older still.
                        reachedStart = true;
                        break;
                    }
                    stored += projector.project(HitlApprovalAudit.parse(entry), false);
                }
                if (reachedStart || entries.size() < PAGE_SIZE) {
                    truncated = false;
                    break;
                }
            }
            LOGGER.infof("Approval analytics rebuild read %d hitl.approval entries and stored %d new decision rows%s", read, stored,
                    truncated ? " (stopped at the page limit)" : "");
            return new Result(read, stored, truncated);
        } catch (UnsupportedOperationException e) {
            LOGGER.warnf("The audit store cannot list entries by task; approval analytics cannot be rebuilt: %s", e.getMessage());
            return new Result(read, stored, true);
        } finally {
            running.set(false);
        }
    }
}
