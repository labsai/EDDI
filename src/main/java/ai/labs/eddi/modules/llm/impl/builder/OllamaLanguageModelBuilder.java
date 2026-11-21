/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
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
 * Ollama builder — the default local provider the setup wizard steers users
 * towards.
 * <p>
 * The sampling parameters use the same key names as every other builder
 * ({@code temperature}, {@code maxTokens}, {@code topP}, {@code topK}) even
 * though Ollama's own wire name for the output cap is {@code num_predict}: an
 * agent designer switching a task between providers must not have to rename
 * parameters. While these keys were unread, a configured {@code temperature}
 * silently did nothing on the one provider most likely to be a user's first
 * experience of EDDI.
 */
@ApplicationScoped
public class OllamaLanguageModelBuilder implements ILanguageModelBuilder {

    /**
     * Applies {@code eddi.security.ssrf-protection.enabled} to the configured
     * endpoint. Field-injected; null in tests built with {@code new}, which then
     * validate as with protection off (metadata check only).
     */
    @Inject
    OutboundTargetPolicy targetPolicy;
    private static final String KEY_MODEL = "model";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TOP_P = "topP";
    private static final String KEY_TOP_K = "topK";

    /**
     * Ollama's own {@code think} switch — tri-state on purpose.
     * <p>
     * {@code "true"} asks the model to reason and return the reasoning in a
     * separate {@code thinking} field, which langchain4j discards unless
     * {@link #KEY_RETURN_THINKING} is also on. {@code "false"} turns reasoning off.
     * <em>Unset</em> leaves it to Ollama and the model, and the two outcomes differ
     * in where the reasoning ends up: a model that reports it separately makes the
     * chat window sit silent for as long as it thinks (the reasoning is not part of
     * the streamed content, and nothing renders until the answer starts), while an
     * older reasoning model instead prepends {@code <think>…</think>} to the
     * content itself, so the tags stream into the reply where the user can see
     * them.
     * <p>
     * Either way it is the first thing that makes a local reasoning model (gemma3n,
     * deepseek-r1, qwen3 …) look broken. {@code think: "false"} makes such a model
     * answer immediately.
     */
    private static final String KEY_THINK = "think";

    /**
     * Whether the separate {@code thinking} field is parsed and surfaced rather
     * than discarded. Independent of {@link #KEY_THINK} — it does not enable
     * reasoning, and it has no effect on reasoning a model embedded in the ordinary
     * content, which is not a {@code thinking} field to parse. Off by default, so
     * reasoning stays out of the transcript unless asked for.
     */
    private static final String KEY_RETURN_THINKING = "returnThinking";

    static final String KEY_NUM_CTX = "numCtx";
    static final String KEY_MIN_P = "minP";
    static final String KEY_REPEAT_PENALTY = "repeatPenalty";
    static final String KEY_REPEAT_LAST_N = "repeatLastN";
    static final String KEY_SEED = "seed";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_TRUNCATE = "truncate";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL, KEY_TIMEOUT, KEY_LOG_REQUESTS, KEY_LOG_RESPONSES, KEY_BASE_URL,
                KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_TOP_P, KEY_TOP_K, KEY_THINK, KEY_RETURN_THINKING, KEY_NUM_CTX,
                KEY_MIN_P, KEY_REPEAT_PENALTY, KEY_REPEAT_LAST_N, KEY_SEED, KEY_STOP_SEQUENCES, KEY_TRUNCATE,
                KEY_CUSTOM_HEADERS);
    }

    /**
     * Ollama's own default address. langchain4j has no default and refuses to build
     * without {@code baseUrl}, so nothing is sent here today; it is named anyway,
     * so a secret restricted elsewhere fails the host check rather than depending
     * on that.
     */
    static final String DEFAULT_ENDPOINT = "http://localhost:11434";

    @Override
    public Set<String> secretParameters() {
        return Set.of(KEY_CUSTOM_HEADERS);
    }

    @Override
    public String defaultEndpoint(Map<String, String> parameters) {
        return ILanguageModelBuilder.unlessSet(parameters, KEY_BASE_URL, DEFAULT_ENDPOINT);
    }

    /**
     * Every setting the builders read; {@code null} leaves the provider default.
     * The request-level ones travel as one {@link OllamaChatRequestParameters},
     * handed to the synchronous and the streaming client alike.
     */
    private record Settings(String baseUrl, Long timeoutMs, Boolean returnThinking, Boolean logRequests, Boolean logResponses,
            Map<String, String> customHeaders, OllamaChatRequestParameters requestParameters) {
    }

    private static Settings read(Map<String, String> p, OutboundTargetPolicy targetPolicy) {
        String baseUrl = stringValue(p, KEY_BASE_URL);
        if (baseUrl != null) {
            // Never the cloud instance-metadata service; with
            // eddi.security.ssrf-protection.enabled no private address either.
            targetPolicy.validateUrl(baseUrl);
        }
        var requestParameters = OllamaChatRequestParameters.builder()
                .modelName(isNullOrEmpty(p.get(KEY_MODEL)) ? null : p.get(KEY_MODEL)).temperature(doubleValue(p, KEY_TEMPERATURE))
                .maxOutputTokens(intValue(p, KEY_MAX_TOKENS)).topP(doubleValue(p, KEY_TOP_P)).topK(intValue(p, KEY_TOP_K))
                .numCtx(intValue(p, KEY_NUM_CTX)).minP(doubleValue(p, KEY_MIN_P)).repeatPenalty(doubleValue(p, KEY_REPEAT_PENALTY))
                .repeatLastN(intValue(p, KEY_REPEAT_LAST_N)).seed(intValue(p, KEY_SEED)).stopSequences(stringList(p, KEY_STOP_SEQUENCES))
                .truncate(booleanOrNull(p, KEY_TRUNCATE)).think(booleanOrNull(p, KEY_THINK)).build();
        return new Settings(baseUrl, longValue(p, KEY_TIMEOUT), booleanOrNull(p, KEY_RETURN_THINKING), booleanOrNull(p, KEY_LOG_REQUESTS),
                booleanOrNull(p, KEY_LOG_RESPONSES), stringMap(p, KEY_CUSTOM_HEADERS), requestParameters);
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
        var b = OllamaChatModel.builder().httpClientBuilder(httpClientBuilder).defaultRequestParameters(s.requestParameters());
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.returnThinking() != null)
            b.returnThinking(s.returnThinking());
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
        var b = OllamaStreamingChatModel.builder().httpClientBuilder(httpClientBuilder).defaultRequestParameters(s.requestParameters());
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.returnThinking() != null)
            b.returnThinking(s.returnThinking());
        if (s.logRequests() != null)
            b.logRequests(s.logRequests());
        if (s.logResponses() != null)
            b.logResponses(s.logResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        return b.build();
    }
}
