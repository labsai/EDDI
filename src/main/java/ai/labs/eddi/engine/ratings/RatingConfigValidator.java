/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.engine.exception.ErrorResponses;
import jakarta.ws.rs.BadRequestException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Save-time checks of {@link RatingConfig}, run when an agent is created,
 * updated or duplicated through the agent store.
 * <p>
 * The limits bound what a single rating can carry — the tag list and the
 * comment length end up in every rating row and in the profile every chat
 * client loads — so an agent cannot be saved with values the rating endpoints
 * would later have to clamp. The rating endpoints clamp anyway, for agents that
 * reached the store another way (a ZIP import, an older version).
 */
public final class RatingConfigValidator {

    /** The longest comment an agent may allow. */
    public static final int MAX_COMMENT_LENGTH = 4000;
    /** The most reason tags an agent may define. */
    public static final int MAX_REASON_TAGS = 20;
    /** The longest reason tag. */
    public static final int MAX_TAG_LENGTH = 60;
    /** The longest prompt text on the rating card. */
    public static final int MAX_PROMPT_LENGTH = 500;

    private RatingConfigValidator() {
    }

    /**
     * @throws BadRequestException
     *             naming the offending field, when the rating configuration is
     *             invalid
     */
    public static void validate(AgentConfiguration configuration) {
        RatingConfig rating = configuration == null ? null : configuration.getRatingConfig();
        if (rating == null) {
            return;
        }
        if (rating.getRatingWindowHours() < 0) {
            throw invalid("ratingConfig.ratingWindowHours must be 0 (no limit) or a positive number of hours");
        }
        var conversation = rating.getConversationRating();
        if (conversation != null) {
            if (conversation.getAskAfterTurns() < 0) {
                throw invalid("ratingConfig.conversationRating.askAfterTurns must be 0 (never) or positive");
            }
            if (conversation.getPromptText() != null && conversation.getPromptText().length() > MAX_PROMPT_LENGTH) {
                throw invalid("ratingConfig.conversationRating.promptText must be at most " + MAX_PROMPT_LENGTH + " characters");
            }
        }
        var comment = rating.getComment();
        if (comment != null) {
            if (comment.getMaxLength() < 1 || comment.getMaxLength() > MAX_COMMENT_LENGTH) {
                throw invalid("ratingConfig.comment.maxLength must be between 1 and " + MAX_COMMENT_LENGTH);
            }
            double askBelow = comment.getAskBelowScore();
            if (Double.isNaN(askBelow) || askBelow < 0 || askBelow > 1) {
                throw invalid("ratingConfig.comment.askBelowScore must be between 0 and 1 (a normalized score)");
            }
        }
        validateTags(rating.getReasonTags());
    }

    private static void validateTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        if (tags.size() > MAX_REASON_TAGS) {
            throw invalid("ratingConfig.reasonTags may hold at most " + MAX_REASON_TAGS + " tags");
        }
        Set<String> seen = new HashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                throw invalid("ratingConfig.reasonTags must not contain empty tags");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw invalid("ratingConfig.reasonTags: each tag must be at most " + MAX_TAG_LENGTH + " characters");
            }
            if (!seen.add(tag)) {
                throw invalid("ratingConfig.reasonTags must not contain duplicates");
            }
        }
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(message, ErrorResponses.badRequest(message));
    }
}
