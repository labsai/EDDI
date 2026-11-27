/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What made a rule fire — the rule's own criteria and the facts it compared,
 * never conversation text.
 *
 * @param matched
 *            the criteria that held, in words built from the rule's constants
 *            ({@code "normalizedScore <= 0.5"}, {@code "comment"},
 *            {@code "tag: Too slow"}, {@code "errorKind: TURN_ERROR"})
 * @param errorKind
 *            CONVERSATION_ERROR: the outcome
 * @param ratingId
 *            RATING: the rating (so GDPR erasure can find the excerpt)
 * @param stepIndex
 *            RATING: {@code -1} for the conversation, else the rated reply
 * @param commentExcerpt
 *            RATING: the comment, at most {@link #MAX_COMMENT_EXCERPT}
 *            characters; blanked by GDPR erasure
 * @param value
 *            OUTPUT_MATCH: the runtime value that matched, at most
 *            {@link #MAX_COMMENT_EXCERPT} characters — present only when the
 *            rule set {@code includeValue} and the agent version has
 *            conversation review enabled
 * @param healthStatus
 *            HEALTH: the agent's status when the rule fired
 * @param previousHealthStatus
 *            HEALTH {@code STATUS_CHANGE}: the status before, {@code null} when
 *            the rule had not seen the agent yet
 * @param measureId
 *            HEALTH {@code CRITICAL_TREND}: the measure that got worse;
 *            {@code BURN_RATE}: {@code agentFailureRate}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertMatch(List<String> matched, ErrorKind errorKind, String ratingId, Integer stepIndex, String scale, Integer score,
        Double normalizedScore, List<String> tags, String commentExcerpt, String value, String healthStatus, String previousHealthStatus,
        String measureId) {

    /** The longest rating comment an alert carries. */
    public static final int MAX_COMMENT_EXCERPT = 280;

    public AlertMatch {
        matched = matched == null ? List.of() : List.copyOf(matched);
        tags = tags == null ? null : List.copyOf(tags);
    }

    /** A match of any source but HEALTH. */
    public AlertMatch(List<String> matched, ErrorKind errorKind, String ratingId, Integer stepIndex, String scale, Integer score,
            Double normalizedScore, List<String> tags, String commentExcerpt, String value) {
        this(matched, errorKind, ratingId, stepIndex, scale, score, normalizedScore, tags, commentExcerpt, value, null, null, null);
    }

    /**
     * A HEALTH match: what changed, in words built from the measures (never
     * conversation content), and the status.
     */
    public static AlertMatch health(List<String> matched, String status, String previousStatus, String measureId) {
        return new AlertMatch(matched, null, null, null, null, null, null, null, null, null, status, previousStatus, measureId);
    }

    /** A match without an OUTPUT_MATCH value. */
    public AlertMatch(List<String> matched, ErrorKind errorKind, String ratingId, Integer stepIndex, String scale, Integer score,
            Double normalizedScore, List<String> tags, String commentExcerpt) {
        this(matched, errorKind, ratingId, stepIndex, scale, score, normalizedScore, tags, commentExcerpt, null);
    }

    /** This match with the rating comment excerpt blanked (GDPR erasure). */
    public AlertMatch withoutCommentExcerpt() {
        return new AlertMatch(matched, errorKind, ratingId, stepIndex, scale, score, normalizedScore, tags, null, value, healthStatus,
                previousHealthStatus, measureId);
    }

    /** This match with the captured output value blanked (GDPR erasure). */
    public AlertMatch withoutValue() {
        return new AlertMatch(matched, errorKind, ratingId, stepIndex, scale, score, normalizedScore, tags, commentExcerpt, null, healthStatus,
                previousHealthStatus, measureId);
    }

    public static AlertMatch error(ErrorKind kind) {
        return new AlertMatch(List.of("errorKind: " + kind.name()), kind, null, null, null, null, null, null, null);
    }

    /**
     * An OUTPUT_MATCH match: what the rule compared, in its own words, and — only
     * when the rule asked and conversation review allows it — the runtime value.
     */
    public static AlertMatch output(String described, String value) {
        return new AlertMatch(List.of(described), null, null, null, null, null, null, null, null, value);
    }

    /**
     * Cuts a comment to {@link #MAX_COMMENT_EXCERPT} code points, with an ellipsis
     * when it was longer; {@code null} for a blank one.
     */
    public static String excerpt(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_COMMENT_EXCERPT) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, MAX_COMMENT_EXCERPT - 1);
        return trimmed.substring(0, end) + "…";
    }
}
