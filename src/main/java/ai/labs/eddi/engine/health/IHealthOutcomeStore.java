/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthFeedCursor;
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

    /**
     * How long after an evaluation's start a stored due time still counts as a mark
     * made during it (see {@link #upsert}). A window close stored earlier falls
     * into it only when it is this close anyway — then the session is merely looked
     * at once more.
     */
    long MARK_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * Inserts or replaces each row by its key — except {@code dueAt}: a stored due
     * time within {@link #MARK_WINDOW_MS} after the row's {@code evaluatedAt} (a
     * rating marked the session due while it was being evaluated) is kept, as the
     * earlier of the two, so the mark is never lost. A row without a due time
     * stores none.
     */
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
     * Makes sessions of a conversation due at {@code at}, the time of the mark (a
     * rating arrived, or was withdrawn, that may change them) — the given sessions,
     * or every one when {@code sessionNos} is {@code null}. A session already due
     * before {@code at} is due at {@code at} afterwards: still due, and the mark
     * now postdates an evaluation that started before it, so that evaluation's
     * {@link #upsert} keeps it rather than clearing an overdue time older than the
     * evaluation. The evaluation also passes a later {@code at} to put a session
     * whose evaluation failed back in the due order.
     *
     * @return the sessions marked
     */
    long markDue(String conversationId, Collection<Integer> sessionNos, Instant at);

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

    /**
     * Where the evaluation's incremental feed stands — its forward position and the
     * overlap replay's — if it ever ran.
     */
    Optional<HealthFeedCursor> feedCursor();

    /** Records where the incremental feed stands, both positions at once. */
    void setFeedCursor(HealthFeedCursor cursor);

    /**
     * How far the incremental feed has read once — the cursor's watermark — if it
     * ever ran.
     */
    default Optional<Instant> watermark() {
        return feedCursor().map(HealthFeedCursor::watermark);
    }
}
