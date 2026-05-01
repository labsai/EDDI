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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves global variable references in parameter maps and strings.
 * <p>
 * Supports the syntax {@code ${vars:keyName}} where {@code keyName} matches
 * {@code [a-zA-Z0-9_.\-]+}. Multiple references in a single string are
 * resolved.
 * <p>
 * This resolver operates at the <b>late-binding layer</b> — after Qute/Jinja2
 * template processing and <b>before</b> vault secret resolution. Resolution
 * order in the pipeline:
 * <ol>
 * <li>Jinja2 templates ({@code {{vars.x}}}, {@code {{snippets.x}}}, etc.)</li>
 * <li><b>Global Variable resolution</b> ({@code ${vars:x}}) — this class</li>
 * <li>Vault secret resolution ({@code ${vault:x}})</li>
 * </ol>
 * <p>
 * Global variables are also available in the template layer as
 * {@code {{vars.<key>}}} via {@link #getTemplateData()}, which is injected into
 * the template data model by {@code LlmTask}.
 * <p>
 * Includes a Caffeine cache with configurable TTL. The cache is invalidated on
 * writes via the REST API. Downstream caches (e.g., ChatModelRegistry) can
 * register invalidation listeners to evict stale model instances.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class GlobalVariableResolver {

    private static final Logger LOGGER = Logger.getLogger(GlobalVariableResolver.class);

    /**
     * Pattern matching {@code ${vars:keyName}} references.
     */
    static final Pattern VARS_PATTERN = Pattern.compile("\\$\\{vars:([a-zA-Z0-9_.\\-]+)\\}");

    private final IGlobalVariableStore store;
    private final int cacheTtlMinutes;

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
                .maximumSize(1)
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
     * Resolve all {@code ${vars:...}} references in a single string value.
     * <p>
     * Missing variables are left as-is (passthrough) so they can be reported by
     * downstream validation or logged for debugging.
     *
     * @param value
     *            the value that may contain {@code ${vars:...}} references
     * @return the value with references replaced by stored values
     */
    public String resolveValue(String value) {
        if (!containsReference(value)) {
            return value;
        }

        Map<String, String> variables = loadVariables();
        Matcher matcher = VARS_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String resolved = variables.get(key);
            if (resolved != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
            } else {
                // Leave the original reference — variable not found
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
                LOGGER.debugf("Global variable not found: %s — passing through unchanged", key);
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolve all {@code ${vars:...}} references in a parameter map. Values not
     * containing references pass through unchanged.
     *
     * @param params
     *            the parameter map
     * @return a new map with references replaced
     */
    public Map<String, String> resolveAll(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }

        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> resolveValue(value));
        return resolved;
    }

    /**
     * Get all global variables as a map suitable for injection into the Jinja2
     * template data model (the {@code {{vars.<key>}}} namespace).
     *
     * @return a map of key → value (cast to Object for template compatibility)
     */
    public Map<String, Object> getTemplateData() {
        Map<String, String> variables = loadVariables();
        return new HashMap<>(variables);
    }

    /**
     * Invalidate the local cache. Called by the REST store on writes. Also notifies
     * all registered invalidation listeners.
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

    private Map<String, String> loadVariables() {
        return cache.get("all", key -> store.getAll());
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
