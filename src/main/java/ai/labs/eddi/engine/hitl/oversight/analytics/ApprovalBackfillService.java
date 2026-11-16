/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
    /**
     * Upper bound of pending rows one reconciliation checks. Rows are pending only
     * while a projection is in flight or after one failed, so this is a backlog
     * bound, not a throughput limit.
     */
    static final int RECONCILE_LIMIT = 1_000;
    /** Upper bound of ledger pages read to find one pending row's entry. */
    static final int LEDGER_LOOKUP_PAGES = 20;

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

    /**
     * Settles rows a projection left pending (it failed, or the process stopped,
     * before it confirmed the row against the erased-subject record), at most
     * {@value #RECONCILE_LIMIT}. A pending row is stored masked, so its identities
     * are read back from its ledger entry (by conversation) and settled through the
     * same two checks as a fresh projection; a row whose entry is gone or redacted
     * keeps its masked identities. Run by the hourly HITL maintenance and after
     * every rebuild; a row that cannot be settled now stays pending.
     *
     * @return how many rows were settled
     */
    public int reconcilePending() {
        List<ApprovalDecision> pending;
        try {
            pending = projector.pendingRows(RECONCILE_LIMIT);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not read approval decisions pending the erased-subject check: %s", e.getMessage());
            return 0;
        }
        int settled = 0;
        for (ApprovalDecision row : pending) {
            try {
                Optional<ApprovalDecision> raw = fromLedger(row);
                boolean done = raw.isPresent() ? projector.settle(raw.get(), row) != null : projector.settleMasked(row);
                if (done) {
                    settled++;
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("Pending approval decision %s could not be settled yet: %s", sanitize(row.id()), e.getMessage());
            }
        }
        if (settled > 0) {
            LOGGER.infof("Settled %d approval decision row(s) left pending the erased-subject check", settled);
        }
        return settled;
    }

    /**
     * The row as the ledger has it: the {@code hitl.approval} entry of the row's
     * conversation that holds its decision, at the row's unit index.
     */
    Optional<ApprovalDecision> fromLedger(ApprovalDecision row) {
        if (row.conversationId() == null || row.decisionId() == null) {
            return Optional.empty();
        }
        for (int page = 0; page < LEDGER_LOOKUP_PAGES; page++) {
            List<AuditEntry> entries = auditStore.getEntries(row.conversationId(), page * PAGE_SIZE, PAGE_SIZE);
            for (AuditEntry entry : entries) {
                if (!HitlApprovalAudit.TASK_ID.equals(entry.taskId())) {
                    continue;
                }
                List<HitlDecisionUnit> units = HitlApprovalAudit.parse(entry);
                int index = row.unitIndex();
                if (index >= 0 && index < units.size() && units.get(index) != null && row.decisionId().equals(units.get(index).decisionId())) {
                    return Optional.of(ApprovalDecision.of(units.get(index), index));
                }
            }
            if (entries.size() < PAGE_SIZE) {
                break;
            }
        }
        return Optional.empty();
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
            reconcilePending();
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
