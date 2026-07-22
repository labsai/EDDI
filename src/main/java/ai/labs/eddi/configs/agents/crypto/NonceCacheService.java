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

    /**
     * Head-room added on top of {@code maxAge + clockSkew} so that a nonce can
     * never be forgotten while a replay carrying it would still pass the freshness
     * and clock-skew checks.
     */
    private static final long TTL_BUFFER_MS = 60_000L;

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
        // A nonce only has to be remembered for as long as a replay of it could
        // still get past the checks above: maxAge covers a back-dated timestamp,
        // clockSkew a post-dated one. Anything older is rejected on freshness
        // before the cache is consulted. With the defaults that is 300s + 30s +
        // 60s buffer = 390s.
        Duration nonceTtl = Duration.ofMillis(maxAgeMs + clockSkewMs + TTL_BUFFER_MS);
        this.nonceCache = cacheFactory.getCache(CACHE_NAME, nonceTtl);

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
        // Reject null/blank nonces immediately
        if (nonce == null || nonce.isBlank()) {
            LOGGER.warn("Nonce validation failed: nonce is null or blank");
            return NonceValidation.REPLAY; // Treat as invalid — same effect as replay
        }

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

        // 3. Atomic replay check — putIfAbsent returns null on successful insertion,
        // existing value if already present. This eliminates the TOCTOU race between
        // get() and put() that could allow two concurrent requests with the same nonce
        // to both pass the replay check.
        Boolean existing = nonceCache.putIfAbsent(nonce, Boolean.TRUE);
        if (existing != null) {
            replayRejections.increment();
            LOGGER.debugf("Nonce '%s' rejected: replay detected", nonce);
            return NonceValidation.REPLAY;
        }

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
