/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.gdpr.ErasedSubjects;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Projects every recorded HITL decision into the approval-decision store, off
 * the decision's critical path ({@code @ObservesAsync} on
 * {@link HitlDecisionRecorded}). Idempotent: a unit is keyed by its decision id
 * and position, so the event and a later rebuild from the ledger store it once.
 * A failed write is logged and counted; a rebuild repairs it.
 * <p>
 * <b>Erased identities.</b> Before a row is stored, each identity it carries —
 * the conversation owner ({@code userId}) and the decider ({@code decidedBy}) —
 * is checked against {@link ErasedSubjects}. For a decision made no later than
 * that person's erasure the row gets what erasure leaves in a stored row
 * ({@link IApprovalDecisionStore#eraseUser}): no {@code userId}, the ledger's
 * pseudonym as decider. Without this a rebuild re-inserted, from ledger entries
 * erasure does not rewrite, rows naming a decider who had been erased. When the
 * record cannot be read the unit is not stored (counted as a failed projection)
 * rather than stored with an identity that may be erased.
 * <p>
 * The check runs again, with a fresh read, after a row was inserted with an
 * identity kept; if an erasure was recorded in between, the row is scrubbed
 * with {@link IApprovalDecisionStore#eraseUser}. Erasure records the person
 * before it pseudonymises the stored rows, so a row inserted in that window is
 * reached by one side or the other (see {@link ErasedSubjects}).
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ApprovalDecisionProjector {

    private static final Logger LOGGER = Logger.getLogger(ApprovalDecisionProjector.class);

    private final IApprovalDecisionStore store;
    private final OversightMetrics metrics;
    private final Instance<AutonomyService> autonomy;
    private final ErasedSubjects erasedSubjects;

    @Inject
    public ApprovalDecisionProjector(IApprovalDecisionStore store, OversightMetrics metrics, Instance<AutonomyService> autonomy,
            ErasedSubjects erasedSubjects) {
        this.store = store;
        this.metrics = metrics;
        this.autonomy = autonomy;
        this.erasedSubjects = erasedSubjects;
    }

    /** Without the erased-subject record, for tests that do not exercise it. */
    ApprovalDecisionProjector(IApprovalDecisionStore store, OversightMetrics metrics, Instance<AutonomyService> autonomy) {
        this(store, metrics, autonomy, null);
    }

    void onDecisionRecorded(@ObservesAsync HitlDecisionRecorded event) {
        if (event != null) {
            project(event.units(), true);
        }
    }

    /**
     * Stores the units of one decision.
     *
     * @param evaluate
     *            whether newly stored rejections are handed to earned autonomy
     *            (false for a rebuild of history)
     * @return how many rows were new
     */
    public int project(List<HitlDecisionUnit> units, boolean evaluate) {
        if (units == null || units.isEmpty()) {
            return 0;
        }
        List<ApprovalDecision> inserted = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            HitlDecisionUnit unit = units.get(i);
            if (unit == null || unit.decisionId() == null || unit.decisionOutcome() == null) {
                continue;
            }
            try {
                ApprovalDecision row = withoutErasedIdentities(ApprovalDecision.of(unit, i));
                if (store.insertIfAbsent(row)) {
                    row = scrubIfErasedSinceCheck(row);
                    inserted.add(row);
                    metrics.decisionProjected(row.surface(), row.pauseType(), row.latencyMs());
                }
            } catch (RuntimeException e) {
                metrics.projectionFailed();
                LOGGER.warnf("Could not store approval decision %s#%d for analytics (a rebuild repairs it): %s", sanitize(unit.decisionId()),
                        i, e.getMessage());
            }
        }
        if (evaluate && !inserted.isEmpty() && autonomy != null && autonomy.isResolvable()) {
            try {
                autonomy.get().onDecisions(inserted);
            } catch (RuntimeException e) {
                LOGGER.warnf("Earned autonomy could not evaluate decision %s: %s", sanitize(units.getFirst().decisionId()), e.getMessage());
            }
        }
        return inserted.size();
    }

    /**
     * The row as erasure would have left it, for each of its identities erased at
     * or after the decision.
     */
    ApprovalDecision withoutErasedIdentities(ApprovalDecision row) {
        if (erasedSubjects == null) {
            return row;
        }
        Instant at = row.decidedAt() != null ? row.decidedAt() : row.pausedAt();
        String userId = row.userId();
        String decidedBy = row.decidedBy();
        boolean changed = false;
        if (userId != null && erasedSubjects.covers(userId, at)) {
            userId = null;
            changed = true;
        }
        if (decidedBy != null && erasedSubjects.covers(decidedBy, at)) {
            decidedBy = erasedSubjects.pseudonymOf(decidedBy);
            changed = true;
        }
        return changed ? row.withPersonalFields(userId, decidedBy) : row;
    }

    /**
     * The second check of an inserted row: an identity it kept that is now covered
     * by an erasure recorded after the first check is erased from the store.
     *
     * @return the row as now stored
     */
    private ApprovalDecision scrubIfErasedSinceCheck(ApprovalDecision row) {
        if (erasedSubjects == null || row.userId() == null && row.decidedBy() == null) {
            return row;
        }
        ApprovalDecision now;
        try {
            now = withoutErasedIdentities(row);
        } catch (RuntimeException e) {
            metrics.projectionFailed();
            LOGGER.errorf("Approval decision %s was stored, but the erased-subject record could not be re-read; if its user was erased "
                    + "concurrently, re-run the erasure: %s", sanitize(row.id()), e.getMessage());
            return row;
        }
        if (row.userId() != null && now.userId() == null) {
            store.eraseUser(row.userId(), erasedSubjects.pseudonymOf(row.userId()));
        }
        if (row.decidedBy() != null && !row.decidedBy().equals(now.decidedBy())) {
            store.eraseUser(row.decidedBy(), now.decidedBy());
        }
        return now;
    }
}
