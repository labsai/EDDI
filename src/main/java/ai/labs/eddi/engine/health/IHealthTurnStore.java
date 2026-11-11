/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthTurn;
import ai.labs.eddi.engine.health.model.HealthUsage;
import ai.labs.eddi.engine.health.model.HealthUsagePoint;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
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
 * paused on one day and resumed on the next stays on the day it started. Only
 * those two are kept: the rest of the row (cost, tokens, failures,
 * {@code paused}, …) is replaced by the resumed turn's values, so a day already
 * rolled up <em>does</em> change on a resume, and its rollup must be recomputed
 * (H2b marks that day dirty);</li>
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
     * The portfolio's traffic in one pass: for each agent, the distinct
     * conversations with a turn in the baseline ({@code baselineFrom} to the day
     * before {@code windowFrom}) and in the window ({@code windowFrom} to
     * {@code to}), over the {@code interactive} origins and over all of
     * {@code origins}. Agents without rows are absent.
     */
    Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom, LocalDate to,
                                                           Collection<String> interactive, Collection<String> origins);

    /**
     * Streams the distinct (agent, day) pairs of the rows written in
     * [{@code since}, {@code until}) — at most {@code limit} — to {@code consumer}.
     * What the rollup's self-heal pass re-marks dirty, so a dirty mark lost on a
     * node (a hard kill before its flush) never leaves a day stale.
     */
    void forEachAgentDayUpdatedSince(Instant since, Instant until, int limit, BiConsumer<String, LocalDate> consumer);

    /**
     * The (agent, day) pairs a user's rows are on, at most {@code limit} pairs —
     * read before an erasure deletes them, so the daily rows of those days are
     * recomputed without the user.
     */
    Map<String, Set<LocalDate>> agentDaysOfUser(String userId, int limit);

    /**
     * The (agent, day) pairs a conversation's rows are on — read before a delete.
     */
    Map<String, Set<LocalDate>> agentDaysOfConversation(String conversationId);

    /**
     * Deletes a conversation's rows and markers and answers the (agent, day) pairs
     * they were on — atomically where the store can (PostgreSQL
     * {@code DELETE ... RETURNING}), else read just before the delete. The delete
     * never depends on the read: when the days cannot be read they are {@code null}
     * and the rows are deleted all the same.
     */
    HealthDeletedRows deleteConversationReturningDays(String conversationId);

    /**
     * As {@link #forEachConversationUpdatedSince}, with the agent id each
     * conversation's recent rows carry ({@code null} when a row has none) — what
     * the orphan sweep needs to re-roll an orphan's days when they cannot be read
     * at delete time. A conversation with rows of two agents is passed once per
     * agent.
     */
    void forEachConversationAgentUpdatedSince(Instant since, int limit, BiConsumer<String, String> consumer);

    /** A user's rows, newest first, at most {@code limit} — for GDPR export. */
    List<HealthTurn> findByUserId(String userId, int limit);

    /** Deletes a user's rows and markers (GDPR erasure). */
    long deleteByUserId(String userId);

    /** Deletes a conversation's rows and markers (the conversation was deleted). */
    long deleteByConversationId(String conversationId);

    /**
     * Streams to {@code consumer} the distinct conversation ids of the rows whose
     * {@code updatedAt} is at or after {@code since}, and of the discard markers
     * written since then — at most {@code limit} ids, in no particular order. What
     * the orphan sweep ({@code HealthTurnRetention}) checks against the
     * conversation store: a write that landed after its conversation was deleted is
     * recent by construction.
     */
    void forEachConversationUpdatedSince(Instant since, int limit, Consumer<String> consumer);

    /**
     * Deletes the rows of every day before {@code day}, and the discard markers
     * written before it (retention).
     */
    long deleteOlderThan(LocalDate day);
}
