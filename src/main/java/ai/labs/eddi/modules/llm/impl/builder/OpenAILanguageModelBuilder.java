/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.objectMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * OpenAI Chat Completions, and (through
 * {@link OpenAiCompatibleLanguageModelBuilder}) every OpenAI-compatible
 * provider.
 * <p>
 * Parameters are read once into a {@link Settings}. The request-level ones
 * reach both clients as one set of request parameters
 * ({@link #requestParameters}); the few client options are applied to each
 * client builder, and {@code ProviderChatStreamingParityTest} fails when the
 * two clients disagree. Which of them a given <em>model</em> accepts is not
 * decided here: the reasoning families reject {@code temperature},
 * {@code topP}, the penalties and {@code maxTokens}, and
 * {@link ModelParameterCatalog} (consulted by {@code ChatModelRegistry} before
 * a builder runs) removes what the configured model would reject.
 */
@ApplicationScoped
public class OpenAILanguageModelBuilder implements ILanguageModelBuilder {

    /**
     * Applies {@code eddi.security.ssrf-protection.enabled} to the configured
     * endpoint. Field-injected; null in tests built with {@code new}, which then
     * validate as with protection off (metadata check only).
     */
    @Inject
    OutboundTargetPolicy targetPolicy;
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    private static final String KEY_RESPONSE_FORMAT = "responseFormat";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_RETURN_THINKING = "returnThinking";
    private static final String KEY_SEND_THINKING = "sendThinking";
    static final String KEY_TOP_P = "topP";
    static final String KEY_FREQUENCY_PENALTY = "frequencyPenalty";
    static final String KEY_PRESENCE_PENALTY = "presencePenalty";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_SEED = "seed";
    static final String KEY_LOGIT_BIAS = "logitBias";
    static final String KEY_MAX_COMPLETION_TOKENS = "maxCompletionTokens";
    static final String KEY_REASONING_EFFORT = "reasoningEffort";
    static final String KEY_VERBOSITY = "verbosity";
    static final String KEY_SERVICE_TIER = "serviceTier";
    static final String KEY_PARALLEL_TOOL_CALLS = "parallelToolCalls";
    static final String KEY_STRICT_TOOLS = "strictTools";
    static final String KEY_STRICT_JSON_SCHEMA = "strictJsonSchema";
    static final String KEY_STORE = "store";
    static final String KEY_METADATA = "metadata";
    static final String KEY_USER = "user";
    static final String KEY_SAFETY_IDENTIFIER = "safetyIdentifier";
    static final String KEY_ORGANIZATION_ID = "organizationId";
    static final String KEY_PROJECT_ID = "projectId";
    static final String KEY_PROMPT_CACHE_KEY = "promptCacheKey";
    static final String KEY_PROMPT_CACHE_RETENTION = "promptCacheRetention";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";
    static final String KEY_CUSTOM_QUERY_PARAMS = "customQueryParams";
    static final String KEY_CUSTOM_PARAMETERS = "customParameters";
    private static final String TYPE_JSON = "json";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_API_KEY, KEY_TEMPERATURE, KEY_MODEL_NAME, KEY_TIMEOUT, KEY_LOG_REQUESTS, KEY_LOG_RESPONSES,
                KEY_RESPONSE_FORMAT, KEY_BASE_URL, KEY_MAX_TOKENS, KEY_RETURN_THINKING, KEY_SEND_THINKING, KEY_TOP_P,
                KEY_FREQUENCY_PENALTY, KEY_PRESENCE_PENALTY, KEY_STOP_SEQUENCES, KEY_SEED, KEY_LOGIT_BIAS,
                KEY_MAX_COMPLETION_TOKENS, KEY_REASONING_EFFORT, KEY_VERBOSITY, KEY_SERVICE_TIER,
                KEY_PARALLEL_TOOL_CALLS, KEY_STRICT_TOOLS, KEY_STRICT_JSON_SCHEMA, KEY_STORE, KEY_METADATA, KEY_USER,
                KEY_SAFETY_IDENTIFIER, KEY_ORGANIZATION_ID, KEY_PROJECT_ID, KEY_PROMPT_CACHE_KEY,
                KEY_PROMPT_CACHE_RETENTION, KEY_CUSTOM_HEADERS, KEY_CUSTOM_QUERY_PARAMS, KEY_CUSTOM_PARAMETERS);
    }

    /**
     * Every setting the builders read. Absent or unusable values are {@code null}
     * so the provider's own default stays in place.
     */
    private record Settings(String baseUrl, String apiKey, String modelName, Long timeoutMs, Double temperature, Double topP,
            Double frequencyPenalty, Double presencePenalty, Integer maxTokens, Integer maxCompletionTokens, List<String> stop,
            Integer seed, Map<String, Integer> logitBias, String reasoningEffort, String serviceTier,
            Boolean parallelToolCalls, Boolean strictTools, Boolean strictJsonSchema, Boolean store, Map<String, String> metadata,
            String user, String organizationId, String projectId, String promptCacheKey, Boolean logRequests, Boolean logResponses,
            boolean jsonObject, boolean returnThinking, boolean sendThinking, Map<String, String> customHeaders,
            Map<String, String> customQueryParams, Map<String, Object> customParameters) {
    }

    private static Settings read(Map<String, String> p, Map<String, Object> providerCustomParameters, OutboundTargetPolicy targetPolicy) {
        String baseUrl = null;
        if (!isNullOrEmpty(p.get(KEY_BASE_URL))) {
            // Never the cloud instance-metadata service; with
            // eddi.security.ssrf-protection.enabled no private address either.
            targetPolicy.validateUrl(p.get(KEY_BASE_URL));
            baseUrl = p.get(KEY_BASE_URL);
        }

        // Body fields langchain4j has no setter for travel as custom parameters:
        // the provider's own first, then these, then the user's customParameters,
        // which win.
        Map<String, Object> body = new LinkedHashMap<>();
        if (providerCustomParameters != null) {
            body.putAll(providerCustomParameters);
        }
        putIfPresent(body, "verbosity", stringValue(p, KEY_VERBOSITY));
        putIfPresent(body, "safety_identifier", stringValue(p, KEY_SAFETY_IDENTIFIER));
        putIfPresent(body, "prompt_cache_retention", stringValue(p, KEY_PROMPT_CACHE_RETENTION));
        Map<String, Object> userBody = objectMap(p, KEY_CUSTOM_PARAMETERS);
        if (userBody != null) {
            body = ModelParameterValues.deepMerge(body, userBody);
        }

        return new Settings(baseUrl, isNullOrEmpty(p.get(KEY_API_KEY)) ? null : p.get(KEY_API_KEY),
                isNullOrEmpty(p.get(KEY_MODEL_NAME)) ? null : p.get(KEY_MODEL_NAME), longValue(p, KEY_TIMEOUT),
                doubleValue(p, KEY_TEMPERATURE), doubleValue(p, KEY_TOP_P), doubleValue(p, KEY_FREQUENCY_PENALTY),
                doubleValue(p, KEY_PRESENCE_PENALTY), intValue(p, KEY_MAX_TOKENS), intValue(p, KEY_MAX_COMPLETION_TOKENS),
                stringList(p, KEY_STOP_SEQUENCES), intValue(p, KEY_SEED), intMap(p, KEY_LOGIT_BIAS),
                stringValue(p, KEY_REASONING_EFFORT), stringValue(p, KEY_SERVICE_TIER),
                booleanOrNull(p, KEY_PARALLEL_TOOL_CALLS), booleanOrNull(p, KEY_STRICT_TOOLS), booleanOrNull(p, KEY_STRICT_JSON_SCHEMA),
                booleanOrNull(p, KEY_STORE), stringMap(p, KEY_METADATA), stringValue(p, KEY_USER), stringValue(p, KEY_ORGANIZATION_ID),
                stringValue(p, KEY_PROJECT_ID), stringValue(p, KEY_PROMPT_CACHE_KEY), booleanOrNull(p, KEY_LOG_REQUESTS),
                booleanOrNull(p, KEY_LOG_RESPONSES), TYPE_JSON.equalsIgnoreCase(p.get(KEY_RESPONSE_FORMAT)),
                booleanValue(p, KEY_RETURN_THINKING, false), booleanValue(p, KEY_SEND_THINKING, false),
                stringMap(p, KEY_CUSTOM_HEADERS), stringMap(p, KEY_CUSTOM_QUERY_PARAMS), body.isEmpty() ? null : body);
    }

    private static void putIfPresent(Map<String, Object> body, String field, String value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    /**
     * The request-level settings as langchain4j's request parameters, handed to the
     * synchronous and the streaming client alike: every request starts from them. A
     * {@code null} leaves the provider default. What stays on the client builders
     * is not part of a request (endpoint, credentials, timeout, logging, headers,
     * the thinking switches), {@code strictTools} and {@code strictJsonSchema}, and
     * the {@code json_object} response format, whose OpenAI wire name the request
     * parameters do not carry.
     */
    private static OpenAiChatRequestParameters requestParameters(Settings s) {
        return OpenAiChatRequestParameters.builder().modelName(s.modelName()).temperature(s.temperature()).topP(s.topP())
                .frequencyPenalty(s.frequencyPenalty()).presencePenalty(s.presencePenalty()).maxOutputTokens(s.maxTokens())
                .maxCompletionTokens(s.maxCompletionTokens()).stopSequences(s.stop()).seed(s.seed()).logitBias(s.logitBias())
                .reasoningEffort(s.reasoningEffort()).serviceTier(s.serviceTier()).parallelToolCalls(s.parallelToolCalls())
                .store(s.store()).metadata(s.metadata()).user(s.user()).promptCacheKey(s.promptCacheKey())
                .customParameters(s.customParameters()).build();
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, Map.of());
    }

    public ChatModel build(Map<String, String> parameters, Map<String, Object> customParameters) {
        return build(parameters, customParameters, BoundedJdkHttpClient.builder(targetPolicy));
    }

    /**
     * Seam for the unit tests: a fake {@link HttpClientBuilder} runs langchain4j's
     * real request mapping and shows what reaches the wire. Production goes through
     * {@link #build(Map, Map)}.
     */
    ChatModel build(Map<String, String> parameters, Map<String, Object> customParameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, customParameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = OpenAiChatModel.builder().httpClientBuilder(httpClientBuilder).defaultRequestParameters(requestParameters(s));
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.organizationId() != null)
            b.organizationId(s.organizationId());
        if (s.projectId() != null)
            b.projectId(s.projectId());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.strictTools() != null)
            b.strictTools(s.strictTools());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.jsonObject())
            b.responseFormat("json_object");
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        if (s.customQueryParams() != null)
            b.customQueryParams(s.customQueryParams());
        b.returnThinking(s.returnThinking());
        b.sendThinking(s.sendThinking());
        // executeWithRetry is the single retry owner; library retries multiply it.
        b.maxRetries(NO_LIBRARY_RETRIES);
        return b.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return buildStreaming(parameters, Map.of());
    }

    public StreamingChatModel buildStreaming(Map<String, String> parameters, Map<String, Object> customParameters) {
        return buildStreaming(parameters, customParameters, BoundedJdkHttpClient.builder(targetPolicy));
    }

    /** Streaming counterpart of {@link #build(Map, Map, HttpClientBuilder)}. */
    StreamingChatModel buildStreaming(Map<String, String> parameters, Map<String, Object> customParameters,
                                      HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, customParameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = OpenAiStreamingChatModel.builder().httpClientBuilder(httpClientBuilder)
                .defaultRequestParameters(requestParameters(s));
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.organizationId() != null)
            b.organizationId(s.organizationId());
        if (s.projectId() != null)
            b.projectId(s.projectId());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.strictTools() != null)
            b.strictTools(s.strictTools());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.jsonObject())
            b.responseFormat("json_object");
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        if (s.customQueryParams() != null)
            b.customQueryParams(s.customQueryParams());
        b.returnThinking(s.returnThinking());
        b.sendThinking(s.sendThinking());
        return b.build();
    }
}
