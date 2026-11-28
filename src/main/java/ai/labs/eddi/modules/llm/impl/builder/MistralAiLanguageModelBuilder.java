/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatRequestParameters;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Mistral AI chat completions.
 * <p>
 * Parameters are read once into a {@link Settings} and applied to the
 * synchronous and the streaming client alike. Mistral's
 * {@code reasoning_effort}, {@code service_tier}, {@code top_k},
 * {@code min_tokens} and {@code prompt_mode} cannot be configured: langchain4j
 * 1.23.0 has no setter for them and its Mistral client offers no way to add raw
 * body fields.
 */
@ApplicationScoped
public class MistralAiLanguageModelBuilder implements ILanguageModelBuilder {

    /**
     * Applies {@code eddi.security.ssrf-protection.enabled} to the configured
     * endpoint. Field-injected; null in tests built with {@code new}, which then
     * validate as with protection off (metadata check only).
     */
    @Inject
    OutboundTargetPolicy targetPolicy;
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_TOP_P = "topP";
    /** EDDI's shared key; Mistral calls it {@code random_seed}. */
    static final String KEY_SEED = "seed";
    static final String KEY_SAFE_PROMPT = "safePrompt";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_FREQUENCY_PENALTY = "frequencyPenalty";
    static final String KEY_PRESENCE_PENALTY = "presencePenalty";
    static final String KEY_STRICT_JSON_SCHEMA = "strictJsonSchema";
    static final String KEY_RETURN_THINKING = "returnThinking";
    static final String KEY_SEND_THINKING = "sendThinking";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_API_KEY, KEY_MODEL_NAME, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_TIMEOUT, KEY_LOG_REQUESTS,
                KEY_LOG_RESPONSES, KEY_BASE_URL, KEY_TOP_P, KEY_SEED, KEY_SAFE_PROMPT, KEY_STOP_SEQUENCES,
                KEY_FREQUENCY_PENALTY, KEY_PRESENCE_PENALTY, KEY_STRICT_JSON_SCHEMA, KEY_RETURN_THINKING,
                KEY_SEND_THINKING, KEY_CUSTOM_HEADERS);
    }

    /**
     * Every setting the builders read; {@code null} leaves the provider default.
     */
    private record Settings(String baseUrl, String apiKey, String modelName, Double temperature, Double topP, Integer maxTokens,
            Integer randomSeed, Boolean safePrompt, List<String> stop, Double frequencyPenalty, Double presencePenalty,
            Boolean strictJsonSchema, Boolean returnThinking, Boolean sendThinking, Long timeoutMs, Boolean logRequests,
            Boolean logResponses, Map<String, String> customHeaders) {
    }

    private static Settings read(Map<String, String> p, OutboundTargetPolicy targetPolicy) {
        String baseUrl = stringValue(p, KEY_BASE_URL);
        if (baseUrl != null) {
            // Never the cloud instance-metadata service; with
            // eddi.security.ssrf-protection.enabled no private address either.
            targetPolicy.validateUrl(baseUrl);
        }
        return new Settings(baseUrl, isNullOrEmpty(p.get(KEY_API_KEY)) ? null : p.get(KEY_API_KEY),
                isNullOrEmpty(p.get(KEY_MODEL_NAME)) ? null : p.get(KEY_MODEL_NAME), doubleValue(p, KEY_TEMPERATURE),
                doubleValue(p, KEY_TOP_P), intValue(p, KEY_MAX_TOKENS), intValue(p, KEY_SEED), booleanOrNull(p, KEY_SAFE_PROMPT),
                stringList(p, KEY_STOP_SEQUENCES), doubleValue(p, KEY_FREQUENCY_PENALTY), doubleValue(p, KEY_PRESENCE_PENALTY),
                booleanOrNull(p, KEY_STRICT_JSON_SCHEMA), booleanOrNull(p, KEY_RETURN_THINKING), booleanOrNull(p, KEY_SEND_THINKING),
                longValue(p, KEY_TIMEOUT), booleanOrNull(p, KEY_LOG_REQUESTS), booleanOrNull(p, KEY_LOG_RESPONSES),
                stringMap(p, KEY_CUSTOM_HEADERS));
    }

    /**
     * The request-level settings as langchain4j's request parameters, handed to the
     * synchronous and the streaming client alike: every request starts from them. A
     * {@code null} leaves the provider default. What stays on the client builders
     * is not part of a request: endpoint, credentials, timeout, logging, headers
     * and {@code strictJsonSchema}, which langchain4j only takes there.
     */
    private static MistralAiChatRequestParameters requestParameters(Settings s) {
        return MistralAiChatRequestParameters.builder().modelName(s.modelName()).temperature(s.temperature()).topP(s.topP())
                .maxOutputTokens(s.maxTokens()).randomSeed(s.randomSeed()).safePrompt(s.safePrompt()).stopSequences(s.stop())
                .frequencyPenalty(s.frequencyPenalty()).presencePenalty(s.presencePenalty()).returnThinking(s.returnThinking())
                .sendThinking(s.sendThinking()).build();
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, BoundedJdkHttpClient.builder(targetPolicy));
    }

    /**
     * Seam for the unit tests: a fake {@link HttpClientBuilder} shows what reaches
     * the wire. Production goes through {@link #build(Map)}.
     */
    ChatModel build(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = MistralAiChatModel.builder().httpClientBuilder(httpClientBuilder).defaultRequestParameters(requestParameters(s));
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());

        // executeWithRetry is the single retry owner; library retries multiply it.
        b.maxRetries(NO_LIBRARY_RETRIES);

        return b.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return buildStreaming(parameters, BoundedJdkHttpClient.builder(targetPolicy));
    }

    /** Streaming counterpart of {@link #build(Map, HttpClientBuilder)}. */
    StreamingChatModel buildStreaming(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = MistralAiStreamingChatModel.builder().httpClientBuilder(httpClientBuilder)
                .defaultRequestParameters(requestParameters(s));
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());

        return b.build();
    }
}
