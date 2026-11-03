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
 * {@code DataStoreProducers}. Bounded by the service, which trims per team on
 * every write.
 *
 * @since 6.8.0
 */
public interface IOutcomeEventStore {

    /**
     * Stores the event unless one with its id exists.
     *
     * @return whether it was inserted
     */
    boolean insertIfAbsent(OutcomeEvent event);

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

    /** How many events a team holds about one subject. */
    long countBySubject(String groupId, String subjectId);

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
