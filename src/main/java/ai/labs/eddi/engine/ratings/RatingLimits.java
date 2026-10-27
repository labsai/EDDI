/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.CommentSettings;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;

/**
 * The limits the rating endpoints apply whatever the stored configuration says.
 * Save-time validation ({@link RatingConfigValidator}) keeps new agents inside
 * them; these clamps cover an agent that reached the store some other way.
 */
public final class RatingLimits {

    private RatingLimits() {
    }

    /** The comment cap of this configuration, within 1..4000. */
    public static int commentMaxLength(RatingConfig config) {
        CommentSettings comment = config == null ? null : config.getComment();
        int configured = comment == null ? CommentSettings.DEFAULT_MAX_LENGTH : comment.getMaxLength();
        if (configured < 1) {
            return CommentSettings.DEFAULT_MAX_LENGTH;
        }
        return Math.min(configured, RatingConfigValidator.MAX_COMMENT_LENGTH);
    }
}
