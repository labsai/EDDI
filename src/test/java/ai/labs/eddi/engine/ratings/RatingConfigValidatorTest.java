/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.CommentSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ConversationRatingSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.api.IRestAgentProfile.RatingProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ratingConfig: validation, defaults and the client profile")
class RatingConfigValidatorTest {

    private static AgentConfiguration with(Consumer<RatingConfig> change) {
        AgentConfiguration configuration = new AgentConfiguration();
        RatingConfig rating = new RatingConfig();
        rating.setEnabled(true);
        change.accept(rating);
        configuration.setRatingConfig(rating);
        return configuration;
    }

    private static CommentSettings comment(int maxLength, double askBelow) {
        CommentSettings comment = new CommentSettings();
        comment.setEnabled(true);
        comment.setMaxLength(maxLength);
        comment.setAskBelowScore(askBelow);
        return comment;
    }

    @Test
    @DisplayName("no ratingConfig and a valid one pass")
    void valid() {
        assertDoesNotThrow(() -> RatingConfigValidator.validate(new AgentConfiguration()));
        assertDoesNotThrow(() -> RatingConfigValidator.validate(null));
        assertDoesNotThrow(() -> RatingConfigValidator.validate(with(r -> {
            r.setComment(comment(4000, 0.6));
            r.setReasonTags(List.of("Helpful", "x".repeat(60)));
        })));
    }

    @Test
    @DisplayName("out-of-range values are refused with a 400 naming the field")
    void invalid() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("tag" + i);
        }
        ConversationRatingSettings negativeTurns = new ConversationRatingSettings();
        negativeTurns.setAskAfterTurns(-1);
        ConversationRatingSettings longPrompt = new ConversationRatingSettings();
        longPrompt.setPromptText("p".repeat(501));

        assertRefused("maxLength", with(r -> r.setComment(comment(4001, 0))));
        assertRefused("maxLength", with(r -> r.setComment(comment(0, 0))));
        assertRefused("askBelowScore", with(r -> r.setComment(comment(100, 1.5))));
        assertRefused("reasonTags", with(r -> r.setReasonTags(tooMany)));
        assertRefused("reasonTags", with(r -> r.setReasonTags(List.of("y".repeat(61)))));
        assertRefused("reasonTags", with(r -> r.setReasonTags(List.of("a", "a"))));
        assertRefused("reasonTags", with(r -> r.setReasonTags(Collections.singletonList(" "))));
        assertRefused("ratingWindowHours", with(r -> r.setRatingWindowHours(-1)));
        assertRefused("askAfterTurns", with(r -> r.setConversationRating(negativeTurns)));
        assertRefused("promptText", with(r -> r.setConversationRating(longPrompt)));
    }

    private static void assertRefused(String field, AgentConfiguration configuration) {
        BadRequestException e = assertThrows(BadRequestException.class, () -> RatingConfigValidator.validate(configuration));
        assertTrue(e.getMessage().contains(field), e.getMessage());
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("an unknown scale fails to deserialize; old configs simply lack the field")
    void json() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(InvalidFormatException.class,
                () -> mapper.readValue("{\"ratingConfig\":{\"enabled\":true,\"scale\":\"TEN_STAR\"}}", AgentConfiguration.class));
        assertNull(mapper.readValue("{}", AgentConfiguration.class).getRatingConfig());

        RatingConfig parsed = mapper.readValue("{\"ratingConfig\":{\"enabled\":true,\"scale\":\"FIVE_STAR\",\"messageRating\":{\"enabled\":true}}}",
                AgentConfiguration.class).getRatingConfig();
        assertEquals(Scale.FIVE_STAR, parsed.getScale());
        assertTrue(parsed.isConversationRatingEnabled(), "conversation rating is on unless switched off");
        assertTrue(parsed.isMessageRatingEnabled());
        assertFalse(parsed.isCommentEnabled());
        assertEquals(RatingConfig.DEFAULT_RATING_WINDOW_HOURS, parsed.getRatingWindowHours());
        String written = mapper.writeValueAsString(parsed);
        assertFalse(written.contains("conversationRatingEnabled"), "derived flags are not part of the stored config: " + written);
    }

    @Test
    @DisplayName("the profile carries only what a chat client renders, with the limits clamped")
    void profile() {
        RatingConfig config = with(r -> {
            r.setScale(Scale.FIVE_STAR);
            r.setComment(comment(9000, 0.6));
            r.setReasonTags(List.of("Helpful"));
        }).getRatingConfig();

        RatingProfile profile = RatingProfile.of(config);

        assertEquals("FIVE_STAR", profile.scale());
        assertTrue(profile.conversationRating());
        assertTrue(profile.askOnEnd());
        assertFalse(profile.messageRating());
        assertTrue(profile.comment());
        assertEquals(RatingConfigValidator.MAX_COMMENT_LENGTH, profile.commentMaxLength(), "an imported over-limit value is clamped");
        assertEquals(0.6, profile.commentAskBelowScore());
        assertEquals(List.of("Helpful"), profile.reasonTags());
        assertEquals(168, profile.ratingWindowHours());
    }
}
