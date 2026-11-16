/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Deployment settings of approval analytics, the sampled audit and earned
 * autonomy. Every one has a default; none needs to be set.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightSettings {

    @ConfigProperty(name = "eddi.hitl.analytics.retention", defaultValue = "P400D")
    Duration analyticsRetention;

    @ConfigProperty(name = "eddi.hitl.analytics.initial-backfill", defaultValue = "P90D")
    Duration initialBackfill;

    @ConfigProperty(name = "eddi.hitl.samples.enabled", defaultValue = "true")
    boolean samplesEnabled;

    @ConfigProperty(name = "eddi.hitl.samples.retention", defaultValue = "P180D")
    Duration samplesRetention;

    @ConfigProperty(name = "eddi.hitl.autonomy.maintenance-interval", defaultValue = "PT1H")
    Duration maintenanceInterval;

    @ConfigProperty(name = "eddi.hitl.autonomy.suspension-cache-ttl", defaultValue = "PT15S")
    Duration suspensionCacheTtl;

    @ConfigProperty(name = "eddi.hitl.autonomy.max-agents-per-run", defaultValue = "500")
    int maxAgentsPerRun;

    /** For CDI. */
    public OversightSettings() {
    }

    /** Test seam: explicit values. */
    public OversightSettings(Duration analyticsRetention, Duration initialBackfill, boolean samplesEnabled, Duration samplesRetention,
            Duration maintenanceInterval, Duration suspensionCacheTtl, int maxAgentsPerRun) {
        this.analyticsRetention = analyticsRetention;
        this.initialBackfill = initialBackfill;
        this.samplesEnabled = samplesEnabled;
        this.samplesRetention = samplesRetention;
        this.maintenanceInterval = maintenanceInterval;
        this.suspensionCacheTtl = suspensionCacheTtl;
        this.maxAgentsPerRun = maxAgentsPerRun;
    }

    /** The defaults, for tests. */
    public static OversightSettings defaults() {
        return new OversightSettings(Duration.ofDays(400), Duration.ofDays(90), true, Duration.ofDays(180), Duration.ofHours(1),
                Duration.ofSeconds(15), 500);
    }

    /** How long decision facts are kept. */
    public Duration analyticsRetention() {
        return analyticsRetention;
    }

    /** How far back the first-start rebuild reads the ledger; zero disables it. */
    public Duration initialBackfill() {
        return initialBackfill;
    }

    /** Whether ungated calls are sampled at all (cluster-wide switch). */
    public boolean samplesEnabled() {
        return samplesEnabled;
    }

    /** How long samples are kept. */
    public Duration samplesRetention() {
        return samplesRetention;
    }

    /** How often the maintenance job runs. */
    public Duration maintenanceInterval() {
        return maintenanceInterval;
    }

    /** How long a node trusts its cached list of an agent's suspensions. */
    public Duration suspensionCacheTtl() {
        return suspensionCacheTtl;
    }

    /** How many agents one maintenance run evaluates. */
    public int maxAgentsPerRun() {
        return maxAgentsPerRun;
    }
}
