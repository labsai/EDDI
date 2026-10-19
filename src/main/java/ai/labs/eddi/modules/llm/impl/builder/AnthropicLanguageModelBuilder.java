/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.objectMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Anthropic Messages API.
 * <p>
 * Which parameters a given model accepts differs a lot between generations
 * (manual {@code thinkingBudgetTokens} versus {@code thinkingType=adaptive},
 * {@code effort}, sampling restrictions while thinking); that is decided by
 * {@link ModelParameterCatalog} before this builder runs, not here.
 */
@ApplicationScoped
public class AnthropicLanguageModelBuilder implements ILanguageModelBuilder {

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
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TOP_P = "topP";
    private static final String KEY_TOP_K = "topK";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_THINKING_TYPE = "thinkingType";
    static final String KEY_THINKING_BUDGET_TOKENS = "thinkingBudgetTokens";
    static final String KEY_THINKING_DISPLAY = "thinkingDisplay";
    static final String KEY_RETURN_THINKING = "returnThinking";
    static final String KEY_SEND_THINKING = "sendThinking";
    static final String KEY_EFFORT = "effort";
    static final String KEY_CACHE_SYSTEM_MESSAGES = "cacheSystemMessages";
    static final String KEY_CACHE_TOOLS = "cacheTools";
    static final String KEY_CACHE_AUTOMATICALLY = "cacheAutomatically";
    static final String KEY_CACHE_TTL = "cacheTtl";
    static final String KEY_DISABLE_PARALLEL_TOOL_USE = "disableParallelToolUse";
    static final String KEY_STRICT_TOOLS = "strictTools";
    static final String KEY_USER_ID = "userId";
    static final String KEY_VERSION = "version";
    static final String KEY_BETA = "beta";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";
    static final String KEY_CUSTOM_PARAMETERS = "customParameters";

    public static final int DEFAULT_MAX_TOKENS = 16384;

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_API_KEY, KEY_TEMPERATURE, KEY_MODEL_NAME, KEY_MAX_TOKENS, KEY_TOP_P, KEY_TOP_K, KEY_TIMEOUT,
                KEY_LOG_REQUESTS, KEY_LOG_RESPONSES, KEY_BASE_URL, KEY_STOP_SEQUENCES, KEY_THINKING_TYPE,
                KEY_THINKING_BUDGET_TOKENS, KEY_THINKING_DISPLAY, KEY_RETURN_THINKING, KEY_SEND_THINKING, KEY_EFFORT,
                KEY_CACHE_SYSTEM_MESSAGES, KEY_CACHE_TOOLS, KEY_CACHE_AUTOMATICALLY, KEY_CACHE_TTL,
                KEY_DISABLE_PARALLEL_TOOL_USE, KEY_STRICT_TOOLS, KEY_USER_ID, KEY_VERSION, KEY_BETA, KEY_CUSTOM_HEADERS,
                KEY_CUSTOM_PARAMETERS);
    }

    /**
     * Every setting the builders read; {@code null} leaves the provider default.
     */
    private record Settings(String baseUrl, String apiKey, String modelName, int maxTokens, Long timeoutMs, Double temperature,
            Double topP, Integer topK, List<String> stop, String thinkingType, Integer thinkingBudgetTokens, String thinkingDisplay,
            Boolean returnThinking, Boolean sendThinking, Boolean cacheSystemMessages, Boolean cacheTools, Boolean cacheAutomatically,
            String cacheTtl, Boolean disableParallelToolUse, Boolean strictTools, String userId, String version, String beta,
            Boolean logRequests, Boolean logResponses, Map<String, String> customHeaders, Map<String, Object> customParameters) {
    }

    private static Settings read(Map<String, String> p, OutboundTargetPolicy targetPolicy) {
        String baseUrl = stringValue(p, KEY_BASE_URL);
        if (baseUrl != null) {
            targetPolicy.validateUrl(baseUrl);
        }
        // Lenient reads: these values are typed by a user in the Manager, so a
        // stray character is a config mistake, not a reason to fail every
        // conversation this agent serves with an uncaught NumberFormatException.
        Integer maxTokens = intValue(p, KEY_MAX_TOKENS);

        // output_config.effort has no langchain4j setter; it travels as a body field.
        Map<String, Object> body = new LinkedHashMap<>();
        String effort = stringValue(p, KEY_EFFORT);
        if (effort != null) {
            body.put("output_config", Map.of("effort", effort));
        }
        Map<String, Object> userBody = objectMap(p, KEY_CUSTOM_PARAMETERS);
        if (userBody != null) {
            body = ModelParameterValues.deepMerge(body, userBody);
        }

        return new Settings(baseUrl, isNullOrEmpty(p.get(KEY_API_KEY)) ? null : p.get(KEY_API_KEY),
                isNullOrEmpty(p.get(KEY_MODEL_NAME)) ? null : p.get(KEY_MODEL_NAME), maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS,
                longValue(p, KEY_TIMEOUT), doubleValue(p, KEY_TEMPERATURE), doubleValue(p, KEY_TOP_P), intValue(p, KEY_TOP_K),
                stringList(p, KEY_STOP_SEQUENCES), stringValue(p, KEY_THINKING_TYPE), intValue(p, KEY_THINKING_BUDGET_TOKENS),
                stringValue(p, KEY_THINKING_DISPLAY), booleanOrNull(p, KEY_RETURN_THINKING), booleanOrNull(p, KEY_SEND_THINKING),
                booleanOrNull(p, KEY_CACHE_SYSTEM_MESSAGES), booleanOrNull(p, KEY_CACHE_TOOLS), booleanOrNull(p, KEY_CACHE_AUTOMATICALLY),
                stringValue(p, KEY_CACHE_TTL), booleanOrNull(p, KEY_DISABLE_PARALLEL_TOOL_USE), booleanOrNull(p, KEY_STRICT_TOOLS),
                stringValue(p, KEY_USER_ID), stringValue(p, KEY_VERSION), stringValue(p, KEY_BETA), booleanOrNull(p, KEY_LOG_REQUESTS),
                booleanOrNull(p, KEY_LOG_RESPONSES), stringMap(p, KEY_CUSTOM_HEADERS), body.isEmpty() ? null : body);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, BoundedJdkHttpClient.builder());
    }

    /**
     * Seam for the unit tests: a fake {@link HttpClientBuilder} runs langchain4j's
     * real request mapping and shows what reaches the wire. Production goes through
     * {@link #build(Map)}.
     */
    ChatModel build(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = AnthropicChatModel.builder().httpClientBuilder(httpClientBuilder);
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.modelName() != null)
            b.modelName(s.modelName());
        b.maxTokens(s.maxTokens());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.temperature() != null)
            b.temperature(s.temperature());
        if (s.topP() != null)
            b.topP(s.topP());
        if (s.topK() != null)
            b.topK(s.topK());
        if (s.stop() != null)
            b.stopSequences(s.stop());
        if (s.thinkingType() != null)
            b.thinkingType(s.thinkingType());
        if (s.thinkingBudgetTokens() != null)
            b.thinkingBudgetTokens(s.thinkingBudgetTokens());
        if (s.thinkingDisplay() != null)
            b.thinkingDisplay(s.thinkingDisplay());
        if (s.returnThinking() != null)
            b.returnThinking(s.returnThinking());
        if (s.sendThinking() != null)
            b.sendThinking(s.sendThinking());
        if (s.cacheSystemMessages() != null)
            b.cacheSystemMessages(s.cacheSystemMessages());
        if (s.cacheTools() != null)
            b.cacheTools(s.cacheTools());
        if (s.cacheAutomatically() != null)
            b.cacheAutomatically(s.cacheAutomatically());
        if (s.cacheTtl() != null)
            b.cacheTtl(s.cacheTtl());
        if (s.disableParallelToolUse() != null)
            b.disableParallelToolUse(s.disableParallelToolUse());
        if (s.strictTools() != null)
            b.strictTools(s.strictTools());
        if (s.userId() != null)
            b.userId(s.userId());
        if (s.version() != null)
            b.version(s.version());
        if (s.beta() != null)
            b.beta(s.beta());
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        if (s.customParameters() != null)
            b.customParameters(s.customParameters());
        // executeWithRetry is the single retry owner; library retries multiply it.
        b.maxRetries(NO_LIBRARY_RETRIES);
        return b.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return buildStreaming(parameters, BoundedJdkHttpClient.builder());
    }

    /** Streaming counterpart of {@link #build(Map, HttpClientBuilder)}. */
    StreamingChatModel buildStreaming(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = AnthropicStreamingChatModel.builder().httpClientBuilder(httpClientBuilder);
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.modelName() != null)
            b.modelName(s.modelName());
        b.maxTokens(s.maxTokens());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.temperature() != null)
            b.temperature(s.temperature());
        if (s.topP() != null)
            b.topP(s.topP());
        if (s.topK() != null)
            b.topK(s.topK());
        if (s.stop() != null)
            b.stopSequences(s.stop());
        if (s.thinkingType() != null)
            b.thinkingType(s.thinkingType());
        if (s.thinkingBudgetTokens() != null)
            b.thinkingBudgetTokens(s.thinkingBudgetTokens());
        if (s.thinkingDisplay() != null)
            b.thinkingDisplay(s.thinkingDisplay());
        if (s.returnThinking() != null)
            b.returnThinking(s.returnThinking());
        if (s.sendThinking() != null)
            b.sendThinking(s.sendThinking());
        if (s.cacheSystemMessages() != null)
            b.cacheSystemMessages(s.cacheSystemMessages());
        if (s.cacheTools() != null)
            b.cacheTools(s.cacheTools());
        if (s.cacheAutomatically() != null)
            b.cacheAutomatically(s.cacheAutomatically());
        if (s.cacheTtl() != null)
            b.cacheTtl(s.cacheTtl());
        if (s.disableParallelToolUse() != null)
            b.disableParallelToolUse(s.disableParallelToolUse());
        if (s.strictTools() != null)
            b.strictTools(s.strictTools());
        if (s.userId() != null)
            b.userId(s.userId());
        if (s.version() != null)
            b.version(s.version());
        if (s.beta() != null)
            b.beta(s.beta());
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        if (s.customParameters() != null)
            b.customParameters(s.customParameters());
        return b.build();
    }
}
