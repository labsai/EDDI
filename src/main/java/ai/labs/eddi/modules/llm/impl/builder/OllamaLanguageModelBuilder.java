/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyLong;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyStringList;
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

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, JdkHttpClient.builder());
    }

    /**
     * Seam for the unit tests: a fake {@link HttpClientBuilder} shows what reaches
     * the wire. Production goes through {@link #build(Map)}.
     */
    ChatModel build(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        var builder = OllamaChatModel.builder().httpClientBuilder(httpClientBuilder);
        applyTo(builder, parameters);

        // executeWithRetry is the single retry owner; library retries multiply it.
        builder.maxRetries(NO_LIBRARY_RETRIES);

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = OllamaStreamingChatModel.builder().httpClientBuilder(JdkHttpClient.builder());
        applyTo(builder, parameters);
        return builder.build();
    }

    /**
     * The synchronous and the streaming client are configured by identical bodies:
     * their common base builder ({@code OllamaBaseChatModel.Builder}) is
     * package-private in langchain4j, so it cannot be named here. Change both
     * together.
     */
    private static void applyTo(OllamaChatModel.OllamaChatModelBuilder builder, Map<String, String> parameters) {
        String baseUrl = stringValue(parameters, KEY_BASE_URL);
        if (baseUrl != null) {
            // Never let a model base URL point at the cloud instance-metadata service
            // (always-on, independent of eddi.security.ssrf-protection.enabled).
            UrlValidationUtils.rejectCloudMetadataTarget(baseUrl);
            builder.baseUrl(baseUrl);
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_TOKENS, builder::numPredict);
        applyDouble(parameters, KEY_TOP_P, builder::topP);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyInt(parameters, KEY_NUM_CTX, builder::numCtx);
        applyDouble(parameters, KEY_MIN_P, builder::minP);
        applyDouble(parameters, KEY_REPEAT_PENALTY, builder::repeatPenalty);
        applyInt(parameters, KEY_REPEAT_LAST_N, builder::repeatLastN);
        applyInt(parameters, KEY_SEED, builder::seed);
        applyStringList(parameters, KEY_STOP_SEQUENCES, builder::stop);
        applyBoolean(parameters, KEY_TRUNCATE, builder::truncate);
        applyBoolean(parameters, KEY_THINK, builder::think);
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        applyBoolean(parameters, KEY_LOG_REQUESTS, builder::logRequests);
        applyBoolean(parameters, KEY_LOG_RESPONSES, builder::logResponses);
        Map<String, String> headers = stringMap(parameters, KEY_CUSTOM_HEADERS);
        if (headers != null) {
            builder.customHeaders(headers);
        }
    }

    /**
     * Streaming twin of
     * {@link #applyTo(OllamaChatModel.OllamaChatModelBuilder, Map)}; keep
     * identical.
     */
    private static void applyTo(OllamaStreamingChatModel.OllamaStreamingChatModelBuilder builder, Map<String, String> parameters) {
        String baseUrl = stringValue(parameters, KEY_BASE_URL);
        if (baseUrl != null) {
            // Never let a model base URL point at the cloud instance-metadata service
            // (always-on, independent of eddi.security.ssrf-protection.enabled).
            UrlValidationUtils.rejectCloudMetadataTarget(baseUrl);
            builder.baseUrl(baseUrl);
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_TOKENS, builder::numPredict);
        applyDouble(parameters, KEY_TOP_P, builder::topP);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyInt(parameters, KEY_NUM_CTX, builder::numCtx);
        applyDouble(parameters, KEY_MIN_P, builder::minP);
        applyDouble(parameters, KEY_REPEAT_PENALTY, builder::repeatPenalty);
        applyInt(parameters, KEY_REPEAT_LAST_N, builder::repeatLastN);
        applyInt(parameters, KEY_SEED, builder::seed);
        applyStringList(parameters, KEY_STOP_SEQUENCES, builder::stop);
        applyBoolean(parameters, KEY_TRUNCATE, builder::truncate);
        applyBoolean(parameters, KEY_THINK, builder::think);
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        applyBoolean(parameters, KEY_LOG_REQUESTS, builder::logRequests);
        applyBoolean(parameters, KEY_LOG_RESPONSES, builder::logResponses);
        Map<String, String> headers = stringMap(parameters, KEY_CUSTOM_HEADERS);
        if (headers != null) {
            builder.customHeaders(headers);
        }
    }
}
