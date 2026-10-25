/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPR erasure and export for alerting. Alert events name no end user — the
 * comment excerpts of a user's ratings are blanked by the rating participant,
 * which can still find them — so what is left is the people who run agents: the
 * administrator or editor who acknowledged an event, or wrote a rule.
 * <p>
 * Erasure forgets them there. The export lists the rules they created or last
 * edited (with which of the two) and the events they acknowledged — never the
 * name of anybody else involved (the other author, another acknowledger), and
 * none of an event's content beyond its rule, source, agent and times.
 */
@ApplicationScoped
public class AlertGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rules, and this many events. */
    static final int MAX_EXPORTED = 10_000;

    /**
     * A rule the user wrote; {@code roles} is {@code author} and/or
     * {@code lastEditor}.
     */
    public record ExportedRule(String id, String name, Instant createdAt, Instant updatedAt, List<String> roles) {
    }

    /** An event the user acknowledged. */
    public record ExportedAcknowledgement(String id, String ruleName, String source, String agentId, Instant firedAt,
            Instant acknowledgedAt) {
    }

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
        if (userId == null || userId.isBlank()) {
            return null;
        }
        List<ExportedRule> rules = new ArrayList<>();
        for (AlertRule rule : ruleStore.findByAuthor(userId, MAX_EXPORTED)) {
            List<String> roles = new ArrayList<>(2);
            if (userId.equals(rule.createdBy())) {
                roles.add("author");
            }
            if (userId.equals(rule.updatedBy())) {
                roles.add("lastEditor");
            }
            if (!roles.isEmpty()) {
                rules.add(new ExportedRule(rule.id(), rule.name(), rule.createdAt(), rule.updatedAt(), List.copyOf(roles)));
            }
        }
        List<ExportedAcknowledgement> acknowledged = new ArrayList<>();
        for (AlertEvent event : eventStore.findAcknowledgedBy(userId, MAX_EXPORTED)) {
            if (userId.equals(event.acknowledgedBy())) {
                acknowledged.add(new ExportedAcknowledgement(event.id(), event.ruleName(), event.source() == null ? null : event.source().name(),
                        event.agentId(), event.firedAt(), event.acknowledgedAt()));
            }
        }
        if (rules.isEmpty() && acknowledged.isEmpty()) {
            return null;
        }
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("rules", rules);
        export.put("acknowledgedEvents", acknowledged);
        return export;
    }
}
