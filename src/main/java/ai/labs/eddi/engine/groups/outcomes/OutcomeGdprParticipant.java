/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * GDPR erasure and export of team outcome events. An outcome describes a team's
 * work, not a person, so the events stay; the principal that posted one becomes
 * the ledger's pseudonym.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class OutcomeGdprParticipant implements IGdprParticipant {

    static final int MAX_EXPORTED_ROWS = 10_000;

    private final IOutcomeEventStore store;
    private final AuditLedgerService ledger;

    @Inject
    public OutcomeGdprParticipant(IOutcomeEventStore store, AuditLedgerService ledger) {
        this.store = store;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return "groupOutcomes";
    }

    @Override
    public long erase(String userId) {
        return store.eraseUser(userId, ledger.pseudonymOf(userId));
    }

    @Override
    public Object export(String userId) {
        List<OutcomeEvent> posted = store.readByUser(userId, MAX_EXPORTED_ROWS);
        return posted.isEmpty() ? null : posted;
    }
}
