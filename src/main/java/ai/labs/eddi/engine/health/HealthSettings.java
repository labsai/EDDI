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

    private final boolean enabled;
    private final int queueCapacity;
    private final Duration factsRetention;
    private final Duration orphanSweepWindow;
    private final int orphanSweepMax;

    @Inject
    public HealthSettings(@ConfigProperty(name = "eddi.health.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.health.queue-capacity", defaultValue = "10000") int queueCapacity,
            @ConfigProperty(name = "eddi.health.facts-retention", defaultValue = "90d") Duration factsRetention,
            @ConfigProperty(name = "eddi.health.orphan-sweep-window", defaultValue = "48h") Duration orphanSweepWindow,
            @ConfigProperty(name = "eddi.health.orphan-sweep-max", defaultValue = "50000") int orphanSweepMax) {
        this.enabled = enabled;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.factsRetention = positiveOr(factsRetention, Duration.ofDays(90));
        this.orphanSweepWindow = positiveOr(orphanSweepWindow, DEFAULT_ORPHAN_SWEEP_WINDOW);
        this.orphanSweepMax = Math.max(1, orphanSweepMax);
    }

    /** The orphan-sweep settings at their defaults. */
    HealthSettings(boolean enabled, int queueCapacity, Duration factsRetention) {
        this(enabled, queueCapacity, factsRetention, DEFAULT_ORPHAN_SWEEP_WINDOW, DEFAULT_ORPHAN_SWEEP_MAX);
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
}
