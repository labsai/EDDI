/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
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
 * <h3>{@link AlertSource#HEALTH}</h3> One or more of {@code healthTriggers}:
 * {@code STATUS_CHANGE} (the status entered one of {@code healthStatuses},
 * default {@code WATCH} and {@code NEEDS_ATTENTION}), {@code BURN_RATE} (the
 * agent-caused failures burn the SLO's error budget too fast in both windows,
 * above the profile's volume floor), {@code CRITICAL_TREND} (a critical measure
 * got significantly worse). Each fires when it starts, not again while it
 * lasts.
 *
 * @param maxNormalizedScore
 *            RATING: fire at or below this normalized score (0..1)
 * @param onComment
 *            RATING: fire on any rating that carries a comment
 * @param tags
 *            RATING: fire on a rating carrying any of these reason tags
 * @param errorKinds
 *            CONVERSATION_ERROR: the outcomes to report
 * @param path
 *            OUTPUT_MATCH: {@code properties.<name>},
 *            {@code output.json.<a.b.c>} or {@code actions} (see
 *            {@code OutputMatcher})
 * @param op
 *            OUTPUT_MATCH: the comparison
 * @param value
 *            OUTPUT_MATCH: the rule author's constant (string, number, boolean,
 *            or a list for {@code in})
 * @param includeValue
 *            OUTPUT_MATCH: add the runtime value to the alert — honoured only
 *            when the agent version has conversation review enabled
 * @param healthTriggers
 *            HEALTH: what fires the rule
 * @param healthStatuses
 *            HEALTH {@code STATUS_CHANGE}: the statuses whose entry fires it
 *            ({@code WATCH}, {@code NEEDS_ATTENTION}); {@code null} for both
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertCondition(Double maxNormalizedScore, Boolean onComment, List<String> tags, List<ErrorKind> errorKinds, String path,
        MatchOp op, Object value, Boolean includeValue, List<HealthTrigger> healthTriggers, List<String> healthStatuses) {

    /** What makes a {@link AlertSource#HEALTH} rule fire. */
    public enum HealthTrigger {
        /** The status entered one of the rule's statuses. */
        STATUS_CHANGE,
        /** The error budget burns too fast in the long and the short window. */
        BURN_RATE,
        /** A critical measure got significantly worse. */
        CRITICAL_TREND
    }

    public AlertCondition {
        tags = tags == null ? null : List.copyOf(tags);
        errorKinds = errorKinds == null ? null : List.copyOf(errorKinds);
        // Not List.copyOf: a null element must reach the validator (a 400), not
        // fail deserialization.
        healthTriggers = healthTriggers == null ? null : Collections.unmodifiableList(new ArrayList<>(healthTriggers));
        healthStatuses = healthStatuses == null ? null : Collections.unmodifiableList(new ArrayList<>(healthStatuses));
    }

    /** A condition of any source but HEALTH. */
    public AlertCondition(Double maxNormalizedScore, Boolean onComment, List<String> tags, List<ErrorKind> errorKinds, String path, MatchOp op,
            Object value, Boolean includeValue) {
        this(maxNormalizedScore, onComment, tags, errorKinds, path, op, value, includeValue, null, null);
    }

    /** A RATING or CONVERSATION_ERROR condition. */
    public AlertCondition(Double maxNormalizedScore, Boolean onComment, List<String> tags, List<ErrorKind> errorKinds) {
        this(maxNormalizedScore, onComment, tags, errorKinds, null, null, null, null, null, null);
    }

    /** An OUTPUT_MATCH condition. */
    public static AlertCondition outputMatch(String path, MatchOp op, Object value, Boolean includeValue) {
        return new AlertCondition(null, null, null, null, path, op, value, includeValue, null, null);
    }

    /** A HEALTH condition. */
    public static AlertCondition health(List<HealthTrigger> triggers, List<String> statuses) {
        return new AlertCondition(null, null, null, null, null, null, null, null, triggers, statuses);
    }

    public boolean hasOutputMatchFields() {
        return path != null || op != null || value != null || includeValue != null;
    }

    public boolean hasHealthFields() {
        return (healthTriggers != null && !healthTriggers.isEmpty()) || (healthStatuses != null && !healthStatuses.isEmpty());
    }

    public boolean hasRatingCriteria() {
        return maxNormalizedScore != null || Boolean.TRUE.equals(onComment) || (tags != null && !tags.isEmpty());
    }
}
