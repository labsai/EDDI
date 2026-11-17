/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins GDPR erasure and export for the Agent Health business events (H5) — the
 * sibling of {@link HealthOutcomeGdprParticipant}. A <em>matched</em> event
 * carries the user of the session it was matched to, so it is erased and
 * exported with the rest of their data, its business reference in the form it
 * is stored (plain, or a salted hash).
 * <p>
 * A <em>pending</em> event names no user — it names a reference or a
 * conversation, and nothing joins it to a person yet. An erasure deletes the
 * user's conversations and session rows, so such an event can no longer match
 * and expires with {@code eddi.health.event-match-window}; a match found while
 * the erasure runs is dropped by the matcher ({@link HealthErasureFence}).
 */
@ApplicationScoped
public class HealthEventGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many events. */
    static final int MAX_EXPORTED_EVENTS = 10_000;

    private final IHealthEventStore store;

    @Inject
    public HealthEventGdprParticipant(IHealthEventStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "agentHealthEvents";
    }

    @Override
    public long erase(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        return store.deleteByUserId(userId);
    }

    @Override
    public Object export(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        List<HealthEvent> held = store.findByUserId(userId, MAX_EXPORTED_EVENTS + 1);
        if (held.isEmpty()) {
            return null;
        }
        boolean truncated = held.size() > MAX_EXPORTED_EVENTS;
        List<HealthEvent> exported = truncated ? held.subList(0, MAX_EXPORTED_EVENTS) : held;
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("events", exported.stream().map(HealthEventGdprParticipant::exported).toList());
        section.put(UserDataExport.SECTION_TRUNCATED, truncated);
        return section;
    }

    /** An event as plain JSON values. */
    static Map<String, Object> exported(HealthEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.id());
        row.put("integrationId", e.integrationId());
        row.put("agentId", e.agentId());
        row.put("agentVersion", e.agentVersion());
        row.put("eventType", e.eventType());
        row.put("ref", e.ref());
        row.put("conversationId", e.conversationId());
        row.put("sessionNo", e.matchedStep());
        row.put("value", e.value());
        row.put("at", e.at() == null ? null : e.at().toString());
        row.put("receivedAt", e.receivedAt() == null ? null : e.receivedAt().toString());
        row.put("evidence", e.evidence());
        return row;
    }
}
