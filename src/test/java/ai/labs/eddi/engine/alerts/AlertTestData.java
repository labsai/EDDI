/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEvent.Delivery;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.ratings.model.ConversationRating;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixtures for the alert tests. Hex ids throughout (EDDI's id extraction
 * drops anything else).
 */
public final class AlertTestData {

    public static final String AGENT = "6aba70011a25f8be7920e055";
    public static final String OTHER_AGENT = "6aba70011a25f8be7920e099";
    public static final String RULE_ID = "6aba70011a25f8be7920e0aa";
    public static final String CONVERSATION = "6aba70011a25f8be7920e0cc";
    public static final String RATER = "rater-user-7f3a";
    public static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private AlertTestData() {
    }

    public static ConversationRating rating(int score, String comment, List<String> tags) {
        double normalized = (score - 1) / 4.0;
        return new ConversationRating("6aba70011a25f8be7920e0dd", CONVERSATION, -1, AGENT, 3, "production", "chat-ui", RATER, Scale.FIVE_STAR,
                score, normalized, tags, comment, NOW, NOW);
    }

    public static AlertRule ratingRule(Double maxScore, Boolean onComment, List<String> tags, DeliveryTarget... targets) {
        return new AlertRule(RULE_ID, "Unhappy users", true, AlertScope.agent(AGENT), AlertSource.RATING,
                new AlertCondition(maxScore, onComment, tags, null), List.of(targets), null, null, "admin", NOW, "admin", NOW);
    }

    public static AlertRule errorRule(List<ErrorKind> kinds, DeliveryTarget... targets) {
        return new AlertRule(RULE_ID, "Broken turns", true, AlertScope.agent(AGENT), AlertSource.CONVERSATION_ERROR,
                new AlertCondition(null, null, null, kinds), List.of(targets), null, null, "admin", NOW, "admin", NOW);
    }

    public static AlertRule withThrottle(AlertRule rule, AlertRule.Throttle throttle, AlertRule.DigestMode digest) {
        return new AlertRule(rule.id(), rule.name(), rule.enabled(), rule.scope(), rule.source(), rule.condition(), rule.delivery(), throttle,
                digest, rule.createdBy(), rule.createdAt(), rule.updatedBy(), rule.updatedAt());
    }

    public static AlertEvent event(String id, AlertRule rule, AlertEvent.State state, List<Delivery> deliveries) {
        return new AlertEvent(id, rule.id(), rule.name(), rule.source(), AlertEvent.Kind.ALERT, AGENT, 3, CONVERSATION, NOW,
                rule.name() + ": a turn failed (agent " + AGENT + " v3)", AlertMatch.error(ErrorKind.TURN_ERROR), deliveries, state,
                NOW.plusSeconds(120), "key-" + id, 0, null, null, null, null, 4);
    }

    public static List<Delivery> pending(AlertRule rule) {
        List<Delivery> deliveries = new ArrayList<>();
        for (DeliveryTarget target : rule.delivery()) {
            deliveries.add(Delivery.pending(target));
        }
        return deliveries;
    }
}
