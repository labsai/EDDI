/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;

import java.time.Instant;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where conversation ratings live — a collection/table of its own
 * ({@code conversationratings} / {@code conversation_ratings}), MongoDB and
 * PostgreSQL implementations selected by {@code DataStoreProducers}.
 * <p>
 * Ratings outlive the conversations they rate: a retention purge of old
 * transcripts leaves them in place (analytics), and only a GDPR erasure
 * ({@link #deleteByUserId}) or the person withdrawing a rating removes one.
 * <p>
 * Failures are unchecked: a store error is a 500, never a silent empty answer.
 */
public interface IConversationRatingStore {

    /**
     * Inserts the rating, or replaces the one stored under the same
     * {@code (conversationId, stepIndex)} — atomically, so a double click or two
     * tabs never produce two rows. A replaced rating keeps its id and
     * {@code createdAt}, and its triage is reset: status {@code NEW}, no reviewer,
     * no note — the note was about the old rating.
     *
     * @return the stored rating, with its id and timestamps
     */
    ConversationRating upsert(ConversationRating rating);

    /** @return whether a rating was removed */
    boolean delete(String conversationId, int stepIndex);

    /**
     * Every rating of one conversation, the conversation rating first, then by
     * step.
     */
    List<ConversationRating> readByConversation(String conversationId);

    /** One page of ratings matching {@code filter}. */
    List<ConversationRating> query(RatingFilter filter, RatingSort sort, int index, int limit);

    /** How many ratings match {@code filter}. */
    long count(RatingFilter filter);

    /**
     * Hands every rating matching {@code filter}, newest first, to {@code sink} —
     * at most {@code maxRows} — without holding them all in memory. For export.
     */
    void stream(RatingFilter filter, int maxRows, Consumer<ConversationRating> sink);

    /**
     * Aggregates the ratings matching {@code filter} per bucket, in the database.
     * For {@link RatingGroupBy#NONE} the result is always exactly one bucket, empty
     * when nothing matched; otherwise only buckets with ratings appear, in
     * ascending key order.
     */
    List<RatingSummary> summarize(RatingFilter filter, RatingGroupBy groupBy);

    /**
     * Every rating the user gave (GDPR export), newest first, at most
     * {@code maxRows}.
     */
    List<ConversationRating> readByUserId(String userId, int maxRows);

    /**
     * Deletes every rating the user gave (GDPR erasure).
     *
     * @return how many were removed
     */
    long deleteByUserId(String userId);

    /** One rating by its store id; empty for an unknown or malformed id. */
    Optional<ConversationRating> readById(String id);

    /**
     * Sets the triage fields of one rating — only if it was not re-rated since it
     * was read ({@code updatedAt} still equals {@code expectedUpdatedAt}), so a
     * maintainer never marks reviewed a rating they have not seen. Leaves
     * {@code updatedAt}, which tracks the rater's changes, alone.
     *
     * @return the updated rating; empty when it is gone or was re-rated meanwhile
     */
    Optional<ConversationRating> updateReview(String id, Instant expectedUpdatedAt, ReviewStatus status, String reviewedBy,
                                              Instant reviewedAt, String reviewNote);

    /**
     * Marks every {@code NEW} rating matching {@code filter} reviewed. Notes are
     * kept.
     *
     * @return how many were marked
     */
    long markReviewed(RatingFilter filter, String reviewedBy, Instant reviewedAt);

    /**
     * Forgets {@code reviewer} as the reviewer of every rating they reviewed (GDPR
     * erasure of a maintainer). The ratings stay reviewed.
     *
     * @return how many ratings were touched
     */
    long clearReviewer(String reviewer);
}
