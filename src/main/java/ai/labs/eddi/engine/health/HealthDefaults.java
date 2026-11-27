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
    /** Preset of a sales agent: conversions, abandonment. */
    public static final String PRESET_SALES = "sales";
    /** Preset of a general assistant: answers, no fixed goal. */
    public static final String PRESET_ASSISTANT = "assistant";
    /** Preset of an agent driven only by schedules, webhooks, A2A, groups. */
    public static final String PRESET_TASK = "task";

    /** The status values (contract §1). */
    public static final String STATUS_HEALTHY = "HEALTHY";
    public static final String STATUS_WATCH = "WATCH";
    public static final String STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION";
    public static final String STATUS_NOT_ENOUGH_DATA = "NOT_ENOUGH_DATA";
    public static final List<String> STATUSES = List.of(STATUS_HEALTHY, STATUS_WATCH, STATUS_NEEDS_ATTENTION, STATUS_NOT_ENOUGH_DATA);

    /**
     * One preset: the measures whose red band means "needs attention", and the band
     * limits of every measure it scores. A health profile overrides any of it per
     * agent.
     *
     * @param thresholds
     *            band limits by measure id; a scored measure without an entry has
     *            no band (GREY)
     */
    public record Preset(String id, String label, String description, List<String> critical, Map<String, Thresholds> thresholds) {
        public Preset {
            critical = List.copyOf(critical);
            thresholds = Collections.unmodifiableMap(new LinkedHashMap<>(thresholds));
        }
    }

    /** The presets of plan §4.6, by id, in display order. */
    public static final Map<String, Preset> PRESETS;

    static {
        Map<String, Preset> p = new LinkedHashMap<>();
        // support — a chat agent people bring a problem to: does it solve it, does it
        // hand off too often, do people give up?
        Map<String, Thresholds> support = new LinkedHashMap<>();
        support.put("agentFailureRate", new Thresholds(0.01, 0.05, false));
        support.put("apdex", new Thresholds(0.85, 0.7, true));
        support.put("fallbackRate", new Thresholds(0.02, 0.1, false));
        support.put("resolutionRate", new Thresholds(0.7, 0.4, true));
        support.put("escalationRate", new Thresholds(0.15, 0.3, false));
        support.put("abandonmentRate", new Thresholds(0.2, 0.35, false));
        support.put("outcomeCoverage", new Thresholds(0.8, 0.5, true));
        support.put("ratingScore", new Thresholds(0.7, 0.5, true));
        support.put("replyThumbsUp", new Thresholds(0.8, 0.6, true));
        p.put(PRESET_SUPPORT, new Preset(PRESET_SUPPORT, "Support", "A chat agent that resolves people's problems: resolution and "
                + "agent-caused failures are critical; escalation and abandonment are banded.", List.of("agentFailureRate", "resolutionRate"),
                support));
        // sales — most conversations do not convert, so resolution is lower; people
        // walking away mid-conversation is what hurts.
        Map<String, Thresholds> sales = new LinkedHashMap<>();
        sales.put("agentFailureRate", new Thresholds(0.01, 0.05, false));
        sales.put("apdex", new Thresholds(0.85, 0.7, true));
        sales.put("fallbackRate", new Thresholds(0.02, 0.1, false));
        sales.put("resolutionRate", new Thresholds(0.4, 0.15, true));
        sales.put("escalationRate", new Thresholds(0.25, 0.45, false));
        sales.put("abandonmentRate", new Thresholds(0.3, 0.5, false));
        sales.put("outcomeCoverage", new Thresholds(0.8, 0.5, true));
        sales.put("ratingScore", new Thresholds(0.7, 0.5, true));
        sales.put("replyThumbsUp", new Thresholds(0.8, 0.6, true));
        p.put(PRESET_SALES, new Preset(PRESET_SALES, "Sales", "An agent that converts: abandonment and agent-caused failures are critical; "
                + "resolution (conversions) is banded lower than for support.", List.of("agentFailureRate", "abandonmentRate"), sales));
        // assistant — open-ended help: no fixed goal to resolve, longer answers.
        Map<String, Thresholds> assistant = new LinkedHashMap<>();
        assistant.put("agentFailureRate", new Thresholds(0.01, 0.05, false));
        assistant.put("apdex", new Thresholds(0.8, 0.6, true));
        assistant.put("fallbackRate", new Thresholds(0.02, 0.1, false));
        assistant.put("resolutionRate", new Thresholds(0.6, 0.3, true));
        assistant.put("abandonmentRate", new Thresholds(0.3, 0.5, false));
        assistant.put("outcomeCoverage", new Thresholds(0.6, 0.3, true));
        assistant.put("ratingScore", new Thresholds(0.7, 0.5, true));
        assistant.put("replyThumbsUp", new Thresholds(0.8, 0.6, true));
        p.put(PRESET_ASSISTANT, new Preset(PRESET_ASSISTANT, "Assistant", "A general assistant without one goal: agent-caused failures are "
                + "critical; resolution is banded loosely, escalation not at all.", List.of("agentFailureRate"), assistant));
        // task — runs without a person: does it run, how long, at what cost.
        Map<String, Thresholds> task = new LinkedHashMap<>();
        task.put("agentFailureRate", new Thresholds(0.02, 0.1, false));
        task.put("apdex", new Thresholds(0.7, 0.5, true));
        task.put("fallbackRate", new Thresholds(0.05, 0.2, false));
        p.put(PRESET_TASK, new Preset(PRESET_TASK, "Task", "An agent driven by schedules, webhooks, A2A or groups: the run failure rate is "
                + "critical; duration and fallbacks are banded; set a cost budget per run in the profile.", List.of("agentFailureRate"), task));
        PRESETS = Collections.unmodifiableMap(p);
    }

    /** A preset by id, or {@code null}. */
    public static Preset preset(String id) {
        return id == null ? null : PRESETS.get(id);
    }

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
