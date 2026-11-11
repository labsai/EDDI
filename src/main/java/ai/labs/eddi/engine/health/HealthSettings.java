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

    private final boolean enabled;
    private final int queueCapacity;
    private final Duration factsRetention;

    @Inject
    public HealthSettings(@ConfigProperty(name = "eddi.health.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.health.queue-capacity", defaultValue = "10000") int queueCapacity,
            @ConfigProperty(name = "eddi.health.facts-retention", defaultValue = "90d") Duration factsRetention) {
        this.enabled = enabled;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.factsRetention = factsRetention == null || factsRetention.isNegative() || factsRetention.isZero()
                ? Duration.ofDays(90)
                : factsRetention;
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
}
