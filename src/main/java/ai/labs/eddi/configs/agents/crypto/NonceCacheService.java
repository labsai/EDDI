/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * Nonce-based replay protection for signed envelopes.
 * <p>
 * Three-stage validation:
 * <ol>
 * <li><strong>Freshness:</strong> Reject if {@code timestampMs} is older than
 * {@code maxAgeMs} (default 5 minutes)</li>
 * <li><strong>Clock skew:</strong> Reject if {@code timestampMs} is more than
 * {@code clockSkewMs} into the future (default 30 seconds)</li>
 * <li><strong>Duplicate:</strong> Reject if the nonce was already seen within
 * the TTL window</li>
 * </ol>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class NonceCacheService {

    private static final Logger LOGGER = Logger.getLogger(NonceCacheService.class);
    private static final String CACHE_NAME = "nonce-replay-protection";

    @ConfigProperty(name = "eddi.a2a.signing.nonce.max-age-ms", defaultValue = "300000") // 5 min
    long maxAgeMs;

    @ConfigProperty(name = "eddi.a2a.signing.nonce.clock-skew-ms", defaultValue = "30000") // 30 sec
    long clockSkewMs;

    private final ICacheFactory cacheFactory;
    private final MeterRegistry meterRegistry;
    private ICache<String, Boolean> nonceCache;
    private Counter replayRejections;
    private Counter freshnessRejections;
    private Counter clockSkewRejections;

    @Inject
    public NonceCacheService(ICacheFactory cacheFactory, MeterRegistry meterRegistry) {
        this.cacheFactory = cacheFactory;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        // TTL must cover the full replay window: maxAge + clockSkew + buffer
        Duration ttl = Duration.ofMillis(maxAgeMs + clockSkewMs + 10_000);
        this.nonceCache = cacheFactory.getCache(CACHE_NAME, ttl);

        replayRejections = meterRegistry.counter("eddi.agent.nonce.replay.rejected");
        freshnessRejections = meterRegistry.counter("eddi.agent.nonce.freshness.rejected");
        clockSkewRejections = meterRegistry.counter("eddi.agent.nonce.clockskew.rejected");
    }

    /**
     * Validate a nonce + timestamp combination.
     *
     * @param nonce
     *            the unique nonce from the envelope
     * @param timestampMs
     *            the envelope creation timestamp in epoch milliseconds
     * @return validation result
     */
    public NonceValidation validate(String nonce, long timestampMs) {
        long now = Instant.now().toEpochMilli();

        // 1. Freshness check
        if ((now - timestampMs) > maxAgeMs) {
            freshnessRejections.increment();
            LOGGER.debugf("Nonce '%s' rejected: too old (%d ms age, max %d ms)", nonce, now - timestampMs, maxAgeMs);
            return NonceValidation.TOO_OLD;
        }

        // 2. Clock skew check
        if ((timestampMs - now) > clockSkewMs) {
            clockSkewRejections.increment();
            LOGGER.debugf("Nonce '%s' rejected: future timestamp (%d ms ahead, max skew %d ms)",
                    nonce, timestampMs - now, clockSkewMs);
            return NonceValidation.CLOCK_SKEW;
        }

        // 3. Replay check
        Boolean existing = nonceCache.get(nonce);
        if (existing != null) {
            replayRejections.increment();
            LOGGER.debugf("Nonce '%s' rejected: replay detected", nonce);
            return NonceValidation.REPLAY;
        }

        // Record nonce
        nonceCache.put(nonce, Boolean.TRUE);
        return NonceValidation.VALID;
    }

    /**
     * Nonce validation results.
     */
    public enum NonceValidation {
        /** Nonce is valid and has been recorded */
        VALID,
        /** Timestamp is too old (exceeds maxAge) */
        TOO_OLD,
        /** Timestamp is too far in the future (clock skew) */
        CLOCK_SKEW,
        /** Nonce was already used (replay attempt) */
        REPLAY
    }
}
