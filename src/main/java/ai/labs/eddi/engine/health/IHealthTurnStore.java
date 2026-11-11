/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Agent Health turn facts — MongoDB {@code healthturns}, PostgreSQL
 * {@code health_turns}; the deployment's datastore picks one
 * ({@code DataStoreProducers}).
 * <p>
 * A row is identified by ({@code conversationId}, {@code executionId}) — an
 * execution id belongs to exactly one step, so this is the
 * ({@code conversationId}, {@code stepIndex}, {@code executionId}) key of the
 * plan — and written by {@link #upsert}: a full replace of the row's values,
 * never an increment, with three exceptions that make concurrent and late
 * writes converge:
 * <ul>
 * <li><b>first recorded wins for {@code day} and {@code timestamp}</b> — a turn
 * paused on one day and resumed on the next stays on the day it started, so a
 * day already rolled up is not changed behind its back;</li>
 * <li><b>newest capture wins</b> — a write whose {@code updatedAt} is older
 * than the stored row's is ignored, so a stale paused row delayed in one node's
 * queue cannot overwrite the finished row another node wrote;</li>
 * <li><b>{@code discarded} is sticky</b> — once set, no write clears it.
 * {@link #markDiscarded} writes a marker when the execution's row has not
 * arrived yet, so a delayed write cannot bring a discarded execution back. A
 * marker has no {@code timestamp} and no reads return it.</li>
 * </ul>
 * Every method throws {@link HealthStoreException} when the store fails.
 */
public interface IHealthTurnStore {

    /** Inserts the row, or replaces it under the rules above. */
    void upsert(HealthTurn turn);

    /**
     * Inserts the row unless one with its key exists — for an unsettled turn (an
     * abandoned wait, a cancelled turn), which must never replace what the
     * conversation kept.
     */
    void insertIfAbsent(HealthTurn turn);

    /**
     * Marks the executions {@code executionIds} of {@code replacing}'s conversation
     * as discarded — an undo removed them and {@code replacing}'s turn took their
     * place. An execution with no row yet gets a marker (agent and user taken from
     * {@code replacing}).
     *
     * @return the executions marked
     */
    long markDiscarded(HealthTurn replacing, Collection<String> executionIds);

    /** A conversation's rows, oldest step first, at most {@code limit}. */
    List<HealthTurn> findByConversation(String conversationId, int limit);

    /**
     * An agent's rows of the UTC days {@code from} to {@code to} (both inclusive),
     * oldest first, at most {@code limit} — for drill-down. A rollup reads a day
     * with {@link #forEachByAgentAndDay} instead.
     */
    List<HealthTurn> findByAgentAndDayRange(String agentId, LocalDate from, LocalDate to, int limit);

    /**
     * Streams every row of one agent and UTC day to {@code consumer} without
     * holding them all in memory — what a rollup of that day reads.
     */
    void forEachByAgentAndDay(String agentId, LocalDate day, Consumer<HealthTurn> consumer);

    /**
     * The UTC days the given executions of a conversation are stored on — the days
     * a write to them changed, which the rollup must recompute. A resumed turn
     * keeps the day it was first recorded on, which is not the day of the write.
     * Executions with no row (or only a discard marker) are skipped.
     */
    Set<LocalDate> daysOf(String conversationId, Collection<String> executionIds);

    /**
     * Sessions, engagement and people of one agent over a period, counted in the
     * database from the non-discarded rows.
     *
     * @param lookbackFrom
     *            the first day searched for earlier turns of the period's users
     *            ({@link HealthUsage#returningUsers()}); not after
     *            {@code query.from()}
     */
    HealthUsage usage(HealthUsageQuery query, LocalDate lookbackFrom);

    /**
     * {@link #usage} per day — and per version or origin when {@code groupBy} is
     * {@code version} / {@code origin}; {@code null} or {@code none} for no
     * grouping. Only days with rows appear, in no particular order. Returning users
     * are not counted per day.
     */
    List<HealthUsagePoint> dailyUsage(HealthUsageQuery query, String groupBy);

    /**
     * Distinct conversations per agent over the days given — the portfolio's
     * traffic. Agents without rows are absent.
     */
    Map<String, Long> sessionsByAgent(Collection<String> agentIds, LocalDate from, LocalDate to, Collection<String> origins);

    /** A user's rows, newest first, at most {@code limit} — for GDPR export. */
    List<HealthTurn> findByUserId(String userId, int limit);

    /** Deletes a user's rows and markers (GDPR erasure). */
    long deleteByUserId(String userId);

    /** Deletes a conversation's rows and markers (the conversation was deleted). */
    long deleteByConversationId(String conversationId);

    /**
     * Deletes the rows of every day before {@code day}, and the discard markers
     * written before it (retention).
     */
    long deleteOlderThan(LocalDate day);
}
