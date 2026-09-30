/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameter keys under which the provider builders read the model to use.
 * <p>
 * There is no single key: OpenAI, Anthropic, Gemini, Mistral, Jlama and Oracle
 * read {@code modelName}, Ollama reads {@code model}, Bedrock, HuggingFace and
 * Vertex read {@code modelId} (Vertex also its legacy {@code modelID}), and
 * Azure OpenAI reads {@code deploymentName}. The provider-to-key mapping itself
 * is {@link SummarizationService#modelParameterKey}.
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
     * carry (so an inherited key can never win over the override), and always under
     * the provider's own key: parameters inherited from a task on another provider
     * can carry only THAT provider's key ({@code modelName} from an OpenAI task
     * under an Ollama summary), which the selected builder never reads. No other
     * key is added, so the builder's unrecognised-parameter warning only ever names
     * keys the caller already passed.
     */
    static Map<String, String> withModel(Map<String, String> parameters, String provider, String model) {
        Map<String, String> result = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
        for (String key : MODEL_KEYS) {
            if (result.containsKey(key)) {
                result.put(key, model);
            }
        }
        result.put(SummarizationService.modelParameterKey(provider), model);
        return result;
    }
}
