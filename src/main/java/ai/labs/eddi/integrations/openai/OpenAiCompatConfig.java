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

    /**
     * {@code chat-key-fallback} value: a request with no explicit chat key is keyed
     * by a hash of its opening messages, so each client-side chat gets its own
     * conversation. The default.
     */
    public static final String CHAT_KEY_FALLBACK_HISTORY = "history";

    /**
     * {@code chat-key-fallback} value: a request with no explicit chat key shares
     * one conversation per user and agent — the behaviour of earlier releases.
     */
    public static final String CHAT_KEY_FALLBACK_SHARED = "shared";

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
    private final boolean adoptLegacyHeaderMappings;
    private final String chatKeyFallback;
    private final int maxConversationSteps;

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
            @ConfigProperty(name = "eddi.openai-compat.expose-stateless-variants", defaultValue = "true") boolean exposeStatelessVariants,
            @ConfigProperty(name = "eddi.openai-compat.adopt-legacy-header-mappings", defaultValue = "false") boolean adoptLegacyHeaderMappings,
            @ConfigProperty(name = "eddi.openai-compat.chat-key-fallback", defaultValue = CHAT_KEY_FALLBACK_HISTORY) String chatKeyFallback,
            @ConfigProperty(name = "eddi.openai-compat.max-conversation-steps", defaultValue = "0") int maxConversationSteps) {

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
        this.adoptLegacyHeaderMappings = adoptLegacyHeaderMappings;
        this.chatKeyFallback = CHAT_KEY_FALLBACK_SHARED.equalsIgnoreCase(chatKeyFallback == null ? "" : chatKeyFallback.trim())
                ? CHAT_KEY_FALLBACK_SHARED
                : CHAT_KEY_FALLBACK_HISTORY;
        this.maxConversationSteps = maxConversationSteps;
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

    /**
     * Whether an Open WebUI chat mapped under the raw, pre-namespacing header id is
     * adopted by the namespaced {@code openwebui:<id>} caller. Off by default: a
     * raw mapping does not record whether the header or an OIDC principal (from a
     * period with {@code http-policy=authenticated}) created it, so adopting it
     * could hand an OIDC user's conversation to a shared-key caller who names that
     * principal. Enable only when {@code /v1} has never run in OIDC mode.
     * <p>
     * An adopted conversation keeps its raw-id owner, so GDPR export and erasure
     * for {@code openwebui:<id>} do not reach it; address the raw {@code <id>} as
     * well while such conversations exist.
     */
    public boolean isAdoptLegacyHeaderMappings() {
        return adoptLegacyHeaderMappings;
    }

    /**
     * How a request that names no chat ({@code X-OpenWebUI-Chat-Id},
     * {@code X-EDDI-Chat-Id}, {@code metadata.chat_id} or {@code user}) is mapped
     * to a conversation: {@link #CHAT_KEY_FALLBACK_HISTORY} (the default) keys it
     * by its opening messages, {@link #CHAT_KEY_FALLBACK_SHARED} keeps the old
     * single conversation per user and agent. An unknown value falls back to the
     * default.
     */
    public String getChatKeyFallback() {
        return chatKeyFallback;
    }

    /** Whether unkeyed requests are keyed by their message history. */
    public boolean isDeriveChatKeyFromHistory() {
        return CHAT_KEY_FALLBACK_HISTORY.equals(chatKeyFallback);
    }

    /**
     * Steps after which an idle mapped conversation is ended and the chat moves to
     * a fresh one, so one long chat cannot grow a conversation document without
     * bound. {@code 0} (the default) disables the rollover.
     */
    public int getMaxConversationSteps() {
        return maxConversationSteps;
    }
}
