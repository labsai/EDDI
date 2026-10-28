/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;

/**
 * Score rules per scale, in one place: what a valid score is, and how it maps
 * onto the 0..1 range that lets dashboards compare agents using different
 * scales (thumbs 0/1; stars (s−1)/4).
 */
public final class RatingScales {

    private RatingScales() {
    }

    /** Whether {@code score} is a valid score on {@code scale}. */
    public static boolean isValid(Scale scale, Integer score) {
        if (scale == null || score == null) {
            return false;
        }
        return switch (scale) {
            case THUMBS -> score == 0 || score == 1;
            case FIVE_STAR -> score >= 1 && score <= 5;
        };
    }

    /**
     * {@code score} on 0..1.
     *
     * @throws IllegalArgumentException
     *             for a score that is not valid on the scale
     */
    public static double normalize(Scale scale, int score) {
        if (!isValid(scale, score)) {
            throw new IllegalArgumentException("score " + score + " is not valid on scale " + scale);
        }
        return switch (scale) {
            case THUMBS -> score;
            case FIVE_STAR -> (score - 1) / 4.0;
        };
    }

    /**
     * The coarse bucket used to tag metrics: {@code low} below 0.4, {@code neutral}
     * below 0.6, {@code high} otherwise — so two stars are low, three neutral, four
     * high, and thumbs are low or high.
     */
    public static String bucket(double normalizedScore) {
        if (normalizedScore < 0.4) {
            return "low";
        }
        return normalizedScore < 0.6 ? "neutral" : "high";
    }
}
