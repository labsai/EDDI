/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Agent Health session outcomes — MongoDB {@code healthoutcomes},
 * PostgreSQL {@code health_outcomes}; the deployment's datastore picks one
 * ({@code DataStoreProducers}).
 * <p>
 * One row per ({@code conversationId}, {@code sessionNo}), written by the
 * outcome evaluation by <b>replace</b>: the row is a function of the turn rows
 * (and ratings) it was computed from, so writing it again is harmless. Every
 * method throws {@link HealthStoreException} when the store fails.
 */
public interface IHealthOutcomeStore {

    /** Inserts or replaces each row by its key. */
    void upsert(Collection<HealthOutcome> outcomes);

    /** A conversation's sessions, oldest first. */
    List<HealthOutcome> findByConversation(String conversationId);

    /**
     * Deletes the given sessions of a conversation (an evaluation no longer finds
     * them).
     */
    long deleteSessions(String conversationId, Collection<Integer> sessionNos);

    /**
     * Sessions due for another look ({@code dueAt} at or before {@code now}),
     * earliest due first, at most {@code limit}.
     */
    List<HealthOutcome> findDue(Instant now, int limit);

    /**
     * Makes a conversation's sessions due at {@code at} (a rating arrived that may
     * change them). Sessions due earlier stay as they are.
     *
     * @return the sessions marked
     */
    long markDue(String conversationId, Instant at);

    /**
     * Sessions of one agent that ended in the query's days, versions and origins,
     * counted per (outcome, evidence, engaged, provisional) — and per end day when
     * {@code perDay}, per version or origin when {@code groupBy} says so
     * ({@code none} or {@code null} for no grouping).
     */
    List<HealthOutcomeCount> counts(HealthUsageQuery query, boolean perDay, String groupBy);

    /** One page of the drill-down, newest end first. */
    List<HealthOutcome> sessions(HealthSessionQuery query);

    /**
     * How often each action was among the last turn's actions of the closed,
     * engaged sessions that have an outcome, over the query's days, versions and
     * origins — mapping suggestions.
     */
    Map<String, Long> endingActions(HealthUsageQuery query);

    /**
     * The portfolio's sessions in one pass: per agent, the sessions that ended in
     * the baseline ({@code baselineFrom} to the day before {@code windowFrom}) and
     * in the window, over the {@code interactive} origins and over all of
     * {@code origins}. Agents without sessions are absent.
     */
    Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom, LocalDate to,
                                                           Collection<String> interactive, Collection<String> origins);

    /** A user's sessions, newest first, at most {@code limit} — GDPR export. */
    List<HealthOutcome> findByUserId(String userId, int limit);

    /** Deletes a user's sessions (GDPR erasure). */
    long deleteByUserId(String userId);

    /** Deletes a conversation's sessions (the conversation was deleted). */
    long deleteByConversationId(String conversationId);

    /**
     * Streams the distinct conversations of sessions evaluated at or after
     * {@code since}, at most {@code limit} — what the orphan sweep checks.
     */
    void forEachConversationEvaluatedSince(Instant since, int limit, Consumer<String> consumer);

    /** Deletes the sessions that ended before {@code day} (retention). */
    long deleteOlderThan(LocalDate day);

    /** Where the evaluation's incremental feed stopped, if it ever ran. */
    Optional<Instant> watermark();

    /** Records where the incremental feed stopped. */
    void setWatermark(Instant at);
}
