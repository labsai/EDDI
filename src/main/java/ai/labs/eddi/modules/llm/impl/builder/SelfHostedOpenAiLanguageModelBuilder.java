/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the chat model of the generic {@code openai-compatible} type (see
 * {@link OpenAiCompatibleEndpoint}) on the {@link OpenAILanguageModelBuilder}.
 * <p>
 * Everything the OpenAI builder reads works here too, including
 * {@code returnThinking}/{@code sendThinking} and {@code customParameters} —
 * the pass-through for server-specific request-body fields such as vLLM's
 * {@code chat_template_kwargs} or {@code guided_json}. Where the OpenAI builder
 * is lenient (it warns and drops an unparseable {@code customParameters}), this
 * type is strict: an endpoint whose extra body does not parse to a JSON object
 * fails to build, naming the parameter, instead of silently losing the fields
 * the server needs.
 * <p>
 * Not a CDI bean: {@code LlmModule} creates it around the shared OpenAI
 * builder, as it does for the catalog providers.
 */
public class SelfHostedOpenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_API_KEY = "apiKey";

    private final OpenAILanguageModelBuilder delegate;
    private final ModelParameterCatalog catalog;

    public SelfHostedOpenAiLanguageModelBuilder(OpenAILanguageModelBuilder delegate) {
        this(delegate, ModelParameterCatalog.defaultCatalog());
    }

    SelfHostedOpenAiLanguageModelBuilder(OpenAILanguageModelBuilder delegate, ModelParameterCatalog catalog) {
        this.delegate = delegate;
        this.catalog = catalog;
    }

    @Override
    public Set<String> recognisedParameters() {
        Set<String> recognised = new HashSet<>(delegate.recognisedParameters());
        recognised.addAll(OpenAiCompatibleEndpoint.OWN_KEYS);
        recognised.addAll(catalog.keys(OpenAiCompatibleEndpoint.TYPE));
        return recognised;
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return delegate.build(prepare(parameters), Map.of());
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return delegate.buildStreaming(prepare(parameters), Map.of());
    }

    /**
     * Validate the endpoint and return the parameters the OpenAI builder receives:
     * this type's own keys removed, everything else unchanged.
     *
     * @throws IllegalArgumentException
     *             naming the parameter, for a missing {@code baseUrl} or
     *             {@code modelName}, a base URL that is not http(s) or targets the
     *             cloud-metadata service, or a {@code customParameters} that is not
     *             a JSON object
     */
    Map<String, String> prepare(Map<String, String> parameters) {
        Map<String, String> p = new HashMap<>(parameters == null ? Map.of() : parameters);
        // Only the required fields stop a build. Capability flags shape requests,
        // not the model, and are judged there (see OpenAiCompatibleEndpoint): a
        // literal bad flag already failed the deploy, and a templated one that
        // resolves to a non-boolean degrades to false instead of failing the turn.
        List<String> problems = OpenAiCompatibleEndpoint.missingRequired(p);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }

        String baseUrl = p.get(OpenAiCompatibleEndpoint.KEY_BASE_URL).trim();
        if (!UrlValidationUtils.isValidHttpUrl(baseUrl)) {
            throw new IllegalArgumentException("LLM type '" + OpenAiCompatibleEndpoint.TYPE + "': 'baseUrl' must be an http:// or https:// URL");
        }
        // http:// and private hosts are allowed on purpose (in-cluster inference);
        // the cloud instance-metadata service never is. The OpenAI builder repeats
        // this check, which is cheap and keeps the guarantee local.
        UrlValidationUtils.rejectCloudMetadataTarget(baseUrl);
        p.put(OpenAiCompatibleEndpoint.KEY_BASE_URL, baseUrl);
        p.put(OpenAiCompatibleEndpoint.KEY_MODEL_NAME, p.get(OpenAiCompatibleEndpoint.KEY_MODEL_NAME).trim());

        // Many local servers take no key. A blank one must not become a
        // "Bearer " header: absent, langchain4j sends no Authorization at all.
        String apiKey = p.get(KEY_API_KEY);
        if (apiKey != null && apiKey.isBlank()) {
            p.remove(KEY_API_KEY);
        }

        requireJsonObject(p, OpenAILanguageModelBuilder.KEY_CUSTOM_PARAMETERS);
        OpenAiCompatibleEndpoint.OWN_KEYS.forEach(p::remove);
        return p;
    }

    private static void requireJsonObject(Map<String, String> p, String key) {
        String raw = p.get(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("LLM type '" + OpenAiCompatibleEndpoint.TYPE + "': '" + key
                    + "' must be a JSON object of extra request-body fields, e.g. {\"chat_template_kwargs\":{\"enable_thinking\":false}}");
        }
    }
}
