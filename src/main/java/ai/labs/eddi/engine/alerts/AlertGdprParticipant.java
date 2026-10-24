/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * GDPR erasure for alerting. Alert events name no end user — the comment
 * excerpts of a user's ratings are blanked by the rating participant, which can
 * still find them — so what is left is the people who run agents: the
 * administrator or editor who acknowledged an event, or wrote a rule, is
 * forgotten there. Nothing is exported: neither store holds data about the user
 * beyond those names.
 */
@ApplicationScoped
public class AlertGdprParticipant implements IGdprParticipant {

    private final IAlertEventStore eventStore;
    private final IAlertRuleStore ruleStore;

    @Inject
    public AlertGdprParticipant(IAlertEventStore eventStore, IAlertRuleStore ruleStore) {
        this.eventStore = eventStore;
        this.ruleStore = ruleStore;
    }

    @Override
    public String name() {
        return "alerts";
    }

    @Override
    public long erase(String userId) {
        return eventStore.clearAcknowledger(userId) + ruleStore.clearAuthor(userId);
    }

    @Override
    public Object export(String userId) {
        return null;
    }
}
