/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Agent Health measure — its section, label, kind, unit, direction,
 * default thresholds and minimum sample — in one place.
 * <p>
 * The thresholds are the defaults of the {@code support} preset's intent (a
 * chat agent people talk to); health profiles and presets (H4) will override
 * them per agent. A measure below its minimum sample is banded {@code GREY}
 * with a hint, never green or red on a handful of turns.
 */
public final class HealthDefaults {

    public static final String RELIABILITY = "reliability";
    public static final String EFFECTIVENESS = "effectiveness";
    public static final String QUALITY = "quality";
    public static final String EFFICIENCY = "efficiency";
    public static final String ADOPTION = "adoption";
    public static final String BUSINESS = "business";

    /** The sections in display order. */
    public static final List<String> SECTIONS = List.of(RELIABILITY, EFFECTIVENESS, QUALITY, EFFICIENCY, ADOPTION, BUSINESS);

    public static final String SCORED = "SCORED";
    public static final String INFORMATIONAL = "INFORMATIONAL";

    /** Preset of an agent with interactive traffic (or none at all). */
    public static final String PRESET_SUPPORT = "support";
    /** Preset of an agent driven only by schedules, webhooks, A2A, groups. */
    public static final String PRESET_TASK = "task";

    /** A golden-suite run older than this is reported {@code stale}. */
    public static final Duration GOLDEN_STALE_AFTER = Duration.ofDays(14);

    /** Platform incident: at least this many agents hit on the same day. */
    public static final int INCIDENT_MIN_AGENTS = 3;
    /** ...each with at least this many turns that day... */
    public static final long INCIDENT_MIN_TURNS = 10;
    /** ...a platform-affected share of at least this... */
    public static final double INCIDENT_MIN_SHARE = 0.05;
    /** ...and at least this many failed attempts of the class. */
    public static final long INCIDENT_MIN_FAILURES = 3;

    /** Whether more is better, worse, or neither. */
    public enum Direction {
        HIGHER, LOWER, NONE
    }

    /**
     * One measure's definition.
     *
     * @param minSample
     *            below this {@code n} the band is {@code GREY} and no change is
     *            called significant
     * @param sampleNoun
     *            what {@code n} counts, for the hint ("turns", "sessions")
     * @param thresholds
     *            band limits, or {@code null} for an informational measure
     */
    public record Definition(String id, String section, String label, String kind, boolean critical, String unit, Direction direction,
            long minSample, String sampleNoun, Thresholds thresholds) {
    }

    /** Every measure by id, in display order. */
    public static final Map<String, Definition> MEASURES;

    static {
        Map<String, Definition> m = new LinkedHashMap<>();
        // Reliability: n is turns.
        put(m, new Definition("agentFailureRate", RELIABILITY, "Agent-caused failures", SCORED, true, "ratio", Direction.LOWER, 30, "turns",
                new Thresholds(0.01, 0.05, false)));
        put(m, new Definition("platformFailureRate", RELIABILITY, "Turns hit by provider or platform trouble", INFORMATIONAL, false, "ratio",
                Direction.LOWER, 30, "turns", null));
        put(m, new Definition("apdex", RELIABILITY, "Response time (Apdex)", SCORED, false, "score", Direction.HIGHER, 30, "timed turns",
                new Thresholds(0.85, 0.7, true)));
        put(m, new Definition("turnDurationP95", RELIABILITY, "Turn duration, 95th percentile (approx.)", INFORMATIONAL, false, "ms",
                Direction.LOWER, 30, "timed turns", null));
        put(m, new Definition("fallbackRate", RELIABILITY, "Fallback answers", SCORED, false, "ratio", Direction.LOWER, 30, "turns",
                new Thresholds(0.02, 0.1, false)));
        // Effectiveness: over the engaged, closed sessions of judged origins (H3).
        // Resolution keeps the plan's support thresholds; escalation and abandonment
        // have none until health profiles (H4) — what is normal differs too much
        // between agents to guess.
        put(m, new Definition("resolutionRate", EFFECTIVENESS, "Resolution", SCORED, true, "ratio", Direction.HIGHER, 30, "engaged sessions",
                new Thresholds(0.7, 0.4, true)));
        put(m, new Definition("escalationRate", EFFECTIVENESS, "Escalation", SCORED, false, "ratio", Direction.LOWER, 30, "engaged sessions",
                null));
        put(m, new Definition("abandonmentRate", EFFECTIVENESS, "Abandonment", SCORED, false, "ratio", Direction.LOWER, 30, "engaged sessions",
                null));
        put(m, new Definition("deflectionRate", EFFECTIVENESS, "Deflection", INFORMATIONAL, false, "ratio", Direction.NONE, 30,
                "engaged sessions", null));
        put(m, new Definition("outcomeCoverage", EFFECTIVENESS, "Outcome coverage", SCORED, false, "ratio", Direction.HIGHER, 30,
                "engaged sessions", new Thresholds(0.8, 0.5, true)));
        // Quality: from ratings.
        put(m, new Definition("ratingScore", QUALITY, "Rating (lower confidence bound)", SCORED, false, "score", Direction.HIGHER, 10,
                "conversation ratings", new Thresholds(0.7, 0.5, true)));
        put(m, new Definition("ratingResponseRate", QUALITY, "Sessions rated", INFORMATIONAL, false, "ratio", Direction.NONE, 30, "sessions",
                null));
        put(m, new Definition("replyThumbsUp", QUALITY, "Replies rated thumbs up", SCORED, false, "ratio", Direction.HIGHER, 10,
                "rated replies", new Thresholds(0.8, 0.6, true)));
        put(m, new Definition("guardrailTripRate", QUALITY, "Guardrail trips per turn", INFORMATIONAL, false, "ratio", Direction.NONE, 30,
                "turns", null));
        // Efficiency.
        put(m, new Definition("costPerSession", EFFICIENCY, "Cost per session", INFORMATIONAL, false, "usd", Direction.LOWER, 30, "sessions",
                null));
        put(m, new Definition("costPerResolvedSession", EFFICIENCY, "Cost per resolved session", INFORMATIONAL, false, "usd", Direction.LOWER,
                30, "resolved sessions", null));
        put(m, new Definition("tokensPerTurn", EFFICIENCY, "Tokens per turn", INFORMATIONAL, false, "tokens", Direction.LOWER, 30, "turns",
                null));
        // Adoption.
        put(m, new Definition("sessions", ADOPTION, "Sessions", INFORMATIONAL, false, "count", Direction.HIGHER, 0, "sessions", null));
        put(m, new Definition("engagedSessions", ADOPTION, "Engaged sessions", INFORMATIONAL, false, "count", Direction.HIGHER, 0, "sessions",
                null));
        put(m, new Definition("distinctUsers", ADOPTION, "Distinct users", INFORMATIONAL, false, "count", Direction.HIGHER, 0, "users", null));
        put(m, new Definition("returningUserShare", ADOPTION, "Returning users", INFORMATIONAL, false, "ratio", Direction.HIGHER, 30, "users",
                null));
        put(m, new Definition("turnsPerSession", ADOPTION, "Turns per session", INFORMATIONAL, false, "count", Direction.NONE, 30, "sessions",
                null));
        MEASURES = Collections.unmodifiableMap(m);
    }

    private HealthDefaults() {
    }

    private static void put(Map<String, Definition> m, Definition d) {
        m.put(d.id(), d);
    }

    /** The definition of a measure, or {@code null} for an unknown id. */
    public static Definition of(String id) {
        return MEASURES.get(id);
    }

    /**
     * The band of {@code value} under {@code thresholds}: {@code GREEN} at or past
     * the green limit, {@code RED} at or past the red one, {@code AMBER} between.
     */
    public static String band(double value, Thresholds thresholds) {
        if (thresholds == null) {
            return "GREY";
        }
        if (thresholds.higherIsBetter()) {
            return value >= thresholds.green() ? "GREEN" : value <= thresholds.red() ? "RED" : "AMBER";
        }
        return value <= thresholds.green() ? "GREEN" : value >= thresholds.red() ? "RED" : "AMBER";
    }
}
