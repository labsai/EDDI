/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins GDPR erasure and export for the {@code toolQuotas} counters.
 * <p>
 * A counter is keyed by the subject it counts, which is a user identifier, so
 * the counters of an erased user are deleted with the rest of their data.
 * Erasure deletes the counters whose subject equals the erased user id, in the
 * form the default subject takes ({@link ToolQuotaService#userSubject}). When
 * an agent's {@code toolQuotas.subject} template derives a different identifier
 * (a person id spanning several composite managed-conversation user ids), the
 * counters sit under that derived identifier and are removed when that
 * identifier is the one erased; they hold counts only, never content, and
 * expire on their own at the end of their window (plus a short grace period).
 * <p>
 * The export carries the user's counters — tool or pool, window start, count —
 * under {@code counters}. They are operational data about the person's own use,
 * comparable to the other records a participant exports. Past
 * {@link #MAX_EXPORTED_COUNTERS} the section is marked
 * {@link UserDataExport#SECTION_TRUNCATED}, which makes the bundle incomplete.
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
        String subject = ToolQuotaService.userSubject(userId);
        return subject == null ? 0L : store.deleteBySubject(subject);
    }

    @Override
    public Object export(String userId) {
        String subject = ToolQuotaService.userSubject(userId);
        if (subject == null) {
            return null;
        }
        // One more than the cap, to know whether the cap cut anything off.
        List<ToolQuotaUsage> held = store.findBySubject(subject, MAX_EXPORTED_COUNTERS + 1);
        if (held.isEmpty()) {
            return null;
        }
        boolean truncated = held.size() > MAX_EXPORTED_COUNTERS;
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("counters", truncated ? held.subList(0, MAX_EXPORTED_COUNTERS) : held);
        section.put(UserDataExport.SECTION_TRUNCATED, truncated);
        return section;
    }
}
