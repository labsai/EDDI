/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyLong;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class GeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    private static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String KEY_ALLOW_CODE_EXECUTION = "allowCodeExecution";
    private static final String KEY_TIMEOUT = "timeout";
    static final String KEY_RETURN_THINKING = "returnThinking";
    static final String KEY_SEND_THINKING = "sendThinking";

    /**
     * Both thinking flags default to {@code true}, the opposite of langchain4j's
     * defaults, because without them <strong>no Gemini 3.x model can use
     * tools</strong>.
     * <p>
     * Gemini 3.x attaches an opaque {@code thoughtSignature} to each
     * {@code functionCall} part and requires it echoed back on the follow-up
     * request carrying the {@code functionResponse}; otherwise
     * {@code 400 INVALID_ARGUMENT — Function call is missing a thought_signature}.
     * No thinking budget avoids it — the signature is emitted and demanded even at
     * {@code thinkingBudget = 0}. langchain4j 1.20.0 models the field but gates it:
     * {@code PartsAndContentsMapper} captures it into
     * {@code AiMessage.attributes()} only when {@code returnThinking} is
     * {@code TRUE}, and re-sends it only when {@code sendThinking} is {@code true}.
     * Both halves are needed.
     * <p>
     * <b>Re-check before exposing {@code thinkingConfig}.</b> The mapper collects a
     * {@code thoughtSignature} from <em>every</em> part, joins them with
     * {@code "\n\n"} into one attribute, and re-sends that on the first
     * {@code functionCall} part — faithful only while a turn carries exactly one
     * signed part. Measured once against the live API (Gemini 3.8/3.5 Flash), it
     * does: parallel calls sign only part 0, and a narrating text part is unsigned.
     * Thought parts, which appear only with a {@code thinkingConfig} (not exposed
     * here), could add more, and the joined value would then not be what the model
     * issued.
     * <p>
     * Overridable, since it is configuration, but an explicit {@code "false"} is
     * <em>not</em> the pre-fix state: EDDI used to leave {@code returnThinking}
     * unset, which langchain4j treats as "prepend any thought text to
     * {@code text()}", whereas {@code false} drops thought text. The difference is
     * unobservable today — this builder exposes no {@code thinkingConfig}, so
     * Gemini returns no thought text parts, and {@code true} merely routes them to
     * {@code AiMessage.thinking()} if it ever did. Either flag set to {@code false}
     * breaks tool calling on Gemini 3.x.
     *
     * @see <a href=
     *      "https://ai.google.dev/gemini-api/docs/thought-signatures">thought
     *      signatures</a>
     */
    private static final boolean THINKING_DEFAULT = true;

    // NOTE: responseFormat is intentionally NOT set at the model builder level.
    // Gemini does not support combining responseFormat=JSON (responseMimeType=
    // application/json) with function calling (tools). Setting it here would
    // cause "Function calling with a response mime type: 'application/json' is
    // unsupported" errors whenever tools are enabled.
    // JSON response format is enforced at the REQUEST level by LegacyChatExecutor,
    // which only applies it when no tools are present.

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_API_KEY, KEY_MODEL_NAME, KEY_TEMPERATURE, KEY_LOG_REQUESTS_AND_RESPONSES,
                KEY_MAX_OUTPUT_TOKENS, KEY_ALLOW_CODE_EXECUTION, KEY_TIMEOUT, KEY_RETURN_THINKING, KEY_SEND_THINKING);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, JdkHttpClient.builder());
    }

    /**
     * Seam for the round-trip test. The HTTP client is the only place the effect of
     * these two flags is observable — asserting on the flags themselves would just
     * restate this code, while a fake
     * {@link dev.langchain4j.http.client.HttpClient} runs langchain4j's real
     * request/response mapping and shows whether the signature reaches the wire.
     * Production goes through {@link #build(Map)}.
     */
    ChatModel build(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        var builder = GoogleAiGeminiChatModel.builder().httpClientBuilder(httpClientBuilder);

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_OUTPUT_TOKENS, builder::maxOutputTokens);
        if (!isNullOrEmpty(parameters.get(KEY_ALLOW_CODE_EXECUTION))) {
            builder.allowCodeExecution(Boolean.parseBoolean(parameters.get(KEY_ALLOW_CODE_EXECUTION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        builder.returnThinking(booleanValue(parameters, KEY_RETURN_THINKING, THINKING_DEFAULT));
        builder.sendThinking(booleanValue(parameters, KEY_SEND_THINKING, THINKING_DEFAULT));

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        return buildStreaming(parameters, JdkHttpClient.builder());
    }

    /** Streaming counterpart of {@link #build(Map, HttpClientBuilder)}. */
    StreamingChatModel buildStreaming(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        var builder = GoogleAiGeminiStreamingChatModel.builder().httpClientBuilder(httpClientBuilder);

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_OUTPUT_TOKENS, builder::maxOutputTokens);
        if (!isNullOrEmpty(parameters.get(KEY_ALLOW_CODE_EXECUTION))) {
            builder.allowCodeExecution(Boolean.parseBoolean(parameters.get(KEY_ALLOW_CODE_EXECUTION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        builder.returnThinking(booleanValue(parameters, KEY_RETURN_THINKING, THINKING_DEFAULT));
        builder.sendThinking(booleanValue(parameters, KEY_SEND_THINKING, THINKING_DEFAULT));

        return builder.build();
    }
}
