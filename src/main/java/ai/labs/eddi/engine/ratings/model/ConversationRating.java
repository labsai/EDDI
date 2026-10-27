/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * One rating a person gave a conversation, or one agent reply in it.
 * <p>
 * Keyed by {@code (conversationId, stepIndex)}: only the conversation's owner
 * may rate, so there is at most one rating per conversation
 * ({@code stepIndex = }{@link #CONVERSATION_STEP_INDEX}) and one per reply. A
 * repeated rating replaces the stored one and keeps its {@code createdAt}.
 * <p>
 * Stored apart from conversation memory, which is loaded on every turn and must
 * stay small, and which cannot be aggregated across conversations.
 *
 * @param id
 *            store-assigned id
 * @param conversationId
 *            the rated conversation
 * @param stepIndex
 *            {@link #CONVERSATION_STEP_INDEX} for the conversation as a whole,
 *            else the index of the rated agent reply's step
 * @param agentId
 *            the conversation's agent (denormalized for aggregation)
 * @param agentVersion
 *            the agent version the conversation ran on when it was rated
 * @param environment
 *            the deployment environment of the conversation
 * @param channel
 *            where the rating was given ({@code chat-ui}, {@code manager},
 *            {@code api}, …; unknown values are stored as {@code other})
 * @param userId
 *            the conversation owner, who gave the rating — personal data
 * @param scale
 *            the scale of {@code score}
 * @param score
 *            0/1 for thumbs, 1–5 for stars
 * @param normalizedScore
 *            {@code score} on 0..1, comparable across scales
 * @param tags
 *            reason tags, each from the agent's allow-list
 * @param comment
 *            free text, length-capped plain text, or null
 * @param reviewStatus
 *            where the rating stands in the maintainers' triage —
 *            {@link ReviewStatus#NEW} until somebody marks it reviewed, and
 *            again whenever the person re-rates
 * @param reviewedBy
 *            the principal who marked it reviewed, set server-side; null while
 *            NEW
 * @param reviewedAt
 *            when it was marked reviewed; null while NEW
 * @param reviewNote
 *            the maintainers' internal note, at most
 *            {@link #MAX_REVIEW_NOTE_LENGTH} characters — never shown to the
 *            person who rated
 */
public record ConversationRating(String id, String conversationId, int stepIndex, String agentId, Integer agentVersion,
        String environment, String channel, String userId, Scale scale, int score, double normalizedScore, List<String> tags,
        String comment, Instant createdAt, Instant updatedAt, ReviewStatus reviewStatus, String reviewedBy, Instant reviewedAt,
        String reviewNote) {

    /** The {@code stepIndex} of a rating of the conversation as a whole. */
    public static final int CONVERSATION_STEP_INDEX = -1;

    /** The longest review note. */
    public static final int MAX_REVIEW_NOTE_LENGTH = 2000;

    public ConversationRating {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A new, untriaged rating: {@link ReviewStatus#NEW}, no reviewer, no note. */
    public ConversationRating(String id, String conversationId, int stepIndex, String agentId, Integer agentVersion, String environment,
            String channel, String userId, Scale scale, int score, double normalizedScore, List<String> tags, String comment,
            Instant createdAt, Instant updatedAt) {
        this(id, conversationId, stepIndex, agentId, agentVersion, environment, channel, userId, scale, score, normalizedScore, tags,
                comment, createdAt, updatedAt, ReviewStatus.NEW, null, null, null);
    }

    /** Whether this rates the conversation as a whole rather than one reply. */
    @JsonIgnore
    public boolean isConversationRating() {
        return stepIndex == CONVERSATION_STEP_INDEX;
    }

    /** {@code conversation} or {@code message}. */
    public RatingKind kind() {
        return isConversationRating() ? RatingKind.CONVERSATION : RatingKind.MESSAGE;
    }

    /**
     * The rating as the person who gave it sees it: without the maintainers' triage
     * — status, reviewer and internal note are theirs, not the rater's.
     */
    public ConversationRating withoutReview() {
        return new ConversationRating(id, conversationId, stepIndex, agentId, agentVersion, environment, channel, userId, scale, score,
                normalizedScore, tags, comment, createdAt, updatedAt, null, null, null, null);
    }
}
