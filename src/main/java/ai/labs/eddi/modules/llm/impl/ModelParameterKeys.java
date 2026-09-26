/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_AZURE_OPENAI;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_BEDROCK;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_GEMINI_VERTEX;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_HUGGING_FACE;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_OLLAMA;

/**
 * The parameter keys under which the provider builders read the model to use.
 * <p>
 * There is no single key: OpenAI, Anthropic, Gemini, Mistral, Jlama and Oracle
 * read {@code modelName}, Ollama reads {@code model}, Bedrock, HuggingFace and
 * Vertex read {@code modelId} (Vertex also its legacy {@code modelID}), and
 * Azure OpenAI reads {@code deploymentName}.
 */
final class ModelParameterKeys {

    /** Every key a builder reads the model from. */
    static final List<String> MODEL_KEYS = List.of("modelName", "model", "modelId", "modelID", "deploymentName");

    private ModelParameterKeys() {
    }

    /**
     * A copy of {@code parameters} that selects {@code model} on the given
     * provider.
     * <p>
     * An override that wrote {@code modelName} alone — what the conversation
     * summarizer and the tool-response summarizer did — was ignored by every
     * provider reading another key: on Ollama, Bedrock, HuggingFace, Vertex and
     * Azure the configured summarizer model silently ran as the parent task's
     * model. The model is written under every model key the parameters already
     * carry (so an inherited key can never win over the override), and otherwise
     * under the provider's own key — never under keys the builder does not read,
     * which would trip its unrecognised-parameter warning.
     */
    static Map<String, String> withModel(Map<String, String> parameters, String provider, String model) {
        Map<String, String> result = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
        boolean written = false;
        for (String key : MODEL_KEYS) {
            if (result.containsKey(key)) {
                result.put(key, model);
                written = true;
            }
        }
        if (!written) {
            result.put(keyFor(provider), model);
        }
        return result;
    }

    /** The key the given provider's builder reads the model from. */
    static String keyFor(String provider) {
        if (provider == null) {
            return "modelName";
        }
        return switch (provider) {
            case LLM_TYPE_OLLAMA -> "model";
            case LLM_TYPE_BEDROCK, LLM_TYPE_HUGGING_FACE, LLM_TYPE_GEMINI_VERTEX -> "modelId";
            case LLM_TYPE_AZURE_OPENAI -> "deploymentName";
            default -> "modelName";
        };
    }
}
