/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class GeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    private static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String KEY_ALLOW_CODE_EXECUTION = "allowCodeExecution";
    private static final String KEY_TIMEOUT = "timeout";

    // NOTE: responseFormat is intentionally NOT set at the model builder level.
    // Gemini does not support combining responseFormat=JSON (responseMimeType=
    // application/json) with function calling (tools). Setting it here would
    // cause "Function calling with a response mime type: 'application/json' is
    // unsupported" errors whenever tools are enabled.
    // JSON response format is enforced at the REQUEST level by LegacyChatExecutor,
    // which only applies it when no tools are present.

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = GoogleAiGeminiChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_OUTPUT_TOKENS))) {
            builder.maxOutputTokens(Integer.parseInt(parameters.get(KEY_MAX_OUTPUT_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_ALLOW_CODE_EXECUTION))) {
            builder.allowCodeExecution(Boolean.parseBoolean(parameters.get(KEY_ALLOW_CODE_EXECUTION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = GoogleAiGeminiStreamingChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_OUTPUT_TOKENS))) {
            builder.maxOutputTokens(Integer.parseInt(parameters.get(KEY_MAX_OUTPUT_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_ALLOW_CODE_EXECUTION))) {
            builder.allowCodeExecution(Boolean.parseBoolean(parameters.get(KEY_ALLOW_CODE_EXECUTION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        return builder.build();
    }
}
