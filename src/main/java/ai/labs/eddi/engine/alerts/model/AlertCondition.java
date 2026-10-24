/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * When a rule fires. One flat shape for every source; each source reads only
 * its own fields and {@code AlertRuleValidator} refuses a rule that sets
 * another's.
 *
 * <h3>{@link AlertSource#RATING}</h3> The criteria are OR-ed: a rating fires
 * the rule when its normalized score is at most {@code maxNormalizedScore},
 * when it carries a comment and {@code onComment} is true, or when it carries
 * one of {@code tags}. At least one criterion is required.
 *
 * <h3>{@link AlertSource#CONVERSATION_ERROR}</h3> The turn's outcome is one of
 * {@code errorKinds} (at least one).
 *
 * @param maxNormalizedScore
 *            RATING: fire at or below this normalized score (0..1)
 * @param onComment
 *            RATING: fire on any rating that carries a comment
 * @param tags
 *            RATING: fire on a rating carrying any of these reason tags
 * @param errorKinds
 *            CONVERSATION_ERROR: the outcomes to report
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertCondition(Double maxNormalizedScore, Boolean onComment, List<String> tags, List<ErrorKind> errorKinds) {

    public AlertCondition {
        tags = tags == null ? null : List.copyOf(tags);
        errorKinds = errorKinds == null ? null : List.copyOf(errorKinds);
    }

    public boolean hasRatingCriteria() {
        return maxNormalizedScore != null || Boolean.TRUE.equals(onComment) || (tags != null && !tags.isEmpty());
    }
}
