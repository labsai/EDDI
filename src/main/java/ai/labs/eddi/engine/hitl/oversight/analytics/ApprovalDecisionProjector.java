/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.audit.HitlDecisionRecorded;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Projects every recorded HITL decision into the approval-decision store, off
 * the decision's critical path ({@code @ObservesAsync} on
 * {@link HitlDecisionRecorded}). Idempotent: a unit is keyed by its decision id
 * and position, so the event and a later rebuild from the ledger store it once.
 * A failed write is logged and counted; a rebuild repairs it.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ApprovalDecisionProjector {

    private static final Logger LOGGER = Logger.getLogger(ApprovalDecisionProjector.class);

    private final IApprovalDecisionStore store;
    private final OversightMetrics metrics;
    private final Instance<AutonomyService> autonomy;

    @Inject
    public ApprovalDecisionProjector(IApprovalDecisionStore store, OversightMetrics metrics, Instance<AutonomyService> autonomy) {
        this.store = store;
        this.metrics = metrics;
        this.autonomy = autonomy;
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
                ApprovalDecision row = ApprovalDecision.of(unit, i);
                if (store.insertIfAbsent(row)) {
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
}
