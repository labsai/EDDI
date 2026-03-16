package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${eddivault:tenantId/botId/keyName}} references in parameter maps.
 * <p>
 * This is the central integration point between the vault and the execution pipeline.
 * It is called <b>after</b> Thymeleaf template processing and <b>before</b> the final
 * API call (late-binding resolution).
 * <p>
 * Includes a Caffeine cache with configurable TTL to avoid repeated decryption/vault calls.
 * Cache is invalidated on secret rotation via {@link #invalidateCache(SecretReference)}.
 */
@ApplicationScoped
public class SecretResolver {

    private static final Logger LOGGER = Logger.getLogger(SecretResolver.class);
    private static final Pattern VAULT_PATTERN = Pattern.compile(SecretReference.VAULT_PATTERN);

    private final ISecretProvider secretProvider;
    private final int cacheTtlMinutes;
    private final int cacheMaxSize;

    private Cache<String, String> cache;

    @Inject
    public SecretResolver(
            ISecretProvider secretProvider,
            @ConfigProperty(name = "eddi.vault.cache-ttl-minutes", defaultValue = "5") int cacheTtlMinutes,
            @ConfigProperty(name = "eddi.vault.cache-max-size", defaultValue = "1000") int cacheMaxSize) {
        this.secretProvider = secretProvider;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.cacheMaxSize = cacheMaxSize;
    }

    @PostConstruct
    void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .maximumSize(cacheMaxSize)
                .build();

        if (secretProvider.isAvailable()) {
            LOGGER.infov("SecretResolver initialized (cache TTL={0}min, maxSize={1})",
                    cacheTtlMinutes, cacheMaxSize);
        } else {
            LOGGER.info("SecretResolver initialized in passthrough mode (vault not configured)");
        }
    }

    /**
     * Resolve all vault references in a parameter map.
     * Values not containing vault references pass through unchanged.
     * <p>
     * If the vault is not available, all values pass through unchanged
     * (backward compatibility with plaintext configs).
     *
     * @param params the parameter map (may contain ${eddivault:...} values)
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
     * Resolve vault references in a single string value.
     * Supports multiple vault references within the same string.
     * <p>
     * If the vault is not available, the value passes through unchanged.
     *
     * @param value the value that may contain ${eddivault:...} references
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
            String tenantId = matcher.group(1);
            String botId = matcher.group(2);
            String keyName = matcher.group(3);

            String cacheKey = tenantId + "/" + botId + "/" + keyName;
            String resolved = cache.get(cacheKey, k -> {
                try {
                    return secretProvider.resolve(new SecretReference(tenantId, botId, keyName));
                } catch (ISecretProvider.SecretNotFoundException e) {
                    LOGGER.warnv("Vault reference not found: {0} — passing through unchanged", fullMatch);
                    return null;
                } catch (ISecretProvider.SecretProviderException e) {
                    LOGGER.errorv("Failed to resolve vault reference: {0} — {1}", fullMatch, e.getMessage());
                    return null;
                }
            });

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
     * Invalidate a specific cache entry (called on secret rotation).
     */
    public void invalidateCache(SecretReference reference) {
        String cacheKey = reference.tenantId() + "/" + reference.botId() + "/" + reference.keyName();
        cache.invalidate(cacheKey);
        LOGGER.infov("Cache invalidated for: {0}", cacheKey);
    }

    /**
     * Invalidate all cached secrets (e.g., on master key rotation).
     */
    public void invalidateAll() {
        cache.invalidateAll();
        LOGGER.info("All cached secrets invalidated");
    }
}
