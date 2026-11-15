/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Pure helpers over {@link ToolQuotaConfig}: finding the rule for a call,
 * reading its window, and the arithmetic of fixed windows. No state, no I/O.
 */
public final class ToolQuotaRules {

    /** Smallest window a rule may use. */
    public static final Duration MIN_WINDOW = Duration.ofMinutes(1);

    /** Largest window a rule may use. */
    public static final Duration MAX_WINDOW = Duration.ofDays(31);

    /** How long past its window's end a counter row is kept, before it is swept. */
    public static final Duration RETENTION_GRACE = Duration.ofHours(1);

    /**
     * The window value that scopes a counter to one run instead of a time span: see
     * {@link ToolQuotaConfig#RUN_WINDOW}.
     */
    public static final String RUN_WINDOW = ToolQuotaConfig.RUN_WINDOW;

    /**
     * How long a per-run counter row is kept after its first call. A run has no
     * natural end the store can see, so the row simply outlives any realistic run;
     * a run still going after this long starts a fresh count.
     */
    public static final Duration RUN_RETENTION = MAX_WINDOW;

    private ToolQuotaRules() {
    }

    /** Whether {@code window} names the per-run scope (case-insensitive). */
    public static boolean isRunWindow(String window) {
        return window != null && RUN_WINDOW.equalsIgnoreCase(window.strip());
    }

    /**
     * The rule that governs one call, resolved like {@code toolRateLimits}: an
     * entry under the dispatch name the model invoked wins over one under the
     * built-in slug.
     *
     * @return the matching rule with the key it was configured under, or
     *         {@code null} when no rule applies
     */
    public static Resolved resolve(ToolQuotaConfig config, String dispatchName, String canonicalName) {
        if (config == null || config.getTools() == null || config.getTools().isEmpty()) {
            return null;
        }
        Map<String, ToolQuotaConfig.Rule> tools = config.getTools();
        String key = dispatchName;
        ToolQuotaConfig.Rule rule = key == null ? null : tools.get(key);
        if (rule == null && canonicalName != null) {
            key = canonicalName;
            rule = tools.get(key);
        }
        if (rule == null) {
            return null;
        }
        Duration window;
        try {
            // null stands for the per-run scope: no time span, the counter is keyed by run
            window = isRunWindow(rule.getWindow()) ? null : parseWindow(rule.getWindow());
        } catch (IllegalArgumentException e) {
            // Rejected at save time; a stored document from before that check, or one
            // edited around it, must not run unbounded. Treat it as unusable and let
            // the caller fail closed.
            throw new IllegalStateException("toolQuotas rule '" + key + "' has an unusable window", e);
        }
        if (rule.getLimit() == null || rule.getLimit() < 1) {
            throw new IllegalStateException("toolQuotas rule '" + key + "' has no positive limit");
        }
        String pool = rule.getPool();
        String counter = pool != null && !pool.isBlank() ? "pool:" + pool.strip() : "tool:" + key;
        return new Resolved(key, counter, rule.getLimit(), window);
    }

    /**
     * Parses an ISO-8601 duration ({@code P1D}, {@code PT12H}, {@code PT90M}) and
     * checks it lies between {@link #MIN_WINDOW} and {@link #MAX_WINDOW}.
     *
     * @throws IllegalArgumentException
     *             when it is absent, not a duration, or out of range
     */
    public static Duration parseWindow(String window) {
        if (window == null || window.isBlank()) {
            throw new IllegalArgumentException("window is required (ISO-8601, e.g. P1D)");
        }
        Duration parsed;
        try {
            parsed = Duration.parse(window.strip());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("window '" + window + "' is not an ISO-8601 duration of days, hours and minutes (e.g. P1D, PT12H)");
        }
        if (parsed.compareTo(MIN_WINDOW) < 0) {
            throw new IllegalArgumentException("window '" + window + "' is shorter than the minimum PT1M");
        }
        if (parsed.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window '" + window + "' is longer than the maximum P31D");
        }
        return parsed;
    }

    /**
     * Start of the fixed window containing {@code now}, in epoch seconds. Windows
     * are aligned to the UTC epoch, so a one-day window starts at 00:00 UTC.
     */
    public static long windowStart(Instant now, Duration window) {
        long length = window.getSeconds();
        return Math.floorDiv(now.getEpochSecond(), length) * length;
    }

    /** The instant a window that started at {@code windowStart} ends. */
    public static Instant windowEnd(long windowStart, Duration window) {
        return Instant.ofEpochSecond(windowStart + window.getSeconds());
    }

    /**
     * A rule matched to one call.
     *
     * @param ruleKey
     *            the configured key that matched (dispatch name or slug)
     * @param counter
     *            the counter name: {@code pool:<pool>} or {@code tool:<ruleKey>}
     * @param limit
     *            calls permitted per window
     * @param window
     *            the window length, or {@code null} for a per-run rule
     */
    public record Resolved(String ruleKey, String counter, int limit, Duration window) {

        /** True when the rule counts per run rather than per time window. */
        public boolean perRun() {
            return window == null;
        }
    }
}
