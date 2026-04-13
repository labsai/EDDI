package ai.labs.eddi.modules.llm.impl;

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
    private final SecretResolver secretResolver;
    private final Map<ModelCacheKey, ChatModel> modelCache = new ConcurrentHashMap<>(1);
    private final Map<ModelCacheKey, StreamingChatModel> streamingModelCache = new ConcurrentHashMap<>(1);

    @Inject
    ChatModelRegistry(Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders, SecretResolver secretResolver) {
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
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
        LOGGER.info("ChatModelRegistry registered for secret invalidation events");
    }

    /**
     * Get or create a ChatModel for the given type and processed parameters.
     * Parameters are filtered to remove non-model keys before cache lookup.
     */
    ChatModel getOrCreate(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        // Extract observability params BEFORE filtering (they're removed from cache
        // key)
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

        // Resolve vault references (late-binding: after Qute, before
        // builder.build())
        var resolvedParams = secretResolver.resolveSecrets(filteredParams);
        var rawModel = languageModelApiConnectorBuilders.get(type).get().build(resolvedParams);
        var model = ObservableChatModel.wrapIfNeeded(rawModel, type, timeoutMs, logReq, logResp);
        modelCache.put(cacheKey, model);

        return model;
    }

    /**
     * Get or create a StreamingChatModel for the given type and parameters. Returns
     * {@code null} if the builder does not support streaming.
     */
    StreamingChatModel getOrCreateStreaming(String type, Map<String, String> processedParams) throws UnsupportedLlmTaskException {

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        if (streamingModelCache.containsKey(cacheKey)) {
            return streamingModelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        try {
            // Resolve vault references (late-binding: after Qute, before
            // builder.build())
            var resolvedParams = secretResolver.resolveSecrets(filteredParams);
            var model = languageModelApiConnectorBuilders.get(type).get().buildStreaming(resolvedParams);
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
     */
    private Map<String, String> filterParams(Map<String, String> processedParams) {
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_AGENT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
        // Observability params don't affect model identity — remove from cache key
        returnMap.remove(KEY_TIMEOUT);
        returnMap.remove(KEY_LOG_REQUESTS);
        returnMap.remove(KEY_LOG_RESPONSES);
        return returnMap;
    }

    /**
     * Evict cached model instances affected by a secret change.
     * <p>
     * When {@code reference} is non-null (single secret changed), scan cache
     * entries and evict only those whose parameter values contain a vault reference
     * to the changed secret — either short form ({@code ${eddivault:keyName}}) or
     * full form ({@code ${eddivault:tenantId/keyName}}).
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
        // Full form: ${eddivault:tenantId/keyName} — always valid
        String fullRef = "${eddivault:" + reference.tenantId() + "/" + reference.keyName() + "}";
        // Short form: ${eddivault:keyName} — only valid for the default tenant.
        // The short form always resolves to "default", so a non-default tenant's
        // secret must NOT match short-form references (that would be a false positive).
        String shortRef = SecretReference.DEFAULT_TENANT.equals(reference.tenantId())
                ? "${eddivault:" + reference.keyName() + "}"
                : null;

        int evicted = evictMatching(modelCache, fullRef, shortRef)
                + evictMatching(streamingModelCache, fullRef, shortRef);

        if (evicted > 0) {
            LOGGER.infof("Evicted %d model(s) using secret '%s/%s'",
                    evicted, reference.tenantId(), reference.keyName());
        }
    }

    /**
     * Scan a cache and remove entries whose parameter values contain either vault
     * reference form.
     */
    private <T> int evictMatching(Map<ModelCacheKey, T> cache, String ref1, String ref2) {
        int count = 0;
        Iterator<ModelCacheKey> it = cache.keySet().iterator();
        while (it.hasNext()) {
            ModelCacheKey key = it.next();
            if (parametersContainRef(key.parameters(), ref1, ref2)) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    private static boolean parametersContainRef(Map<String, String> params, String ref1, String ref2) {
        for (String value : params.values()) {
            if (value != null && (value.contains(ref1) || (ref2 != null && value.contains(ref2)))) {
                return true;
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
