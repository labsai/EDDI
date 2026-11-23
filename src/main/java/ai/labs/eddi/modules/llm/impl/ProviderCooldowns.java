/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore.Cooldown;
import ai.labs.eddi.utils.LogSanitizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads and writes the shared cooldown rows ({@link IProviderCooldownStore})
 * for the callers that wait on a provider's quota — the embedding throttle and
 * the Google Drive connector.
 *
 * <p>
 * A read is cached for a few seconds per quota, so a drain asking before every
 * call costs the database one round trip per quota every few seconds, not one
 * per call. A refusal this node records updates its own cache at once.
 *
 * <p>
 * Never fails its caller. A store that cannot answer is read as "no cooldown":
 * the provider then refuses the call itself, which is what happened before the
 * rows existed. A write that does not land only means other nodes learn the
 * throttle from the provider too.
 */
@ApplicationScoped
public class ProviderCooldowns {

    private static final Logger LOGGER = Logger.getLogger(ProviderCooldowns.class);

    /** How long a read is trusted. */
    static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final IProviderCooldownStore store;
    private final Supplier<Instant> clock;
    /**
     * Bounded, and entries leave a minute after they were written: a deployment
     * with many knowledge bases on many keys must not hold every key it ever saw.
     * The 5 s trust in {@link #CACHE_TTL} is judged on the injected clock.
     */
    private final Cache<String, Cached> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_QUOTAS)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    /** The most quotas whose cooldown is held in memory. */
    static final int MAX_CACHED_QUOTAS = 10_000;

    private record Cached(Cooldown cooldown, Instant readAt) {
    }

    @Inject
    public ProviderCooldowns(IProviderCooldownStore store) {
        this(store, Instant::now);
    }

    public ProviderCooldowns(IProviderCooldownStore store, Supplier<Instant> clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The quota's cooldown while it is still running; empty once it has passed. */
    public Optional<Cooldown> active(String throttleKey) {
        if (throttleKey == null) {
            return Optional.empty();
        }
        Instant now = clock.get();
        Cached cached = cache.getIfPresent(throttleKey);
        Cooldown cooldown;
        if (cached != null && cached.readAt().plus(CACHE_TTL).isAfter(now)) {
            cooldown = cached.cooldown();
        } else {
            try {
                cooldown = store.read(throttleKey).orElse(null);
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "Could not read the provider cooldown %s", LogSanitizer.sanitize(throttleKey));
                cooldown = cached == null ? null : cached.cooldown();
            }
            cache.put(throttleKey, new Cached(cooldown, now));
        }
        return cooldown != null && cooldown.until().isAfter(now) ? Optional.of(cooldown) : Optional.empty();
    }

    /** Records that the quota refuses calls until {@code until}. */
    public void record(String throttleKey, Instant until, boolean daily) {
        if (throttleKey == null || until == null) {
            return;
        }
        Cached cached = cache.getIfPresent(throttleKey);
        if (cached == null || cached.cooldown() == null || until.isAfter(cached.cooldown().until())) {
            cache.put(throttleKey, new Cached(new Cooldown(throttleKey, until, daily), clock.get()));
        }
        try {
            store.extend(throttleKey, until, daily);
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Could not record the provider cooldown %s", LogSanitizer.sanitize(throttleKey));
        }
    }
}
