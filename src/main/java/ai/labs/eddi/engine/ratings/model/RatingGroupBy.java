/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.model;

import java.util.Locale;

/**
 * How a summary splits its ratings into buckets. Bucket keys are strings, the
 * same on both datastores: {@code all} for {@link #NONE}, {@code 2026-10-24}
 * for {@link #DAY} (UTC), {@code 2026-W43} for {@link #WEEK} (ISO week, UTC),
 * the version number for {@link #AGENT_VERSION}, the channel for
 * {@link #CHANNEL}.
 */
public enum RatingGroupBy {
    NONE, DAY, WEEK, AGENT_VERSION, CHANNEL;

    /** The bucket key of {@link #NONE}. */
    public static final String ALL_BUCKET = "all";

    /**
     * Parses {@code none|day|week|agentVersion|channel}, case-insensitively.
     *
     * @return {@link #NONE} for a blank value
     * @throws IllegalArgumentException
     *             for anything else
     */
    public static RatingGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none" -> NONE;
            case "day" -> DAY;
            case "week" -> WEEK;
            case "agentversion", "agent_version" -> AGENT_VERSION;
            case "channel" -> CHANNEL;
            default -> throw new IllegalArgumentException("groupBy must be one of none, day, week, agentVersion, channel");
        };
    }
}
