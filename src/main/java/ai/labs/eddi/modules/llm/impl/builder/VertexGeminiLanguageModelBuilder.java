/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class VertexGeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_PUBLISHER = "publisher";
    private static final String KEY_MODEL_ID = "modelID";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_LOCATION = "location";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = VertexAiGeminiChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_PROJECT_ID))) {
            builder.project(parameters.get(KEY_PROJECT_ID));
        }

        // Core langchain4j uses location() instead of publisher();
        // "location" param takes priority, fallback to "publisher" for backward compat
        if (!isNullOrEmpty(parameters.get(KEY_LOCATION))) {
            builder.location(parameters.get(KEY_LOCATION));
        } else if (!isNullOrEmpty(parameters.get(KEY_PUBLISHER))) {
            builder.location(parameters.get(KEY_PUBLISHER));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelName(parameters.get(KEY_MODEL_ID));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Float.parseFloat(parameters.get(KEY_TEMPERATURE)));
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
