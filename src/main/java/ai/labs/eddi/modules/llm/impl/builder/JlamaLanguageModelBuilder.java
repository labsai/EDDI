/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class JlamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = JlamaChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }

        if (!isNullOrEmpty(parameters.get(KEY_AUTH_TOKEN))) {
            builder.authToken(parameters.get(KEY_AUTH_TOKEN));
        }

        // Parsed as a double and narrowed: this setter takes a float, and a separate
        // float helper would buy nothing — every value a float accepts, a double
        // accepts too.
        applyDouble(parameters, KEY_TEMPERATURE, temperature -> builder.temperature((float) temperature));

        applyInt(parameters, KEY_MAX_TOKENS, builder::maxTokens);

        return builder.build();
    }
}
