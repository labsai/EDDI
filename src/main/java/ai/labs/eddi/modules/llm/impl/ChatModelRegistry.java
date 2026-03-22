package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ChatModel creation, caching, and lookup by type and parameters.
 * <p>
 * Models are cached by (type, parameters) tuple so that identical configs
 * reuse the same model instance. Thread-safe via ConcurrentHashMap.
 * <p>
 * Supports agenth synchronous ({@link ChatModel}) and streaming
 * ({@link StreamingChatModel}) models with separate caches.
 */
class ChatModelRegistry {
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

    ChatModelRegistry(Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders,
            SecretResolver secretResolver) {
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
        this.secretResolver = secretResolver;
    }

    /**
     * Get or create a ChatModel for the given type and processed parameters.
     * Parameters are filtered to remove non-model keys before cache lookup.
     */
    ChatModel getOrCreate(String type, Map<String, String> processedParams)
            throws UnsupportedLlmTaskException {

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

        // Resolve vault references (late-binding: after Thymeleaf, before
        // builder.build())
        var resolvedParams = secretResolver.resolveSecrets(filteredParams);
        var rawModel = languageModelApiConnectorBuilders.get(type).get().build(resolvedParams);
        var model = ObservableChatModel.wrapIfNeeded(rawModel, type, timeoutMs, logReq, logResp);
        modelCache.put(cacheKey, model);

        return model;
    }

    /**
     * Get or create a StreamingChatModel for the given type and parameters.
     * Returns {@code null} if the builder does not support streaming.
     */
    StreamingChatModel getOrCreateStreaming(String type, Map<String, String> processedParams)
            throws UnsupportedLlmTaskException {

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        if (streamingModelCache.containsKey(cacheKey)) {
            return streamingModelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLlmTaskException(String.format("Type \"%s\" is not supported", type));
        }

        try {
            // Resolve vault references (late-binding: after Thymeleaf, before
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
