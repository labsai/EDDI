/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ChatModel creation, caching, and lookup by type and parameters.
 * <p>
 * Models are cached by (type, parameters) tuple so that identical configs reuse
 * the same model instance. Thread-safe via ConcurrentHashMap.
 * <p>
 * Supports both synchronous ({@link ChatModel}) and streaming
 * ({@link StreamingChatModel}) models with separate caches.
 */
@ApplicationScoped
public class ChatModelRegistry {
    private static final String KEY_INCLUDE_FIRST_AGENT_MESSAGE = "includeFirstAgentMessage";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    private static final Logger LOGGER = Logger.getLogger(ChatModelRegistry.class);

    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;
    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final Map<ModelCacheKey, ChatModel> modelCache = new ConcurrentHashMap<>(1);
    private final Map<ModelCacheKey, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>(1);

    @Inject
    ChatModelRegistry(Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders,
            GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
    }

    /**
     * Register for write-through cache invalidation: when vault secrets are written
     * or rotated, evict affected model instances so they are rebuilt with the new
     * API key on next use. Surgical: only models whose parameters contain the
     * changed vault reference are evicted.
     */
    @PostConstruct
    void registerSecretInvalidation() {
        secretResolver.registerInvalidationListener(this::invalidateForSecret);
        globalVariableResolver.registerInvalidationListener(() -> {
            int total = modelCache.size() + streamingModelCache.size();
            modelCache.clear();
            streamingModelCache.clear();
            if (total > 0) {
                LOGGER.infof("Invalidated all %d model(s) due to global variable change", total);
            }
        });
        LOGGER.info("ChatModelRegistry registered for secret and global variable invalidation events");
    }

    /**
     * Get or create a ChatModel for the given type and processed parameters.
     * Parameters are filtered to remove non-model keys before cache lookup.
     * <p>
     * The filtered map is used both as the cache key <em>and</em> as the builder
     * input, so anything that shapes the constructed model necessarily shapes its
     * identity. {@code timeout}, {@code logRequests} and {@code logResponses} are
     * part of that map: they are consumed by the provider builders and by
     * {@link ObservableChatModel}, so two tasks differing only in those settings
     * must get two different model instances.
     */
    ChatModel getOrCreate(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        var timeoutMs = processedParams.get(KEY_TIMEOUT);
        var logReq = processedParams.get(KEY_LOG_REQUESTS);
        var logResp = processedParams.get(KEY_LOG_RESPONSES);

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        if (modelCache.containsKey(cacheKey)) {
            return modelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        // Resolve global variable references, then vault secrets (late-binding:
        // after Qute, before builder.build())
        var resolvedParams = globalVariableResolver.resolveAll(filteredParams);
        resolvedParams = secretResolver.resolveSecrets(resolvedParams);
        var rawModel = languageModelApiConnectorBuilders.get(type).get().build(resolvedParams);
        var model = ObservableChatModel.wrapIfNeeded(rawModel, type, timeoutMs, logReq, logResp);
        modelCache.put(cacheKey, model);

        return model;
    }

    /**
     * Get or create a StreamingChatModel for the given type and parameters. Returns
     * {@code null} if the builder does not support streaming.
     * <p>
     * As on the sync path, {@code timeout}/{@code logRequests}/{@code logResponses}
     * reach the streaming builder and are part of the cache key. {@code timeout}
     * becomes the provider's streaming HTTP request/read timeout; the logging flags
     * additionally wrap the model in {@link ObservableStreamingChatModel} so they
     * are honoured uniformly, including for providers whose streaming builder has
     * no logging switch of its own.
     */
    StreamingChatModel getOrCreateStreaming(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        var logReq = processedParams.get(KEY_LOG_REQUESTS);
        var logResp = processedParams.get(KEY_LOG_RESPONSES);

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        if (streamingModelCache.containsKey(cacheKey)) {
            return streamingModelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        try {
            // Resolve global variable references, then vault secrets (late-binding:
            // after Qute, before builder.build())
            var resolvedParams = globalVariableResolver.resolveAll(filteredParams);
            resolvedParams = secretResolver.resolveSecrets(resolvedParams);
            var rawModel = languageModelApiConnectorBuilders.get(type).get().buildStreaming(resolvedParams);
            var model = ObservableStreamingChatModel.wrapIfNeeded(rawModel, type, logReq, logResp);
            streamingModelCache.put(cacheKey, model);
            return model;
        } catch (UnsupportedOperationException e) {
            LOGGER.debugf("Streaming not supported for type '%s', falling back to sync", type);
            return null;
        }
    }

    /**
     * Remove all props that are not directly configuring the langchain builders
     * (for better caching).
     * <p>
     * Only keys the builders never read may be removed here. {@code timeout},
     * {@code logRequests} and {@code logResponses} are <em>not</em> among them:
     * every provider builder reads them, and stripping them both made those builder
     * branches unreachable and — worse — dropped them from the cache key, so a task
     * configured with a {@code timeout} silently received a timeout-free model
     * whenever a task with otherwise identical parameters happened to be built
     * first. Cache key and builder input must stay the same map.
     */
    private Map<String, String> filterParams(Map<String, String> processedParams) {
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_AGENT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
        return returnMap;
    }

    /**
     * Evict cached model instances affected by a secret change.
     * <p>
     * When {@code reference} is non-null (single secret changed), scan cache
     * entries and evict only those whose parameter values contain a vault reference
     * to the changed secret — either short form ({@code ${vault:keyName}}) or full
     * form ({@code ${vault:tenantId/keyName}}). Also matches the legacy
     * {@code ${eddivault:...}} prefix for backward compatibility.
     * <p>
     * When {@code reference} is null (DEK/KEK rotation — all secrets affected),
     * clear everything.
     *
     * @param reference
     *            the changed secret, or null for full invalidation
     */
    void invalidateForSecret(SecretReference reference) {
        if (reference == null) {
            // Full invalidation (DEK/KEK rotation)
            int total = modelCache.size() + streamingModelCache.size();
            modelCache.clear();
            streamingModelCache.clear();
            if (total > 0) {
                LOGGER.infof("Invalidated all %d model(s) due to bulk secret rotation", total);
            }
            return;
        }

        // Build the vault reference forms to search for in cached parameters.
        // New canonical form: ${vault:tenantId/keyName} — always valid
        String fullRef = "${vault:" + reference.tenantId() + "/" + reference.keyName() + "}";
        // Legacy form: ${eddivault:tenantId/keyName} — for backward compat
        String legacyFullRef = "${eddivault:" + reference.tenantId() + "/" + reference.keyName() + "}";
        // Short form: ${vault:keyName} — only valid for the default tenant.
        // The short form always resolves to "default", so a non-default tenant's
        // secret must NOT match short-form references (that would be a false positive).
        String shortRef = SecretReference.DEFAULT_TENANT.equals(reference.tenantId())
                ? "${vault:" + reference.keyName() + "}"
                : null;
        String legacyShortRef = SecretReference.DEFAULT_TENANT.equals(reference.tenantId())
                ? "${eddivault:" + reference.keyName() + "}"
                : null;

        int evicted = evictMatching(modelCache, fullRef, legacyFullRef, shortRef, legacyShortRef)
                + evictMatching(streamingModelCache, fullRef, legacyFullRef, shortRef, legacyShortRef);

        if (evicted > 0) {
            LOGGER.infof("Evicted %d model(s) using secret '%s/%s'",
                    evicted, sanitize(reference.tenantId()), sanitize(reference.keyName()));
        }
    }

    /**
     * Scan a cache and remove entries whose parameter values contain any of the
     * given vault reference forms (new and legacy prefixes).
     */
    private <T> int evictMatching(Map<ModelCacheKey, T> cache, String... refs) {
        int count = 0;
        Iterator<ModelCacheKey> it = cache.keySet().iterator();
        while (it.hasNext()) {
            ModelCacheKey key = it.next();
            if (parametersContainRef(key.parameters(), refs)) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    private static boolean parametersContainRef(Map<String, String> params, String... refs) {
        for (String value : params.values()) {
            if (value == null)
                continue;
            for (String ref : refs) {
                if (ref != null && value.contains(ref)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Cache key for model lookup — defensive copy of parameters for immutability.
     */
    record ModelCacheKey(String type, Map<String, String> parameters) {
        ModelCacheKey(String type, Map<String, String> parameters) {
            this.type = type;
            this.parameters = new HashMap<>(parameters);
        }
    }

    /**
     * Thrown when a requested model type has no registered builder.
     */
    public static class UnsupportedLlmTaskException extends Exception {
        public UnsupportedLlmTaskException(String message) {
            super(message);
        }
    }
}
