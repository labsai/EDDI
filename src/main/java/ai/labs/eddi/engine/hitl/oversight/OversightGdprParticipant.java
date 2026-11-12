/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Objects;

import static ai.labs.eddi.engine.gdpr.ThirdPartyIdentifiers.keepOwn;

/**
 * GDPR erasure and export of the oversight stores.
 * <ul>
 * <li><b>Audit samples</b> of the user's conversations are deleted (they hold
 * redacted arguments); where the user was the reviewer, the reviewer becomes
 * the ledger's pseudonym.</li>
 * <li><b>Decision facts</b> stay — they hold no content and keep analytics
 * correct — but lose the user: their conversations' rows drop the
 * {@code userId}, their own decisions get the pseudonym as decider.</li>
 * <li><b>Autonomy state</b>: who applied, dismissed, suspended or lifted
 * becomes the pseudonym.</li>
 * </ul>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightGdprParticipant implements IGdprParticipant {

    static final int MAX_EXPORTED_ROWS = 10_000;
    static final int MAX_STATES = 100_000;

    private final IApprovalDecisionStore decisionStore;
    private final IAuditSampleStore sampleStore;
    private final IAutonomyStateStore stateStore;
    private final AuditLedgerService ledger;

    @Inject
    public OversightGdprParticipant(IApprovalDecisionStore decisionStore, IAuditSampleStore sampleStore, IAutonomyStateStore stateStore,
            AuditLedgerService ledger) {
        this.decisionStore = decisionStore;
        this.sampleStore = sampleStore;
        this.stateStore = stateStore;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return "hitlOversight";
    }

    @Override
    public long erase(String userId) {
        String pseudonym = ledger.pseudonymOf(userId);
        long changed = sampleStore.deleteByUser(userId);
        changed += sampleStore.pseudonymizeReviewer(userId, pseudonym);
        changed += decisionStore.eraseUser(userId, pseudonym);
        for (AutonomyState state : stateStore.list(null, MAX_STATES)) {
            AutonomyState next = pseudonymize(state, userId, pseudonym);
            if (next != state && stateStore.save(next, state.version()).isPresent()) {
                changed++;
            }
        }
        return changed;
    }

    @Override
    public Object export(String userId) {
        var export = new LinkedHashMap<String, Object>();
        // A row reached by the subject's id can name someone else: the reviewer of a
        // sample of their conversation, the approver of a pause in it, or — for a
        // decision the subject made — the owner of the conversation it was made in.
        // Those identifiers are masked (Art. 15(4)); the subject's own are kept.
        var samples = sampleStore.readByUser(userId, MAX_EXPORTED_ROWS).stream()
                .map(s -> s.withColumns(keepOwn(s.userId(), userId), keepOwn(s.reviewedBy(), userId), s.status(), s.version()))
                .toList();
        if (!samples.isEmpty()) {
            export.put("auditSamples", samples);
        }
        var decisions = decisionStore.readByUser(userId, MAX_EXPORTED_ROWS).stream()
                .map(d -> d.withPersonalFields(keepOwn(d.userId(), userId), keepOwn(d.decidedBy(), userId)))
                .toList();
        if (!decisions.isEmpty()) {
            export.put("approvalDecisions", decisions);
        }
        return export.isEmpty() ? null : export;
    }

    static AutonomyState pseudonymize(AutonomyState state, String userId, String pseudonym) {
        AutonomyState next = state;
        Recommendation r = state.recommendation();
        if (r != null && (userId.equals(r.appliedBy()) || userId.equals(r.dismissedBy()))) {
            next = next.withRecommendation(new Recommendation(r.id(), r.kind(), r.status(), r.computedAt(), r.basedOnAgentVersion(),
                    r.evidence(), r.pattern(), r.applicable(), r.proposedHitlConfig(), r.changes(), r.leavesNoGate(),
                    swap(r.appliedBy(), userId, pseudonym), r.appliedAt(), r.resultingAgentVersion(), swap(r.dismissedBy(), userId, pseudonym),
                    r.dismissedAt(), r.dismissNote()));
        }
        Suspension s = state.suspension();
        if (s != null && (userId.equals(s.suspendedBy()) || userId.equals(s.liftedBy()))) {
            next = next.withSuspension(new Suspension(s.active(), s.pattern(), s.since(), s.trigger(), s.triggerRef(), s.reasonCode(),
                    swap(s.suspendedBy(), userId, pseudonym), swap(s.liftedBy(), userId, pseudonym), s.liftedAt(), s.liftNote()));
        }
        return next;
    }

    private static String swap(String value, String userId, String pseudonym) {
        return Objects.equals(value, userId) ? pseudonym : value;
    }
}
