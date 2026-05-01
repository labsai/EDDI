/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${vars:...}} references in configuration values at runtime
 * (late-binding). Supports two forms:
 * <ul>
 * <li><b>Short form:</b> {@code ${vars:keyName}} — uses the provided tenant
 * context (defaults to {@code "default"})</li>
 * <li><b>Full form:</b> {@code ${vars:tenantId/keyName}} — explicit tenant</li>
 * </ul>
 * <p>
 * Values are loaded from {@link IGlobalVariableStore} and cached per-tenant via
 * Caffeine with a configurable TTL. Cache invalidation is triggered by
 * {@link #invalidateCache()}, which also notifies downstream listeners (e.g.,
 * {@code ChatModelRegistry}).
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class GlobalVariableResolver {

    private static final Logger LOGGER = Logger.getLogger(GlobalVariableResolver.class);

    /**
     * Dual-format pattern matching both variable reference forms:
     * <ul>
     * <li>{@code ${vars:keyName}} — group(1) is null, group(2) is keyName</li>
     * <li>{@code ${vars:tenantId/keyName}} — group(1) is tenantId, group(2) is
     * keyName</li>
     * </ul>
     */
    static final Pattern VARS_PATTERN = Pattern.compile(
            "\\$\\{vars:(?:([a-zA-Z0-9_.\\-]+)/)?([a-zA-Z0-9_.\\-]+)\\}");

    private final IGlobalVariableStore store;
    private final int cacheTtlMinutes;

    /** Per-tenant variable cache: tenantId → (key → value). */
    private Cache<String, Map<String, String>> cache;

    /**
     * Listeners notified when the variable cache is invalidated. Used by downstream
     * caches (e.g., ChatModelRegistry) to evict stale model instances that were
     * built with old variable values.
     */
    private final List<Runnable> invalidationListeners = new CopyOnWriteArrayList<>();

    @Inject
    public GlobalVariableResolver(IGlobalVariableStore store,
            @ConfigProperty(name = "eddi.variables.cache-ttl-minutes", defaultValue = "2") int cacheTtlMinutes) {
        this.store = store;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    @PostConstruct
    void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
                .maximumSize(32)
                .build();
        LOGGER.infof("GlobalVariableResolver initialized (cache TTL=%dmin)", cacheTtlMinutes);
    }

    /**
     * Quick check whether a string contains any {@code ${vars:...}} reference.
     */
    public static boolean containsReference(String value) {
        return value != null && value.contains("${vars:");
    }

    /**
     * Resolve all {@code ${vars:...}} references using the default tenant.
     *
     * @param value
     *            the value that may contain {@code ${vars:...}} references
     * @return the value with references replaced by stored values
     */
    public String resolveValue(String value) {
        return resolveValue(value, GlobalVariable.DEFAULT_TENANT);
    }

    /**
     * Resolve all {@code ${vars:...}} references in a single string value.
     * <p>
     * For {@code ${vars:key}} (short form), uses the provided {@code tenantId}. For
     * {@code ${vars:explicitTenant/key}} (full form), uses the explicit tenant.
     * <p>
     * Missing variables are left as-is (passthrough) so they can be reported by
     * downstream validation or logged for debugging.
     *
     * @param value
     *            the value that may contain {@code ${vars:...}} references
     * @param tenantId
     *            the tenant context for short-form references
     * @return the value with references replaced by stored values
     */
    public String resolveValue(String value, String tenantId) {
        if (!containsReference(value)) {
            return value;
        }
        if (tenantId == null) {
            tenantId = GlobalVariable.DEFAULT_TENANT;
        }

        Matcher matcher = VARS_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String explicitTenant = matcher.group(1);
            String key = matcher.group(2);

            // Full form: ${vars:tenantId/key} → use explicit tenant
            // Short form: ${vars:key} → use the provided tenantId
            String effectiveTenant = explicitTenant != null ? explicitTenant : tenantId;

            Map<String, String> variables = loadVariables(effectiveTenant);
            String resolved = variables.get(key);
            if (resolved != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
            } else {
                // Leave the original reference — variable not found
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                LOGGER.debugf("Global variable not found: %s/%s — passing through unchanged",
                        effectiveTenant, key);
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolve all {@code ${vars:...}} references in a parameter map using the
     * default tenant. Values not containing references pass through unchanged.
     *
     * @param params
     *            the parameter map
     * @return a new map with references replaced
     */
    public Map<String, String> resolveAll(Map<String, String> params) {
        return resolveAll(params, GlobalVariable.DEFAULT_TENANT);
    }

    /**
     * Resolve all {@code ${vars:...}} references in a parameter map. Values not
     * containing references pass through unchanged.
     *
     * @param params
     *            the parameter map
     * @param tenantId
     *            the tenant context for short-form references
     * @return a new map with references replaced
     */
    public Map<String, String> resolveAll(Map<String, String> params, String tenantId) {
        if (params == null || params.isEmpty()) {
            return params;
        }

        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> resolveValue(value, tenantId));
        return resolved;
    }

    /**
     * Get all global variables for the default tenant as a map suitable for
     * injection into the Jinja2 template data model (the {@code {{vars.<key>}}}
     * namespace).
     *
     * @return a map of key → value (cast to Object for template compatibility)
     */
    public Map<String, Object> getTemplateData() {
        return getTemplateData(GlobalVariable.DEFAULT_TENANT);
    }

    /**
     * Get all global variables for a specific tenant as a map suitable for
     * injection into the template data model.
     *
     * @param tenantId
     *            the tenant to load variables for
     * @return a map of key → value (cast to Object for template compatibility)
     */
    public Map<String, Object> getTemplateData(String tenantId) {
        Map<String, String> variables = loadVariables(tenantId);
        return new HashMap<>(variables);
    }

    /**
     * Invalidate the local cache for all tenants. Called by the REST store on
     * writes. Also notifies all registered invalidation listeners.
     */
    public void invalidateCache() {
        cache.invalidateAll();
        LOGGER.info("Global variable cache invalidated");
        fireInvalidationListeners();
    }

    /**
     * Register a listener that is called whenever global variables change. Used by
     * downstream caches to perform surgical eviction.
     *
     * @param listener
     *            a callback to invoke on invalidation
     */
    public void registerInvalidationListener(Runnable listener) {
        invalidationListeners.add(listener);
    }

    private Map<String, String> loadVariables(String tenantId) {
        return cache.get(tenantId, store::getAll);
    }

    private void fireInvalidationListeners() {
        for (Runnable listener : invalidationListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warnf("Global variable invalidation listener failed: %s", e.getMessage());
            }
        }
    }
}
