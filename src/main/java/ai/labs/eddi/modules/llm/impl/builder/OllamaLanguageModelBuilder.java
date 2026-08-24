/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

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
     * separate {@code thinking} field; {@code "false"} turns reasoning off;
     * <em>unset</em> leaves the model's own default, which for a reasoning model
     * (gemma3n, deepseek-r1, qwen3 …) means it thinks. That default is what makes a
     * first Ollama agent look broken: streaming a reasoning model emits a long run
     * of chunks whose {@code content} is empty and whose {@code thinking} carries
     * the text, so the chat window sits silent for many seconds before the answer
     * appears all at once. Setting {@code think: "false"} makes such a model answer
     * immediately.
     */
    private static final String KEY_THINK = "think";

    /**
     * Whether the reasoning text is surfaced rather than discarded. Independent of
     * {@link #KEY_THINK} — it only controls parsing of the {@code thinking} field
     * the server sends, so it is off by default and reasoning stays out of the
     * conversation transcript unless asked for.
     */
    private static final String KEY_RETURN_THINKING = "returnThinking";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL, KEY_TIMEOUT, KEY_LOG_REQUESTS, KEY_LOG_RESPONSES, KEY_BASE_URL,
                KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_TOP_P, KEY_TOP_K, KEY_THINK, KEY_RETURN_THINKING);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = OllamaChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_BASE_URL))) {
            builder.baseUrl(parameters.get(KEY_BASE_URL));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_TOKENS, builder::numPredict);
        applyDouble(parameters, KEY_TOP_P, builder::topP);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyBoolean(parameters, KEY_THINK, builder::think);
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = OllamaStreamingChatModel.builder().httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_BASE_URL))) {
            builder.baseUrl(parameters.get(KEY_BASE_URL));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_TOKENS, builder::numPredict);
        applyDouble(parameters, KEY_TOP_P, builder::topP);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyBoolean(parameters, KEY_THINK, builder::think);
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }
}
