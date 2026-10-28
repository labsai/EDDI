/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.OTHER_AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.rating;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AlertMatchers")
class AlertMatchersTest {

    @Nested
    @DisplayName("RATING")
    class Ratings {

        @Test
        @DisplayName("fires at or below the score threshold, not above it")
        void scoreThreshold() {
            AlertRule rule = ratingRule(0.25, null, null);
            assertTrue(AlertMatchers.matchRating(rule, rating(2, null, List.of())).isPresent()); // 0.25
            assertFalse(AlertMatchers.matchRating(rule, rating(3, null, List.of())).isPresent()); // 0.5
        }

        @Test
        @DisplayName("onComment fires on a comment, not on a blank one; criteria are OR-ed and all listed")
        void commentAndTags() {
            AlertRule rule = ratingRule(0.0, true, List.of("Too slow"));
            Optional<AlertMatch> match = AlertMatchers.matchRating(rule, rating(5, "Great, but slow", List.of("Too slow")));
            assertEquals(List.of("comment", "tag: Too slow"), match.orElseThrow().matched());
            assertFalse(AlertMatchers.matchRating(ratingRule(null, true, null), rating(5, "   ", List.of())).isPresent());
        }

        @Test
        @DisplayName("a rule for another agent, a disabled rule, or an error rule never fires on a rating")
        void wrongRule() {
            AlertRule other = new AlertRule("r", "x", true, AlertScope.agent(OTHER_AGENT), ratingRule(1.0, null, null).source(),
                    ratingRule(1.0, null, null).condition(), List.of(), null, null, null, null, null, null);
            assertFalse(AlertMatchers.matchRating(other, rating(1, null, List.of())).isPresent());
            AlertRule disabled = new AlertRule("r", "x", false, AlertScope.agent(AGENT), other.source(), other.condition(), List.of(), null,
                    null, null, null, null, null);
            assertFalse(AlertMatchers.matchRating(disabled, rating(1, null, List.of())).isPresent());
            assertFalse(AlertMatchers.matchRating(errorRule(List.of(ErrorKind.TURN_ERROR)), rating(1, null, List.of())).isPresent());
        }

        @Test
        @DisplayName("an every-agent rule fires for any agent")
        void allAgents() {
            AlertRule base = ratingRule(1.0, null, null);
            AlertRule all = new AlertRule("r", "x", true, new AlertScope(true, List.of()), base.source(), base.condition(), List.of(), null,
                    null, null, null, null, null);
            assertTrue(AlertMatchers.matchRating(all, rating(1, null, List.of())).isPresent());
        }

        @Test
        @DisplayName("the match carries the comment cut to 280 characters, and the summary carries no comment at all")
        void excerptAndSummary() {
            String comment = "x".repeat(400);
            AlertMatch match = AlertMatchers.matchRating(ratingRule(null, true, null), rating(1, comment, List.of())).orElseThrow();
            assertEquals(280, match.commentExcerpt().codePointCount(0, match.commentExcerpt().length()));
            assertTrue(match.commentExcerpt().endsWith("…"));
            String summary = AlertMatchers.summary(ratingRule(null, true, null), match, AGENT, 3);
            assertFalse(summary.contains("xxx"), summary);
            assertTrue(summary.contains("conversation rating 1 (FIVE_STAR)"), summary);
        }
    }

    @Nested
    @DisplayName("CONVERSATION_ERROR")
    class Errors {

        @Test
        @DisplayName("fires only for the listed kinds")
        void kinds() {
            AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR, ErrorKind.TURN_DEADLINE));
            assertTrue(AlertMatchers.matchTurn(rule, new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.TURN_DEADLINE)).isPresent());
            assertFalse(AlertMatchers.matchTurn(rule, new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.COST_CEILING)).isPresent());
            assertFalse(AlertMatchers.matchTurn(rule, new TurnOutcome(CONVERSATION, OTHER_AGENT, 2, ErrorKind.TURN_ERROR)).isPresent());
            assertFalse(AlertMatchers.matchTurn(ratingRule(1.0, null, null), new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.TURN_ERROR))
                    .isPresent());
        }

        @Test
        @DisplayName("the summary names the kind in words")
        void summary() {
            AlertRule rule = errorRule(List.of(ErrorKind.TURN_ERROR));
            AlertMatch match = AlertMatchers.matchTurn(rule, new TurnOutcome(CONVERSATION, AGENT, 2, ErrorKind.TURN_ERROR)).orElseThrow();
            assertEquals("Broken turns: a turn failed (agent " + AGENT + " v2)", AlertMatchers.summary(rule, match, AGENT, 2));
        }
    }
}
