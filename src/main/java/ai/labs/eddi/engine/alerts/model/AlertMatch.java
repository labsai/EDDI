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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertMatch(List<String> matched, ErrorKind errorKind, String ratingId, Integer stepIndex, String scale, Integer score,
        Double normalizedScore, List<String> tags, String commentExcerpt) {

    /** The longest rating comment an alert carries. */
    public static final int MAX_COMMENT_EXCERPT = 280;

    public AlertMatch {
        matched = matched == null ? List.of() : List.copyOf(matched);
        tags = tags == null ? null : List.copyOf(tags);
    }

    public static AlertMatch error(ErrorKind kind) {
        return new AlertMatch(List.of("errorKind: " + kind.name()), kind, null, null, null, null, null, null, null);
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
