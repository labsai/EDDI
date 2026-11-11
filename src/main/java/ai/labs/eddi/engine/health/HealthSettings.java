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

    /** Default {@code eddi.health.rollup-interval}. */
    static final Duration DEFAULT_ROLLUP_INTERVAL = Duration.ofMinutes(5);
    /** Default {@code eddi.health.daily-retention}. */
    static final Duration DEFAULT_DAILY_RETENTION = Duration.ofDays(730);

    private final boolean enabled;
    private final int queueCapacity;
    private final Duration factsRetention;
    private final Duration rollupInterval;
    private final Duration dailyRetention;
    private final Duration apdexT;

    @Inject
    public HealthSettings(@ConfigProperty(name = "eddi.health.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.health.queue-capacity", defaultValue = "10000") int queueCapacity,
            @ConfigProperty(name = "eddi.health.facts-retention", defaultValue = "90d") Duration factsRetention,
            @ConfigProperty(name = "eddi.health.rollup-interval", defaultValue = "5m") Duration rollupInterval,
            @ConfigProperty(name = "eddi.health.daily-retention", defaultValue = "730d") Duration dailyRetention,
            @ConfigProperty(name = "eddi.health.apdex-t", defaultValue = "4s") Duration apdexT) {
        this.enabled = enabled;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.factsRetention = positiveOr(factsRetention, Duration.ofDays(90));
        this.rollupInterval = positiveOr(rollupInterval, DEFAULT_ROLLUP_INTERVAL);
        this.dailyRetention = positiveOr(dailyRetention, DEFAULT_DAILY_RETENTION);
        this.apdexT = positiveOr(apdexT, Duration.ofMillis(HealthTurnAggregator.DEFAULT_APDEX_T_MS));
    }

    /** Collection settings with the H2b defaults for the rest — for tests. */
    public HealthSettings(boolean enabled, int queueCapacity, Duration factsRetention) {
        this(enabled, queueCapacity, factsRetention, DEFAULT_ROLLUP_INTERVAL, DEFAULT_DAILY_RETENTION,
                Duration.ofMillis(HealthTurnAggregator.DEFAULT_APDEX_T_MS));
    }

    /** Defaults, for tests. */
    public static HealthSettings defaults() {
        return new HealthSettings(true, DEFAULT_QUEUE_CAPACITY, Duration.ofDays(90));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    /**
     * How often the rollup job turns dirty days into daily rows — also how far
     * today's numbers may lag. Never less than 15 seconds.
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
}
