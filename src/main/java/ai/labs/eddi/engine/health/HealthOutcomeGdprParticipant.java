/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins GDPR erasure and export for the Agent Health session outcomes — the
 * sibling of {@link HealthTurnGdprParticipant}. A row names the user whose
 * session it describes and how it ended (an outcome, its evidence, counts, the
 * action names of the last turn), so it is erased and exported with the rest of
 * their data.
 * <p>
 * The outcome evaluation writes nothing for a user an erasure fences
 * ({@link HealthErasureFence}) and deletes again what it wrote while the fence
 * went up; outcome rows are derived from turn rows, which the erasure deletes
 * too, so nothing re-creates them afterwards.
 */
@ApplicationScoped
public class HealthOutcomeGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rows. */
    static final int MAX_EXPORTED_SESSIONS = 10_000;

    private final IHealthOutcomeStore store;

    @Inject
    public HealthOutcomeGdprParticipant(IHealthOutcomeStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "agentHealthOutcomes";
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
        List<HealthOutcome> held = store.findByUserId(userId, MAX_EXPORTED_SESSIONS + 1);
        if (held.isEmpty()) {
            return null;
        }
        boolean truncated = held.size() > MAX_EXPORTED_SESSIONS;
        List<HealthOutcome> exported = truncated ? held.subList(0, MAX_EXPORTED_SESSIONS) : held;
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sessions", exported.stream().map(HealthOutcomeGdprParticipant::exported).toList());
        section.put(UserDataExport.SECTION_TRUNCATED, truncated);
        return section;
    }

    /** A row as plain JSON values. */
    static Map<String, Object> exported(HealthOutcome o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("conversationId", o.conversationId());
        row.put("sessionNo", o.sessionNo());
        row.put("agentId", o.agentId());
        row.put("agentVersion", o.agentVersion());
        row.put("origin", o.origin());
        row.put("engaged", o.engaged());
        row.put("startedAt", o.startedAt() == null ? null : o.startedAt().toString());
        row.put("endedAt", o.endedAt() == null ? null : o.endedAt().toString());
        row.put("turns", o.turns());
        row.put("costUsd", o.costUsd());
        row.put("failures", o.failures());
        row.put("outcome", o.outcome());
        row.put("evidence", o.evidence());
        row.put("provisional", o.provisional());
        row.put("lastActions", o.lastActions());
        return row;
    }
}
