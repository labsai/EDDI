/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import java.time.Instant;

/**
 * Which ratings a listing, summary or export covers. Every field is optional;
 * {@code null} means "do not filter on this".
 *
 * @param agentId
 *            ratings of this agent only
 * @param agentVersion
 *            ratings given on this agent version only
 * @param environment
 *            ratings of conversations in this environment only
 * @param from
 *            ratings created at or after this instant
 * @param to
 *            ratings created strictly before this instant
 * @param minScore
 *            normalized score (0..1) at least this
 * @param maxScore
 *            normalized score (0..1) at most this
 * @param kind
 *            conversation or reply ratings only
 * @param tag
 *            ratings carrying this reason tag
 * @param hasComment
 *            {@code true}: only ratings with a comment; {@code false}: only
 *            without
 * @param reviewStatus
 *            ratings in this triage state only
 * @param needsAttention
 *            {@code true}: only ratings that need a maintainer's attention —
 *            {@link ReviewStatus#NEW} and either low (normalized score at most
 *            {@link #ATTENTION_SCORE}) or carrying a comment
 * @param conversationId
 *            the ratings of one conversation only (the Manager's conversation
 *            view)
 */
public record RatingFilter(String agentId, Integer agentVersion, String environment, Instant from, Instant to, Double minScore,
        Double maxScore, RatingKind kind, String tag, Boolean hasComment, ReviewStatus reviewStatus, Boolean needsAttention,
        String conversationId) {

    /**
     * A rating at or below this normalized score is "low" for
     * {@code needsAttention}.
     */
    public static final double ATTENTION_SCORE = 0.5;

    /** Without the conversation filter. */
    public RatingFilter(String agentId, Integer agentVersion, String environment, Instant from, Instant to, Double minScore,
            Double maxScore, RatingKind kind, String tag, Boolean hasComment, ReviewStatus reviewStatus, Boolean needsAttention) {
        this(agentId, agentVersion, environment, from, to, minScore, maxScore, kind, tag, hasComment, reviewStatus, needsAttention, null);
    }

    /** Without the triage and conversation filters. */
    public RatingFilter(String agentId, Integer agentVersion, String environment, Instant from, Instant to, Double minScore,
            Double maxScore, RatingKind kind, String tag, Boolean hasComment) {
        this(agentId, agentVersion, environment, from, to, minScore, maxScore, kind, tag, hasComment, null, null);
    }

    /** Ratings of one agent (every agent when null) that need attention. */
    public static RatingFilter needingAttention(String agentId) {
        return new RatingFilter(agentId, null, null, null, null, null, null, null, null, null, null, true);
    }

    /** No filter at all. */
    public static RatingFilter all() {
        return new RatingFilter(null, null, null, null, null, null, null, null, null, null);
    }

    /** All ratings of one agent. */
    public static RatingFilter ofAgent(String agentId) {
        return new RatingFilter(agentId, null, null, null, null, null, null, null, null, null);
    }
}
