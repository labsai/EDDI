/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.DaySpend;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins GDPR erasure and export for the {@code documentSpend} counters.
 * <p>
 * A user's daily document spend buckets are keyed by their user id, so they are
 * personal data: erasure deletes every {@link SubjectKind#USER} bucket of the
 * erased id. Team buckets hold a team space id and dollar amounts, no user id,
 * and stay. The participant works on the store alone, without any conversation
 * lookup, because it runs after the conversation cascade.
 * <p>
 * Nothing in flight is keyed by these counters, so {@link #stopInFlightWork}
 * signals nothing; the jobs that charge them are stopped by their own
 * participant.
 * <p>
 * The export carries the user's buckets — UTC day, dollars, calls — under
 * {@code days}, at most {@link #MAX_EXPORTED_DAYS}; past that the section is
 * marked {@link UserDataExport#SECTION_TRUNCATED}.
 */
@ApplicationScoped
public class DocumentSpendGdprParticipant implements IGdprParticipant, UserErasureParticipant {

    /** The participant's name in erasure results and exports. */
    public static final String NAME = "documentSpend";

    /** An export carries at most this many day buckets. */
    static final int MAX_EXPORTED_DAYS = 1_000;

    private final IDocumentSpendStore store;

    @Inject
    public DocumentSpendGdprParticipant(IDocumentSpendStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String erasureStepName() {
        return NAME;
    }

    @Override
    public int stopInFlightWork(String userId) {
        return 0;
    }

    @Override
    public long erase(String userId) {
        if (userId == null || userId.isEmpty()) {
            return 0L;
        }
        return store.deleteBySubject(SubjectKind.USER, userId);
    }

    @Override
    public Object export(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        // One more than the cap, to know whether the cap cut anything off.
        List<DaySpend> held = store.findBySubject(SubjectKind.USER, userId, MAX_EXPORTED_DAYS + 1);
        if (held.isEmpty()) {
            return null;
        }
        boolean truncated = held.size() > MAX_EXPORTED_DAYS;
        List<Map<String, Object>> days = new ArrayList<>();
        for (DaySpend day : truncated ? held.subList(0, MAX_EXPORTED_DAYS) : held) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", LocalDate.ofEpochDay(day.epochDay()).toString());
            row.put("spentUsd", day.spentUsd());
            row.put("calls", day.calls());
            days.add(row);
        }
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("days", days);
        section.put(UserDataExport.SECTION_TRUNCATED, truncated);
        return section;
    }
}
