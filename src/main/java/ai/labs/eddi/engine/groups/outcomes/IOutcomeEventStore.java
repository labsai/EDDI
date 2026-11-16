/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.outcomes;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Stores {@link OutcomeEvent}s: MongoDB ({@code group_outcomes} collection) and
 * PostgreSQL ({@code group_outcomes} table), selected by
 * {@code DataStoreProducers}. Bounded per subject by {@link #insert} and per
 * team by the service, which trims on every write.
 *
 * @since 6.8.0
 */
public interface IOutcomeEventStore {

    /** What {@link #insert} did. */
    enum InsertOutcome {
        INSERTED,
        /** An event with this id is already stored; nothing changed. */
        DUPLICATE,
        /** The subject already holds {@code maxPerSubject} events; nothing changed. */
        FULL
    }

    /**
     * Stores the event unless one with its id exists or its subject already holds
     * {@code maxPerSubject} events. The check and the insert are one atomic step
     * per subject, so concurrent writers at {@code maxPerSubject - 1} store one
     * event between them, not one each. A duplicate is reported as such even on a
     * full subject.
     */
    InsertOutcome insert(OutcomeEvent event, int maxPerSubject);

    /** The events about one subject of a team, newest {@code recordedAt} first. */
    List<OutcomeEvent> listBySubject(String groupId, String subjectId, int limit);

    /**
     * A team's events recorded at or after {@code since} (null: any), newest first.
     */
    List<OutcomeEvent> listByGroup(String groupId, Instant since, int limit);

    /** A team's events about any of the subjects, newest first. */
    List<OutcomeEvent> listBySubjects(String groupId, Collection<String> subjectIds, int limit);

    /** How many events a team holds. */
    long countByGroup(String groupId);

    /**
     * Deletes a team's events beyond the newest {@code keep}.
     *
     * @return rows removed
     */
    long deleteOldestBeyond(String groupId, int keep);

    /** Deletes a team's events recorded before the cutoff. */
    long deleteRecordedBefore(String groupId, Instant cutoff);

    /** Deletes every event of a team (group deletion). */
    long deleteByGroup(String groupId);

    // === GDPR ===

    /** Events posted by the principal. */
    List<OutcomeEvent> readByUser(String userId, int limit);

    /** Replaces the principal on every event they posted. */
    long eraseUser(String userId, String pseudonym);
}
