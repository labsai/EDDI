/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.lessons;

import java.util.List;

/**
 * Stores {@link LessonHistoryEntry} rows: MongoDB
 * ({@code group_lesson_history}) and PostgreSQL ({@code group_lesson_history}),
 * selected by {@code DataStoreProducers}. Bounded by the writer, which trims
 * per team.
 *
 * @since 6.8.0
 */
public interface ILessonHistoryStore {

    /**
     * Stores the row unless one with its id exists.
     *
     * @return whether it was inserted
     */
    boolean insertIfAbsent(LessonHistoryEntry entry);

    /** A team's history, newest first. */
    List<LessonHistoryEntry> listByGroup(String groupId, int limit);

    /** How many rows a team holds. */
    long countByGroup(String groupId);

    /**
     * Deletes a team's rows beyond the newest {@code keep}.
     *
     * @return rows removed
     */
    long deleteOldestBeyond(String groupId, int keep);

    /** Deletes every row of a team (group deletion). */
    long deleteByGroup(String groupId);
}
