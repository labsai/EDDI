/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.model.Deployment.Environment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Runtime configuration for the OpenAI-compatible adapter
 * ({@code eddi.openai-compat.*}).
 * <p>
 * The adapter is <b>disabled by default</b>: enabling it exposes a new
 * conversation surface, and a surface that appears by upgrading is a surface
 * nobody reviewed.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class OpenAiCompatConfig {

    /** {@code http-policy} value meaning "the adapter authenticates itself". */
    public static final String POLICY_PERMIT = "permit";

    /**
     * {@code http-policy} value meaning "Quarkus OIDC authenticates the caller".
     */
    public static final String POLICY_AUTHENTICATED = "authenticated";

    private final boolean enabled;
    private final String apiKey;
    private final String httpPolicy;
    private final boolean trustUserHeaders;
    private final boolean allowAnonymous;
    private final String defaultUser;
    private final Environment environment;
    private final int requestTimeoutSeconds;
    private final int maxConcurrentRequests;
    private final int modelCacheSeconds;
    private final boolean exposeStatelessVariants;

    @Inject
    @SuppressWarnings("java:S107") // configuration carrier — one parameter per knob is the point
    public OpenAiCompatConfig(
            @ConfigProperty(name = "eddi.openai-compat.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "eddi.openai-compat.api-key") Optional<String> apiKey,
            @ConfigProperty(name = "eddi.openai-compat.http-policy", defaultValue = POLICY_PERMIT) String httpPolicy,
            @ConfigProperty(name = "eddi.openai-compat.trust-user-headers", defaultValue = "true") boolean trustUserHeaders,
            @ConfigProperty(name = "eddi.openai-compat.allow-anonymous", defaultValue = "false") boolean allowAnonymous,
            @ConfigProperty(name = "eddi.openai-compat.default-user", defaultValue = "openai-anonymous") String defaultUser,
            @ConfigProperty(name = "eddi.openai-compat.environment", defaultValue = "production") Environment environment,
            @ConfigProperty(name = "eddi.openai-compat.request-timeout-seconds", defaultValue = "120") int requestTimeoutSeconds,
            @ConfigProperty(name = "eddi.openai-compat.max-concurrent-requests", defaultValue = "64") int maxConcurrentRequests,
            @ConfigProperty(name = "eddi.openai-compat.model-cache-seconds", defaultValue = "30") int modelCacheSeconds,
            @ConfigProperty(name = "eddi.openai-compat.expose-stateless-variants", defaultValue = "true") boolean exposeStatelessVariants) {

        this.enabled = enabled;
        this.apiKey = apiKey.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        this.httpPolicy = httpPolicy;
        this.trustUserHeaders = trustUserHeaders;
        this.allowAnonymous = allowAnonymous;
        this.defaultUser = defaultUser;
        this.environment = environment;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.modelCacheSeconds = modelCacheSeconds;
        this.exposeStatelessVariants = exposeStatelessVariants;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The shared bearer secret, or {@code null} when none is configured. */
    public String getApiKey() {
        return apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    public String getHttpPolicy() {
        return httpPolicy;
    }

    /** Whether Quarkus OIDC — rather than the adapter — authenticates callers. */
    public boolean isOidcMode() {
        return POLICY_AUTHENTICATED.equalsIgnoreCase(httpPolicy);
    }

    /**
     * Whether {@code X-OpenWebUI-User-Id} may be believed. Only safe when the
     * caller already proved possession of the API key — a leaked key otherwise
     * permits impersonating any user.
     */
    public boolean isTrustUserHeaders() {
        return trustUserHeaders;
    }

    /**
     * Whether callers with no resolvable identity are served. When true, all such
     * callers share one conversation per (agent, chat) — leave false in any
     * multi-user deployment.
     */
    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public String getDefaultUser() {
        return defaultUser;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public int getModelCacheSeconds() {
        return modelCacheSeconds;
    }

    public boolean isExposeStatelessVariants() {
        return exposeStatelessVariants;
    }
}
