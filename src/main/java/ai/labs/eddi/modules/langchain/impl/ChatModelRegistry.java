package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.modules.langchain.impl.builder.ILanguageModelBuilder;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Provider;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ChatModel creation, caching, and lookup by type and parameters.
 * <p>
 * Models are cached by (type, parameters) tuple so that identical configs
 * reuse the same model instance. Thread-safe via ConcurrentHashMap.
 */
class ChatModelRegistry {
    private static final String KEY_INCLUDE_FIRST_BOT_MESSAGE = "includeFirstBotMessage";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";

    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;
    private final Map<ModelCacheKey, ChatModel> modelCache = new ConcurrentHashMap<>(1);

    ChatModelRegistry(Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders) {
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
    }

    /**
     * Get or create a ChatModel for the given type and processed parameters.
     * Parameters are filtered to remove non-model keys before cache lookup.
     */
    ChatModel getOrCreate(String type, Map<String, String> processedParams)
            throws UnsupportedLangchainTaskException {

        var filteredParams = filterParams(processedParams);
        var cacheKey = new ModelCacheKey(type, filteredParams);

        if (modelCache.containsKey(cacheKey)) {
            return modelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLangchainTaskException(String.format("Type \"%s\" is not supported", type));
        }

        var model = languageModelApiConnectorBuilders.get(type).get().build(filteredParams);
        modelCache.put(cacheKey, model);

        return model;
    }

    /**
     * Remove all props that are not directly configuring the langchain builders
     * (for better caching).
     */
    private Map<String, String> filterParams(Map<String, String> processedParams) {
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_BOT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
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
    public static class UnsupportedLangchainTaskException extends Exception {
        public UnsupportedLangchainTaskException(String message) {
            super(message);
        }
    }
}
