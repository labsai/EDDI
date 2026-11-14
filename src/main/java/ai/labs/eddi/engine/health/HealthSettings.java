/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * The deployment-wide Agent Health settings ({@code eddi.health.*}).
 * Independent of {@code eddi.alerts.*}: switching alerting off does not stop
 * health collection, and the other way round.
 */
@ApplicationScoped
public class HealthSettings {

    /** Default {@code eddi.health.queue-capacity}. */
    static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    /** Default {@code eddi.health.orphan-sweep-window}. */
    static final Duration DEFAULT_ORPHAN_SWEEP_WINDOW = Duration.ofHours(48);
    /** Default {@code eddi.health.orphan-sweep-max}. */
    static final int DEFAULT_ORPHAN_SWEEP_MAX = 50_000;
    /** Default {@code eddi.health.rollup-interval}. */
    static final Duration DEFAULT_ROLLUP_INTERVAL = Duration.ofMinutes(5);
    /** Default {@code eddi.health.cache-ttl}. */
    static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);
    /** Default {@code eddi.health.daily-retention}. */
    static final Duration DEFAULT_DAILY_RETENTION = Duration.ofDays(730);
    /** Default {@code eddi.health.session-window}. */
    static final Duration DEFAULT_SESSION_WINDOW = Duration.ofHours(24);
    /** Default {@code eddi.health.email-session-window}. */
    static final Duration DEFAULT_EMAIL_SESSION_WINDOW = Duration.ofHours(72);
    /** Default {@code eddi.health.outcome-feed-overlap}. */
    static final Duration DEFAULT_OUTCOME_FEED_OVERLAP = Duration.ofMinutes(15);
    /** Default {@code eddi.health.min-outcome-coverage}. */
    static final double DEFAULT_MIN_OUTCOME_COVERAGE = 0.5;

    private final boolean enabled;
    private final int queueCapacity;
    private final Duration factsRetention;
    private final Duration orphanSweepWindow;
    private final int orphanSweepMax;
    private final Duration rollupInterval;
    private final Duration dailyRetention;
    private final Duration apdexT;
    private final Duration cacheTtl;
    private Duration sessionWindow = DEFAULT_SESSION_WINDOW;
    private Duration emailSessionWindow = DEFAULT_EMAIL_SESSION_WINDOW;
    private double minOutcomeCoverage = DEFAULT_MIN_OUTCOME_COVERAGE;
    private Duration outcomeFeedOverlap = DEFAULT_OUTCOME_FEED_OVERLAP;

    @Inject
    public HealthSettings(@ConfigProperty(name = "eddi.health.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.health.queue-capacity", defaultValue = "10000") int queueCapacity,
            @ConfigProperty(name = "eddi.health.facts-retention", defaultValue = "90d") Duration factsRetention,
            @ConfigProperty(name = "eddi.health.orphan-sweep-window", defaultValue = "48h") Duration orphanSweepWindow,
            @ConfigProperty(name = "eddi.health.orphan-sweep-max", defaultValue = "50000") int orphanSweepMax,
            @ConfigProperty(name = "eddi.health.rollup-interval", defaultValue = "5m") Duration rollupInterval,
            @ConfigProperty(name = "eddi.health.daily-retention", defaultValue = "730d") Duration dailyRetention,
            @ConfigProperty(name = "eddi.health.apdex-t", defaultValue = "4s") Duration apdexT,
            @ConfigProperty(name = "eddi.health.cache-ttl", defaultValue = "60s") Duration cacheTtl) {
        this.enabled = enabled;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.factsRetention = positiveOr(factsRetention, Duration.ofDays(90));
        this.orphanSweepWindow = positiveOr(orphanSweepWindow, DEFAULT_ORPHAN_SWEEP_WINDOW);
        this.orphanSweepMax = Math.max(1, orphanSweepMax);
        this.rollupInterval = positiveOr(rollupInterval, DEFAULT_ROLLUP_INTERVAL);
        this.dailyRetention = positiveOr(dailyRetention, DEFAULT_DAILY_RETENTION);
        this.apdexT = positiveOr(apdexT, Duration.ofMillis(HealthTurnAggregator.DEFAULT_APDEX_T_MS));
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? DEFAULT_CACHE_TTL : cacheTtl;
    }

    /**
     * The outcome settings (H3), set by CDI after construction so the constructors
     * the tests use stay as they are.
     */
    @Inject
    void outcomeSettings(@ConfigProperty(name = "eddi.health.session-window", defaultValue = "24h") Duration sessionWindow,
                         @ConfigProperty(name = "eddi.health.email-session-window", defaultValue = "72h") Duration emailSessionWindow,
                         @ConfigProperty(name = "eddi.health.min-outcome-coverage", defaultValue = "0.5") double minOutcomeCoverage) {
        this.sessionWindow = positiveOr(sessionWindow, DEFAULT_SESSION_WINDOW);
        this.emailSessionWindow = positiveOr(emailSessionWindow, DEFAULT_EMAIL_SESSION_WINDOW);
        this.minOutcomeCoverage = Double.isFinite(minOutcomeCoverage) && minOutcomeCoverage >= 0 && minOutcomeCoverage <= 1
                ? minOutcomeCoverage
                : DEFAULT_MIN_OUTCOME_COVERAGE;
    }

    /** The outcome feed's overlap (H3 review), injected like the settings above. */
    @Inject
    void outcomeFeedOverlap(@ConfigProperty(name = "eddi.health.outcome-feed-overlap", defaultValue = "15m") Duration overlap) {
        this.outcomeFeedOverlap = overlap == null || overlap.isNegative() ? DEFAULT_OUTCOME_FEED_OVERLAP : overlap;
    }

    /**
     * How far behind its watermark the outcome evaluation re-reads conversations
     * every pass ({@code eddi.health.outcome-feed-overlap}, 15 min): a turn row is
     * ordered by its capture time, set on the turn's node before it waits on that
     * node's queue, so it can reach the store after the watermark passed it. A row
     * later than this is judged with its conversation's next change or when its
     * session is due. Zero re-reads nothing.
     */
    public Duration outcomeFeedOverlap() {
        return outcomeFeedOverlap;
    }

    /** Collection settings; everything else at its default — for tests. */
    public HealthSettings(boolean enabled, int queueCapacity, Duration factsRetention) {
        this(enabled, queueCapacity, factsRetention, DEFAULT_ORPHAN_SWEEP_WINDOW, DEFAULT_ORPHAN_SWEEP_MAX, DEFAULT_ROLLUP_INTERVAL,
                DEFAULT_DAILY_RETENTION, Duration.ofMillis(HealthTurnAggregator.DEFAULT_APDEX_T_MS), DEFAULT_CACHE_TTL);
    }

    /**
     * Collection and orphan-sweep settings; the rest at its default — for tests.
     */
    HealthSettings(boolean enabled, int queueCapacity, Duration factsRetention, Duration orphanSweepWindow, int orphanSweepMax) {
        this(enabled, queueCapacity, factsRetention, orphanSweepWindow, orphanSweepMax, DEFAULT_ROLLUP_INTERVAL, DEFAULT_DAILY_RETENTION,
                Duration.ofMillis(HealthTurnAggregator.DEFAULT_APDEX_T_MS), DEFAULT_CACHE_TTL);
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    /** Defaults, for tests. */
    public static HealthSettings defaults() {
        return new HealthSettings(true, DEFAULT_QUEUE_CAPACITY, Duration.ofDays(90));
    }

    /** Whether turns are recorded at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Turn records the health worker may have waiting before it drops one. */
    public int queueCapacity() {
        return queueCapacity;
    }

    /**
     * How long turn rows are kept. Whole UTC days: a retention of 90 days keeps
     * today and the 90 days before it. Never less than one day.
     */
    public Duration factsRetention() {
        return factsRetention;
    }

    /** {@link #factsRetention()} in whole days, at least 1. */
    public long factsRetentionDays() {
        return Math.max(1L, (factsRetention.toHours() + 23) / 24);
    }

    /**
     * How far back the daily orphan sweep looks: the conversations of rows written
     * within it are checked against the conversation store. Covers the race of a
     * queued write landing after its conversation was deleted, with slack for a
     * missed day. Never zero.
     */
    public Duration orphanSweepWindow() {
        return orphanSweepWindow;
    }

    /** The most conversations one orphan sweep checks, at least 1. */
    public int orphanSweepMax() {
        return orphanSweepMax;
    }

    /**
     * How often the rollup job turns dirty days into daily rows — also how far
     * today's numbers may lag, and how long a computed view is cached. Never less
     * than 15 seconds.
     */
    public Duration rollupInterval() {
        return rollupInterval.compareTo(Duration.ofSeconds(15)) < 0 ? Duration.ofSeconds(15) : rollupInterval;
    }

    /** How long daily rows and change markers are kept. */
    public Duration dailyRetention() {
        return dailyRetention;
    }

    /** {@link #dailyRetention()} in whole days, at least 1. */
    public long dailyRetentionDays() {
        return Math.max(1L, (dailyRetention.toHours() + 23) / 24);
    }

    /**
     * The Apdex threshold T: a turn of at most T satisfies, one of at most 4T is
     * tolerated.
     */
    public long apdexTMs() {
        return apdexT.toMillis();
    }

    /**
     * How long the health views cache what they read from the stores; zero switches
     * the cache off.
     */
    public Duration cacheTtl() {
        return cacheTtl;
    }

    /**
     * How long a conversation may stay quiet before its session closes, for every
     * origin but e-mail ({@code eddi.health.session-window}, 24 h). An agent may
     * override it per origin ({@code healthConfig.inactivityWindowHours}).
     */
    public Duration sessionWindow() {
        return sessionWindow;
    }

    /**
     * The same for e-mail conversations ({@code eddi.health.email-session-window},
     * 72 h): a reply to a mail takes longer than one in a chat.
     */
    public Duration emailSessionWindow() {
        return emailSessionWindow;
    }

    /**
     * The outcome coverage below which the effectiveness rates are withheld
     * ({@code eddi.health.min-outcome-coverage}, 0.5): with most sessions ending
     * without any outcome evidence, a rate would describe the few that have one.
     */
    public double minOutcomeCoverage() {
        return minOutcomeCoverage;
    }
}
