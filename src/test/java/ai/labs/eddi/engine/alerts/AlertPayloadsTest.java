/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RATER;
import static ai.labs.eddi.engine.alerts.AlertTestData.rating;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The privacy contract, asserted on what actually leaves EDDI: the serialized
 * webhook body and the Slack text.
 */
@DisplayName("AlertPayloads — what leaves EDDI")
class AlertPayloadsTest {

    private static final String SECRET_TEXT = "my card number is 4111 and I live at Elm Street";

    private AlertEvent ratingEvent(String comment) {
        AlertRule rule = ratingRule(null, true, null, DeliveryTarget.slack("C0123ABC"));
        ConversationRating rating = rating(1, comment, List.of("Too slow"));
        AlertMatch match = AlertMatchers.matchRating(rule, rating).orElseThrow();
        return new AlertEvent("6aba70011a25f8be7920e0ee", rule.id(), rule.name(), rule.source(), AlertEvent.Kind.ALERT, AGENT, 3,
                rating.conversationId(), NOW, AlertMatchers.summary(rule, match, AGENT, 3), match, List.of(), AlertEvent.State.PENDING, null,
                "key", 0, null, null, null, null, 0);
    }

    @Test
    @DisplayName("the webhook body never carries the rater's user id, and the comment only as a 280-character excerpt")
    void webhookPrivacy() throws Exception {
        String longComment = SECRET_TEXT + " " + "y".repeat(500);
        String json = new ObjectMapper().writeValueAsString(AlertPayloads.webhook(ratingEvent(longComment), Optional.empty()));
        assertFalse(json.contains(RATER), json);
        assertFalse(json.contains("userId"), json);
        assertFalse(json.contains("y".repeat(300)), "the comment must be cut");
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) new ObjectMapper().readValue(json, Map.class).get("match");
        String excerpt = (String) match.get("commentExcerpt");
        assertEquals(280, excerpt.codePointCount(0, excerpt.length()));
        assertEquals(AlertPayloads.SCHEMA, new ObjectMapper().readValue(json, Map.class).get("schema"));
    }

    @Test
    @DisplayName("an error alert carries the kind, never exception text or conversation content")
    void errorPayload() throws Exception {
        AlertEvent event = AlertTestData.event("6aba70011a25f8be7920e0ef",
                AlertTestData.errorRule(List.of(ErrorKind.TURN_ERROR)), AlertEvent.State.PENDING, List.of());
        String json = new ObjectMapper().writeValueAsString(AlertPayloads.webhook(event, Optional.of("https://eddi.example.com")));
        assertTrue(json.contains("\"errorKind\":\"TURN_ERROR\""), json);
        assertTrue(json.contains("https://eddi.example.com/manage/conversationview/" + event.conversationId()), json);
        assertFalse(json.contains("commentExcerpt"), json);
    }

    @Test
    @DisplayName("Slack text escapes the comment, so it cannot ping a channel or inject a link")
    void slackEscaping() {
        String text = AlertPayloads.slackText(ratingEvent("<!channel> see <https://evil.example|here>"), Optional.empty());
        assertFalse(text.contains("<!channel>"), text);
        assertFalse(text.contains("<https://evil.example"), text);
        assertTrue(text.contains("&lt;!channel&gt;"), text);
        assertFalse(text.contains(RATER), text);
    }

    @Test
    @DisplayName("links appear only with a configured Manager URL")
    void links() {
        assertTrue(AlertPayloads.links(ratingEvent("ok"), Optional.empty()).isEmpty());
        assertEquals("https://m.example/manage/alerts?event=6aba70011a25f8be7920e0ee",
                AlertPayloads.links(ratingEvent("ok"), Optional.of("https://m.example")).get("alert"));
    }
}
