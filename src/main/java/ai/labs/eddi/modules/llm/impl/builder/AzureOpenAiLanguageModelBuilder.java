/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Azure OpenAI builder — uses Azure SDK HTTP pipeline (NOT JdkHttpClient).
 * <p>
 * Key differences from standard OpenAI:
 * <ul>
 * <li>Uses {@code deploymentName} instead of {@code modelName}</li>
 * <li>Uses {@code logRequestsAndResponses} (combined) instead of separate
 * flags</li>
 * <li>Requires {@code endpoint} (format:
 * {@code https://{resource}.openai.azure.com/})</li>
 * <li>Auth via {@code apiKey} (Azure key) or {@code nonAzureApiKey} (standard
 * OpenAI key)</li>
 * </ul>
 * <p>
 * ⚠️ Native image: medium risk — Azure SDK uses Kotlin+Jackson reflection. Ship
 * for JVM mode, fix native config during Phase 12 CI/CD (native-image agent
 * tracing).
 */
@ApplicationScoped
public class AzureOpenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_NON_AZURE_API_KEY = "nonAzureApiKey";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_DEPLOYMENT_NAME = "deploymentName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = AzureOpenAiChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_ENDPOINT))) {
            builder.endpoint(parameters.get(KEY_ENDPOINT));
        }
        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_NON_AZURE_API_KEY))) {
            builder.nonAzureApiKey(parameters.get(KEY_NON_AZURE_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_DEPLOYMENT_NAME))) {
            builder.deploymentName(parameters.get(KEY_DEPLOYMENT_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = AzureOpenAiStreamingChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_ENDPOINT))) {
            builder.endpoint(parameters.get(KEY_ENDPOINT));
        }
        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_NON_AZURE_API_KEY))) {
            builder.nonAzureApiKey(parameters.get(KEY_NON_AZURE_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_DEPLOYMENT_NAME))) {
            builder.deploymentName(parameters.get(KEY_DEPLOYMENT_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }

        return builder.build();
    }
}
