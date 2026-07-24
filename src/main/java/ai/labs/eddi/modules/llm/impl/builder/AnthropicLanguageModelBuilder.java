/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class AnthropicLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TOP_P = "topP";
    private static final String KEY_TOP_K = "topK";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    /**
     * Default max output tokens when not configured. Anthropic's langchain4j
     * default is 1024, which is far too low for models with extended thinking (e.g.
     * claude-sonnet-5) — thinking tokens consume the budget and leave nothing for
     * the actual text response. 16384 matches Claude's natural output limit without
     * extended thinking. Setting this higher has no cost impact (the model only
     * generates what it needs); the timeout parameter is the real cost safety net,
     * not this ceiling.
     */
    private static final int DEFAULT_MAX_TOKENS = 16384;

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = AnthropicChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        } else {
            builder.maxTokens(DEFAULT_MAX_TOKENS);
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TOP_P))) {
            builder.topP(Double.parseDouble(parameters.get(KEY_TOP_P)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TOP_K))) {
            builder.topK(Integer.parseInt(parameters.get(KEY_TOP_K)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = AnthropicStreamingChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        } else {
            builder.maxTokens(DEFAULT_MAX_TOKENS);
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TOP_P))) {
            builder.topP(Double.parseDouble(parameters.get(KEY_TOP_P)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TOP_K))) {
            builder.topK(Integer.parseInt(parameters.get(KEY_TOP_K)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }
}
