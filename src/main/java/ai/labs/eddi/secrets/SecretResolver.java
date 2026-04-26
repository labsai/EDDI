/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves vault references in parameter maps and strings. Supports both the
 * <b>short form</b> ({@code ${eddivault:keyName}}) and <b>full form</b>
 * ({@code ${eddivault:tenantId/keyName}}).
 * <p>
 * This is the central integration point between the vault and the execution
 * workflow. It is called <b>after</b> Qute template processing and
 * <b>before</b> the final API call (late-binding resolution).
 * <p>
 * <b>Access model:</b> Access control is via configuration authorship — the
 * admin who writes the agent config decides which vault references to include.
 * The resolver does NOT check agent permissions; it resolves any valid
 * reference that exists in the vault.
 * <p>
 * Includes a Caffeine cache with configurable TTL to avoid repeated
 * decryption/vault calls. Cache is invalidated on secret rotation via
 * {@link #invalidateCache(SecretReference)}. Failed resolutions (not-found,
 * provider errors) are <b>never cached</b> so that newly created secrets
 * resolve immediately.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class SecretResolver {

    private static final Logger LOGGER = Logger.getLogger(SecretResolver.class);
    private static final Pattern VAULT_PATTERN = SecretReference.compiledPattern();

    private final ISecretProvider secretProvider;
    private final MeterRegistry meterRegistry;
    private final int cacheTtlMinutes;
    private final int cacheMaxSize;

    private Cache<String, String> cache;

    /**
     * Listeners notified when cached secrets are invalidated. Used by downstream
     * caches (e.g., ChatModelRegistry) to evict stale model instances that were
     * built with old API keys. Receives the specific {@link SecretReference} that
     * changed, or {@code null} when all secrets are invalidated (e.g., DEK/KEK
     * rotation). Thread-safe via CopyOnWriteArrayList.
     */
    private final List<Consumer<SecretReference>> invalidationListeners = new CopyOnWriteArrayList<>();

    // ─── Metrics ───
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter resolveErrorCounter;
    private Timer resolveTimer;

    @Inject
    public SecretResolver(ISecretProvider secretProvider, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.vault.cache-ttl-minutes", defaultValue = "5") int cacheTtlMinutes,
            @ConfigProperty(name = "eddi.vault.cache-max-size", defaultValue = "1000") int cacheMaxSize) {
        this.secretProvider = secretProvider;
        this.meterRegistry = meterRegistry;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.cacheMaxSize = cacheMaxSize;
    }

    @PostConstruct
    void init() {
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes)).maximumSize(cacheMaxSize).build();

        this.cacheHitCounter = meterRegistry.counter("eddi.vault.cache.hits");
        this.cacheMissCounter = meterRegistry.counter("eddi.vault.cache.misses");
        this.resolveErrorCounter = meterRegistry.counter("eddi.vault.resolve.errors");
        this.resolveTimer = meterRegistry.timer("eddi.vault.resolve.time");

        if (secretProvider.isAvailable()) {
            LOGGER.infof("SecretResolver initialized (cache TTL=%dmin, maxSize=%d)", cacheTtlMinutes, cacheMaxSize);
        } else {
            LOGGER.info("SecretResolver initialized in passthrough mode (vault not configured)");
        }
    }

    /**
     * Resolve all vault references in a parameter map. Values not containing vault
     * references pass through unchanged.
     * <p>
     * If the vault is not available, all values pass through unchanged (backward
     * compatibility with plaintext configs).
     *
     * @param params
     *            the parameter map (may contain ${eddivault:...} values)
     * @return a new map with vault references replaced by plaintext values
     */
    public Map<String, String> resolveSecrets(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }

        if (!secretProvider.isAvailable()) {
            return params; // Passthrough — vault not configured
        }

        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> resolveValue(value));
        return resolved;
    }

    /**
     * Resolve vault references in a single string value. Supports both the short
     * form ({@code ${eddivault:keyName}}) and full form
     * ({@code ${eddivault:tenantId/keyName}}), and multiple references within the
     * same string.
     * <p>
     * If the vault is not available, the value passes through unchanged.
     * <p>
     * <b>Caching strategy:</b> Only successful resolutions are cached. Failed
     * resolutions (secret not found or provider error) are <b>never cached</b>,
     * ensuring that newly created secrets resolve immediately without waiting for
     * cache expiry.
     *
     * @param value
     *            the value that may contain ${eddivault:...} references
     * @return the value with vault references replaced by plaintext
     */
    public String resolveValue(String value) {
        if (value == null || !SecretReference.isVaultReference(value)) {
            return value;
        }

        if (!secretProvider.isAvailable()) {
            return value; // Passthrough
        }

        Matcher matcher = VAULT_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String fullMatch = matcher.group(0);

            // Parse using SecretReference — handles both short and full form
            SecretReference ref = SecretReference.parse(fullMatch);
            String cacheKey = ref.tenantId() + "/" + ref.keyName();

            // Check cache first — only successful resolutions are cached
            String resolved = cache.getIfPresent(cacheKey);
            if (resolved != null) {
                cacheHitCounter.increment();
            } else {
                cacheMissCounter.increment();
                resolved = resolveFromProvider(ref, fullMatch);
                if (resolved != null) {
                    // Only cache successful resolutions — never cache failures
                    cache.put(cacheKey, resolved);
                }
            }

            if (resolved != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
            } else {
                // Leave the original reference in place if resolution failed
                matcher.appendReplacement(result, Matcher.quoteReplacement(fullMatch));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolve a single secret reference from the provider, with timing and error
     * handling.
     *
     * @return the plaintext value, or null if resolution failed
     */
    private String resolveFromProvider(SecretReference ref, String fullMatch) {
        return resolveTimer.record(() -> {
            try {
                return secretProvider.resolve(ref);
            } catch (ISecretProvider.SecretNotFoundException e) {
                LOGGER.warnf("Vault reference not found: %s — passing through unchanged", fullMatch);
                resolveErrorCounter.increment();
                return null;
            } catch (ISecretProvider.SecretProviderException e) {
                LOGGER.errorf("Failed to resolve vault reference: %s — %s", fullMatch, e.getMessage());
                resolveErrorCounter.increment();
                return null;
            }
        });
    }

    /**
     * Invalidate a specific cache entry (called on secret rotation).
     */
    public void invalidateCache(SecretReference reference) {
        String cacheKey = reference.tenantId() + "/" + reference.keyName();
        cache.invalidate(cacheKey);
        LOGGER.infof("Cache invalidated for: %s", cacheKey);
        fireInvalidationListeners(reference);
    }

    /**
     * Invalidate all cached secrets (e.g., on master key rotation).
     */
    public void invalidateAll() {
        cache.invalidateAll();
        LOGGER.info("All cached secrets invalidated");
        fireInvalidationListeners(null);
    }

    /**
     * Register a listener that is called whenever cached secrets are invalidated.
     * The listener receives the specific {@link SecretReference} that changed, or
     * {@code null} when all secrets are being invalidated. This allows downstream
     * caches to perform surgical eviction.
     *
     * @param listener
     *            a callback that receives the affected SecretReference (or null for
     *            all)
     */
    public void registerInvalidationListener(Consumer<SecretReference> listener) {
        invalidationListeners.add(listener);
    }

    private void fireInvalidationListeners(SecretReference reference) {
        for (Consumer<SecretReference> listener : invalidationListeners) {
            try {
                listener.accept(reference);
            } catch (Exception e) {
                LOGGER.warnf("Invalidation listener failed: %s", e.getMessage());
            }
        }
    }
}
