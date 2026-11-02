/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Joins GDPR erasure and export for the {@code toolQuotas} counters.
 * <p>
 * A counter is keyed by the subject it counts, which is a user identifier, so
 * the counters of an erased user are deleted with the rest of their data.
 * Erasure deletes the counters whose subject equals the erased user id. When an
 * agent's {@code toolQuotas.subject} template derives a different identifier (a
 * person id spanning several composite managed-conversation user ids), the
 * counters sit under that derived identifier and are removed when that
 * identifier is the one erased; they hold counts only, never content, and
 * expire on their own at the end of their window (plus a short grace period).
 * <p>
 * The export carries the user's counters — tool or pool, window start, count.
 * They are operational data about the person's own use, comparable to the other
 * records a participant exports.
 */
@ApplicationScoped
public class ToolQuotaGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many counters. */
    static final int MAX_EXPORTED_COUNTERS = 1_000;

    private final IToolQuotaStore store;

    @Inject
    public ToolQuotaGdprParticipant(IToolQuotaStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "toolQuotas";
    }

    @Override
    public long erase(String userId) {
        return store.deleteBySubject(userId);
    }

    @Override
    public Object export(String userId) {
        List<ToolQuotaUsage> held = store.findBySubject(userId, MAX_EXPORTED_COUNTERS);
        return held.isEmpty() ? null : held;
    }
}
