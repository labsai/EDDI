/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The digest, output-match and sparse-event shapes of the alert webhook body
 * and Slack text.
 */
class AlertPayloadsBranchTest {

    private static AlertEvent event(AlertEvent.Kind kind, String conversationId, AlertMatch match, Integer digestCount, List<String> items) {
        return new AlertEvent("evt-1", "rule-1", "Rule", null, kind, "agent-1", 2, conversationId, null, "3 errors", match, List.of(),
                AlertEvent.State.PENDING, null, "key", 0, digestCount, items, null, null, 0);
    }

    @Test
    void aSparseEventLeavesItsOptionalFieldsNull() {
        Map<String, Object> body = AlertPayloads.webhook(event(AlertEvent.Kind.ALERT, null, null, null, null), Optional.of("https://m"));
        assertNull(body.get("firedAt"));
        assertNull(body.get("source"));
        assertFalse(body.containsKey("match"));
        assertFalse(body.containsKey("digestCount"));
        assertEquals(Map.of("alert", "https://m/manage/alerts?event=evt-1"), body.get("links"), "no conversation, no conversation link");
    }

    @Test
    void withoutAManagerUrlThereAreNoLinks() {
        Map<String, Object> body = AlertPayloads.webhook(event(AlertEvent.Kind.ALERT, "c-1", null, null, null), Optional.empty());
        assertFalse(body.containsKey("links"));
        String slack = AlertPayloads.slackText(event(AlertEvent.Kind.ALERT, "c-1", null, null, null), Optional.empty());
        assertFalse(slack.contains("Open in the Manager"), slack);
        assertTrue(slack.contains("Conversation: `c-1`"), slack);
    }

    @Test
    void aDigestCarriesItsCountAndItems() {
        var event = event(AlertEvent.Kind.DIGEST, null, null, 5, List.of("first <b>", "second"));
        Map<String, Object> body = AlertPayloads.webhook(event, Optional.empty());
        assertEquals(5, body.get("digestCount"));
        assertEquals(List.of("first <b>", "second"), body.get("digestItems"));

        String slack = AlertPayloads.slackText(event, Optional.of("https://m"));
        assertTrue(slack.startsWith(":bell: *Alert digest* — 3 errors"), slack);
        assertTrue(slack.contains("• first &lt;b&gt;"), slack);
        assertTrue(slack.contains("… and 3 more"), slack);
        assertTrue(slack.endsWith("<https://m/manage/alerts?event=evt-1|Open in the Manager>"), slack);
        assertFalse(slack.contains("Conversation:"), slack);
    }

    @Test
    void aDigestThatListsEveryItemSaysNoMore() {
        String complete = AlertPayloads.slackText(event(AlertEvent.Kind.DIGEST, null, null, 2, List.of("a", "b")), Optional.empty());
        assertFalse(complete.contains("more"), complete);
        String uncounted = AlertPayloads.slackText(event(AlertEvent.Kind.DIGEST, null, null, null, List.of("a")), Optional.empty());
        assertFalse(uncounted.contains("more"), uncounted);
        String noItems = AlertPayloads.slackText(event(AlertEvent.Kind.DIGEST, null, null, 4, null), Optional.empty());
        assertFalse(noItems.contains("•"), noItems);
    }

    @Test
    void anOutputMatchCarriesItsValueQuotedSafely() {
        var match = AlertMatch.output("output contains 'refund'", "refund `now`");
        var event = event(AlertEvent.Kind.ALERT, "c-1", match, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) AlertPayloads.webhook(event, Optional.empty()).get("match");
        assertEquals("refund `now`", body.get("value"));
        assertFalse(body.containsKey("errorKind"));
        assertFalse(body.containsKey("ratingId"));

        String slack = AlertPayloads.slackText(event, Optional.empty());
        assertTrue(slack.contains("Value: `refund 'now'`"), "a backtick cannot close the code span: " + slack);
        assertFalse(slack.contains("Tags:"), slack);
    }

    @Test
    void aRatingWithoutACommentOmitsTheExcerpt() {
        var match = new AlertMatch(List.of("score <= 2"), null, "r-1", 3, "STARS_5", 1, 0.0, List.of(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) AlertPayloads.webhook(event(AlertEvent.Kind.ALERT, "c-1", match, null, null),
                Optional.empty()).get("match");
        assertEquals("r-1", body.get("ratingId"));
        assertEquals(3, body.get("stepIndex"));
        assertFalse(body.containsKey("commentExcerpt"));
        String slack = AlertPayloads.slackText(event(AlertEvent.Kind.ALERT, "c-1", match, null, null), Optional.empty());
        assertFalse(slack.contains("Tags:"), "an empty tag list prints nothing: " + slack);
        assertFalse(slack.contains("> "), slack);
    }

    @Test
    void escapeTreatsNullAsEmpty() {
        assertEquals("", AlertPayloads.escape(null));
        assertTrue(AlertPayloads.slackText(event(AlertEvent.Kind.ALERT, null, null, null, null), Optional.empty()).startsWith(
                ":rotating_light: *Alert* — 3 errors"));
    }
}
