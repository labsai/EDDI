/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.lessons;

import java.time.Instant;
import java.util.List;

/**
 * What became of a lesson a consolidation run removed from the team's live set:
 * the full text it had, why it went, and what it went into. The live lessons
 * are what members recall; this is the record, so a merge or a retirement can
 * be read back and, if it was wrong, re-entered by hand.
 *
 * @param id
 *            unique
 * @param groupId
 *            the owning team
 * @param lessonKey
 *            the retro lesson's memory key ({@code retro:<hash>})
 * @param lessonValue
 *            the lesson's text as it was
 * @param action
 *            why it left
 * @param reason
 *            the model's reason, bounded
 * @param replacedByKey
 *            for a merge, the key of the lesson that absorbed it; else null
 * @param evidence
 *            for a retirement, the outcome ids that contradicted it
 * @param runId
 *            the consolidation run that did it
 * @param lessonCreatedAt
 *            when the lesson was first stored, if known
 * @param at
 *            when it was removed
 * @since 6.8.0
 */
public record LessonHistoryEntry(String id, String groupId, String lessonKey, String lessonValue, Action action, String reason,
        String replacedByKey, List<String> evidence, String runId, Instant lessonCreatedAt, Instant at) {

    /** Why a lesson left the live set. */
    public enum Action {
        /** Absorbed into another lesson. */
        MERGED,
        /** Contradicted by a later outcome. */
        RETIRED
    }

    public LessonHistoryEntry {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
