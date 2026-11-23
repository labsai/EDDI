/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.modules.llm.impl.ProviderCooldowns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Drive's quota, counted in units rather than requests.
 *
 * <p>
 * Drive allows a user a number of quota units per minute, and calls cost
 * differently: a listing or a metadata read {@value #LIST_UNITS} units, a
 * download or an export {@value #DOWNLOAD_UNITS}. Counting requests either
 * wastes most of the quota or overruns it. Every call is charged here first,
 * against a token bucket per connection that refills at
 * {@code eddi.rag.throttle.googleDrive.units-per-minute}; a call the bucket
 * cannot pay for waits until it can, within the run's deadline.
 *
 * <p>
 * The bucket is node-local. What makes the cluster honest is the shared
 * cooldown ({@link ProviderCooldowns}): a throttle Drive answers on one node
 * stops every node's calls on that connection until the time Drive asked for.
 */
@ApplicationScoped
public class DriveQuota {

    /** What a listing or a metadata read costs. */
    static final int LIST_UNITS = 100;

    /** What a download or an export costs. */
    static final int DOWNLOAD_UNITS = 200;

    /** How long a throttle with no Retry-After is shared for. */
    static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(1);

    private static final String KEY_PREFIX = "googleDrive|";

    private final long unitsPerMinute;
    private final ProviderCooldowns cooldowns;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Inject
    public DriveQuota(
            @ConfigProperty(name = "eddi.rag.throttle.googleDrive.units-per-minute", defaultValue = "250000") long unitsPerMinute,
            ProviderCooldowns cooldowns) {
        this.unitsPerMinute = Math.max(0, unitsPerMinute);
        this.cooldowns = cooldowns;
    }

    /**
     * A quota that shares nothing across nodes, for a connector assembled by hand.
     */
    static DriveQuota local(long unitsPerMinute) {
        return new DriveQuota(unitsPerMinute, null);
    }

    /** No limit: every call may go at once. */
    static DriveQuota unlimited() {
        return local(0);
    }

    /**
     * How long a call costing {@code units} must wait before it may go; zero means
     * it may go now, and its units have been taken.
     */
    Duration reserve(String connection, int units, Instant now) {
        if (unitsPerMinute <= 0) {
            return Duration.ZERO;
        }
        return buckets.computeIfAbsent(key(connection), ignored -> new Bucket(unitsPerMinute, now))
                .reserve(Math.min(units, unitsPerMinute), now);
    }

    /** Until when Drive refuses this connection's calls, as any node last heard. */
    Optional<Instant> cooldownUntil(String connection) {
        return cooldowns == null
                ? Optional.empty()
                : cooldowns.active(key(connection)).map(cooldown -> cooldown.until());
    }

    /** Shares a throttle Drive answered with, until {@code until}. */
    void recordThrottle(String connection, Instant until) {
        if (cooldowns != null) {
            cooldowns.record(key(connection), until, false);
        }
    }

    private static String key(String connection) {
        return KEY_PREFIX + (connection == null ? "default" : connection);
    }

    /** A token bucket of units, refilled continuously. */
    private static final class Bucket {

        private final long capacity;
        private final double perMilli;
        private double tokens;
        private Instant refilledAt;

        private Bucket(long unitsPerMinute, Instant now) {
            this.capacity = unitsPerMinute;
            this.perMilli = unitsPerMinute / 60_000.0;
            this.tokens = unitsPerMinute;
            this.refilledAt = now;
        }

        synchronized Duration reserve(long units, Instant now) {
            long elapsed = Duration.between(refilledAt, now).toMillis();
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * perMilli);
                refilledAt = now;
            }
            if (tokens >= units) {
                tokens -= units;
                return Duration.ZERO;
            }
            return Duration.ofMillis((long) Math.ceil((units - tokens) / perMilli));
        }
    }
}
