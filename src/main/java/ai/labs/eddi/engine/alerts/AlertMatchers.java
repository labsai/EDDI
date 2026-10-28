/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.ratings.model.ConversationRating;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a rule fires for an input, and what it reports. Pure functions: no
 * I/O, no state. The match lists the rule's own criteria that held — built from
 * the rule's constants — so an alert says <em>why</em> without quoting anything
 * a person said, apart from the rating comment excerpt.
 */
public final class AlertMatchers {

    private AlertMatchers() {
    }

    /**
     * @return the match, or empty when the rule is not a RATING rule for the
     *         rating's agent or none of its criteria holds
     */
    public static Optional<AlertMatch> matchRating(AlertRule rule, ConversationRating rating) {
        if (!applies(rule, AlertSource.RATING, rating.agentId()) || rule.condition() == null) {
            return Optional.empty();
        }
        AlertCondition condition = rule.condition();
        List<String> matched = new ArrayList<>();
        if (condition.maxNormalizedScore() != null && rating.normalizedScore() <= condition.maxNormalizedScore()) {
            matched.add("normalizedScore <= " + condition.maxNormalizedScore());
        }
        boolean hasComment = rating.comment() != null && !rating.comment().isBlank();
        if (Boolean.TRUE.equals(condition.onComment()) && hasComment) {
            matched.add("comment");
        }
        if (condition.tags() != null) {
            for (String tag : condition.tags()) {
                if (rating.tags().contains(tag)) {
                    matched.add("tag: " + tag);
                }
            }
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AlertMatch(matched, null, rating.id(), rating.stepIndex(), rating.scale() == null ? null : rating.scale().name(),
                rating.score(), rating.normalizedScore(), rating.tags(), AlertMatch.excerpt(rating.comment())));
    }

    /**
     * @return the match, or empty when the rule is not a CONVERSATION_ERROR rule
     *         for the agent or does not list the outcome's kind
     */
    public static Optional<AlertMatch> matchTurn(AlertRule rule, TurnOutcome outcome) {
        if (!applies(rule, AlertSource.CONVERSATION_ERROR, outcome.agentId()) || rule.condition() == null
                || rule.condition().errorKinds() == null || !rule.condition().errorKinds().contains(outcome.kind())) {
            return Optional.empty();
        }
        return Optional.of(AlertMatch.error(outcome.kind()));
    }

    private static boolean applies(AlertRule rule, AlertSource source, String agentId) {
        return rule.enabled() && rule.source() == source && rule.scope() != null && rule.scope().covers(agentId);
    }

    /**
     * One line for the inbox and the delivered message, from the rule's name and
     * the facts — never from conversation text.
     */
    public static String summary(AlertRule rule, AlertMatch match, String agentId, Integer agentVersion) {
        String agent = agentId + (agentVersion == null ? "" : " v" + agentVersion);
        if (match.errorKind() != null) {
            return rule.name() + ": " + describe(match.errorKind()) + " (agent " + agent + ")";
        }
        if (rule.source() == AlertSource.OUTPUT_MATCH) {
            return rule.name() + ": " + String.join(", ", match.matched()) + " (agent " + agent + ")";
        }
        String what = match.stepIndex() != null && match.stepIndex() >= 0 ? "reply rating" : "conversation rating";
        String score = match.score() == null ? "" : " " + match.score() + (match.scale() == null ? "" : " (" + match.scale() + ")");
        return rule.name() + ": " + what + score + " — " + String.join(", ", match.matched()) + " (agent " + agent + ")";
    }

    static String describe(ErrorKind kind) {
        return switch (kind) {
            case TURN_ERROR -> "a turn failed";
            case TURN_INTERRUPTED -> "a turn was interrupted";
            case TURN_DEADLINE -> "a turn exceeded its deadline";
            case COST_CEILING -> "the conversation cost ceiling stopped a model call";
            case TENANT_BUDGET -> "the tenant cost budget stopped a model call";
        };
    }
}
