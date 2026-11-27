/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
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
 * <b>The erasure guarantee.</b> Sessions are rows of Agent Health's async
 * writer ({@link HealthErasureWriter}): the erasure tombstones the user and
 * their conversations and waits for every node's outcome evaluations in flight,
 * and {@link HealthOutcomeEvaluator} guards each write with
 * {@link ErasureGuard#beforeWrite} and {@link ErasureGuard#afterWrite}. After
 * deleting, {@link ErasureGuard#verifyUserErased} retries the user's pending
 * removals of that writer and requires no session and no pending removal to be
 * left; otherwise the step fails and a re-run finishes it. Outcome rows are
 * derived from turn rows, which the erasure deletes too, so nothing re-creates
 * them afterwards. Their deletes mark no day dirty: the daily rows hold no
 * outcome counts.
 */
@ApplicationScoped
public class HealthOutcomeGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many rows. */
    static final int MAX_EXPORTED_SESSIONS = 10_000;

    private final IHealthOutcomeStore store;
    private final ErasureGuard erasureGuard;

    @Inject
    public HealthOutcomeGdprParticipant(IHealthOutcomeStore store, ErasureGuard erasureGuard) {
        this.store = store;
        this.erasureGuard = erasureGuard;
    }

    /** Without the erasure guard: tests of the delete and the export alone. */
    HealthOutcomeGdprParticipant(IHealthOutcomeStore store) {
        this(store, null);
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
        long deleted = store.deleteByUserId(userId);
        // Verified whatever the delete answered: the count is what decides. The
        // sessions' pending removals are recorded under the writer's name.
        if (erasureGuard != null) {
            erasureGuard.verifyUserErased(HealthErasureWriter.NAME, userId, () -> store.countByUserId(userId));
        }
        return deleted;
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
        row.put("refs", o.refs());
        return row;
    }
}
