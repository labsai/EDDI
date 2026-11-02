/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;

/**
 * The deployment-wide alert settings ({@code eddi.alerts.*}). Everything a rule
 * author decides lives on the rule; these are the operator's bounds.
 */
@ApplicationScoped
public class AlertSettings {

    private final boolean enabled;
    private final int queueCapacity;
    private final Duration maintenanceInterval;
    private final int maxAttempts;
    private final Duration retryBase;
    private final Duration claimLease;
    private final Duration eventRetention;
    private final Duration webhookTimeout;
    private final Optional<String> managerUrl;

    @Inject
    public AlertSettings(@ConfigProperty(name = "eddi.alerts.enabled", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "eddi.alerts.queue-capacity", defaultValue = "1000") int queueCapacity,
            @ConfigProperty(name = "eddi.alerts.maintenance-interval", defaultValue = "60s") Duration maintenanceInterval,
            @ConfigProperty(name = "eddi.alerts.max-attempts", defaultValue = "5") int maxAttempts,
            @ConfigProperty(name = "eddi.alerts.retry-base", defaultValue = "30s") Duration retryBase,
            @ConfigProperty(name = "eddi.alerts.claim-lease", defaultValue = "2m") Duration claimLease,
            @ConfigProperty(name = "eddi.alerts.event-retention", defaultValue = "90d") Duration eventRetention,
            @ConfigProperty(name = "eddi.alerts.webhook-timeout", defaultValue = "10s") Duration webhookTimeout,
            @ConfigProperty(name = "eddi.alerts.manager-url") Optional<String> managerUrl) {
        this.enabled = enabled;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.maintenanceInterval = maintenanceInterval;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBase = retryBase;
        this.claimLease = claimLease;
        this.eventRetention = eventRetention;
        this.webhookTimeout = clampTimeout(webhookTimeout);
        this.managerUrl = managerUrl.map(String::strip).filter(url -> !url.isEmpty()).map(url -> url.replaceAll("/+$", ""));
    }

    /**
     * A webhook timeout of 1 to 60 seconds: the send claim lease is derived from it
     * (three times), and the erasure's wait for claims is bounded at ten base
     * leases.
     */
    private static Duration clampTimeout(Duration timeout) {
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            return Duration.ofSeconds(1);
        }
        return timeout.compareTo(Duration.ofSeconds(60)) > 0 ? Duration.ofSeconds(60) : timeout;
    }

    /** Defaults, for tests. */
    public static AlertSettings defaults() {
        return new AlertSettings(true, 1000, Duration.ofSeconds(60), 5, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofDays(90),
                Duration.ofSeconds(10), Optional.empty());
    }

    public boolean enabled() {
        return enabled;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public Duration maintenanceInterval() {
        return maintenanceInterval;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration retryBase() {
        return retryBase;
    }

    public Duration claimLease() {
        return claimLease;
    }

    public Duration eventRetention() {
        return eventRetention;
    }

    public Duration webhookTimeout() {
        return webhookTimeout;
    }

    /**
     * The Manager's base URL for links in delivered alerts, without a trailing
     * slash.
     */
    public Optional<String> managerUrl() {
        return managerUrl;
    }

    /**
     * The wait before the next attempt after {@code attempts} failed ones:
     * {@code retryBase × 4^(attempts-1)}, capped at six hours.
     */
    public Duration backoff(int attempts) {
        long factor = 1;
        for (int i = 1; i < attempts && factor < 4096; i++) {
            factor *= 4;
        }
        Duration wait = retryBase.multipliedBy(factor);
        Duration cap = Duration.ofHours(6);
        return wait.compareTo(cap) > 0 ? cap : wait;
    }
}
