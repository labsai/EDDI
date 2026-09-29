/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds a chat model for one named OpenAI-compatible provider (xAI, DeepSeek,
 * Moonshot, ...) by applying the provider's preset to the generic
 * {@link OpenAILanguageModelBuilder}.
 * <p>
 * Not a CDI bean: {@code LlmModule} creates one instance per catalog entry.
 * <p>
 * Precedence: an explicit {@code baseUrl} wins over {@code region}, which wins
 * over the preset's default endpoint; an explicit {@code modelName} wins over
 * the preset's default; explicit parameters win over the preset's
 * {@code parameterDefaults}.
 */
public class OpenAiCompatibleLanguageModelBuilder implements ILanguageModelBuilder {
    static final String KEY_REGION = "region";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_API_KEY = "apiKey";

    private final OpenAiCompatibleProvider provider;
    private final OpenAILanguageModelBuilder delegate;

    public OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProvider provider, OpenAILanguageModelBuilder delegate) {
        this.provider = provider;
        this.delegate = delegate;
    }

    @Override
    public Set<String> recognisedParameters() {
        Set<String> recognised = new HashSet<>(delegate.recognisedParameters());
        recognised.add(KEY_REGION);
        return recognised;
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return delegate.build(merged(parameters), provider.customParameters());
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return delegate.buildStreaming(merged(parameters), provider.customParameters());
    }

    private Map<String, String> merged(Map<String, String> parameters) {
        Map<String, String> merged = new HashMap<>(parameters == null ? Map.of() : parameters);
        provider.parameterDefaults().forEach(merged::putIfAbsent);

        if (isBlank(merged.get(KEY_BASE_URL))) {
            merged.put(KEY_BASE_URL, provider.resolveBaseUrl(merged.get(KEY_REGION)));
        }
        merged.remove(KEY_REGION);

        if (isBlank(merged.get(KEY_MODEL_NAME))) {
            merged.put(KEY_MODEL_NAME, provider.defaultModel());
        }
        if (isBlank(merged.get(KEY_API_KEY))) {
            throw new IllegalArgumentException(provider.displayName() + " requires an apiKey, e.g. ${vault:" + provider.id() + "-key}");
        }
        return merged;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
