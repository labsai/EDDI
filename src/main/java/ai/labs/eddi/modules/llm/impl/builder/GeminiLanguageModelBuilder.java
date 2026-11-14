/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.utils.LogSanitizer;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GeminiHarmBlockThreshold;
import dev.langchain4j.model.googleai.GeminiHarmCategory;
import dev.langchain4j.model.googleai.GeminiMediaResolutionLevel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatRequestParameters;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.objectMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class GeminiLanguageModelBuilder implements ILanguageModelBuilder {

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
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    private static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String KEY_ALLOW_CODE_EXECUTION = "allowCodeExecution";
    private static final String KEY_TIMEOUT = "timeout";
    static final String KEY_RETURN_THINKING = "returnThinking";
    static final String KEY_SEND_THINKING = "sendThinking";
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_TOP_P = "topP";
    static final String KEY_TOP_K = "topK";
    static final String KEY_SEED = "seed";
    static final String KEY_FREQUENCY_PENALTY = "frequencyPenalty";
    static final String KEY_PRESENCE_PENALTY = "presencePenalty";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_THINKING_LEVEL = "thinkingLevel";
    static final String KEY_THINKING_BUDGET = "thinkingBudget";
    static final String KEY_ALLOW_GOOGLE_SEARCH = "allowGoogleSearch";
    static final String KEY_ALLOW_GOOGLE_MAPS = "allowGoogleMaps";
    static final String KEY_ALLOW_URL_CONTEXT = "allowUrlContext";
    static final String KEY_SAFETY_SETTINGS = "safetySettings";
    static final String KEY_MEDIA_RESOLUTION = "mediaResolution";
    static final String KEY_CACHED_CONTENT = "cachedContentName";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    private static final Logger LOGGER = Logger.getLogger(GeminiLanguageModelBuilder.class);

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
                KEY_MAX_OUTPUT_TOKENS, KEY_ALLOW_CODE_EXECUTION, KEY_TIMEOUT, KEY_RETURN_THINKING, KEY_SEND_THINKING,
                KEY_BASE_URL, KEY_TOP_P, KEY_TOP_K, KEY_SEED, KEY_FREQUENCY_PENALTY, KEY_PRESENCE_PENALTY,
                KEY_STOP_SEQUENCES, KEY_THINKING_LEVEL, KEY_THINKING_BUDGET, KEY_ALLOW_GOOGLE_SEARCH,
                KEY_ALLOW_GOOGLE_MAPS, KEY_ALLOW_URL_CONTEXT, KEY_SAFETY_SETTINGS, KEY_MEDIA_RESOLUTION,
                KEY_CACHED_CONTENT, KEY_CUSTOM_HEADERS);
    }

    /** langchain4j's endpoint when no {@code baseUrl} is configured. */
    static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta";

    @Override
    public Set<String> secretParameters() {
        return Set.of(KEY_API_KEY, KEY_CUSTOM_HEADERS);
    }

    @Override
    public String defaultEndpoint(Map<String, String> parameters) {
        return ILanguageModelBuilder.unlessSet(parameters, KEY_BASE_URL, DEFAULT_ENDPOINT);
    }

    /**
     * Every setting the builders read; {@code null} leaves the provider default.
     * The request-level ones travel as one
     * {@link GoogleAiGeminiChatRequestParameters}, handed to the synchronous and
     * the streaming client alike; the rest are options only the client builders
     * take.
     */
    private record Settings(String apiKey, String baseUrl, Long timeoutMs, Boolean logRequestsAndResponses,
            Boolean allowCodeExecution, boolean returnThinking, boolean sendThinking, Integer seed, GeminiThinkingConfig thinkingConfig,
            Boolean allowGoogleSearch, Boolean allowGoogleMaps, Boolean allowUrlContext,
            Map<GeminiHarmCategory, GeminiHarmBlockThreshold> safetySettings, GeminiMediaResolutionLevel mediaResolution,
            Map<String, String> customHeaders, GoogleAiGeminiChatRequestParameters requestParameters) {
    }

    /**
     * {@code includeThoughts} is deliberately not offered: thought parts are what
     * the signature note above warns about, so
     * {@code thinkingLevel}/{@code thinkingBudget} steer how hard the model thinks
     * without ever asking for the thoughts back.
     */
    private static Settings read(Map<String, String> p, OutboundTargetPolicy targetPolicy) {
        String baseUrl = stringValue(p, KEY_BASE_URL);
        if (baseUrl != null) {
            targetPolicy.validateUrl(baseUrl);
        }

        String level = ModelParameterValues.enumValue(p, KEY_THINKING_LEVEL, List.of("minimal", "low", "medium", "high"));
        Integer budget = intValue(p, KEY_THINKING_BUDGET);
        if (level != null && budget != null) {
            LOGGER.warn("Gemini thinkingLevel and thinkingBudget are mutually exclusive - using thinkingLevel.");
            budget = null;
        }
        GeminiThinkingConfig thinkingConfig = null;
        if (level != null || budget != null) {
            var thinking = GeminiThinkingConfig.builder();
            if (level != null)
                thinking.thinkingLevel(level);
            if (budget != null)
                thinking.thinkingBudget(budget);
            thinkingConfig = thinking.build();
        }

        GeminiMediaResolutionLevel mediaResolution = null;
        String mediaResolutionName = stringValue(p, KEY_MEDIA_RESOLUTION);
        if (mediaResolutionName != null) {
            mediaResolution = enumConstant(GeminiMediaResolutionLevel.class, "MEDIA_RESOLUTION_", mediaResolutionName);
            if (mediaResolution == null) {
                LOGGER.warnf("Gemini mediaResolution must be one of %s - ignoring it.",
                        Arrays.toString(GeminiMediaResolutionLevel.values()));
            }
        }

        var requestParameters = GoogleAiGeminiChatRequestParameters.builder()
                .modelName(isNullOrEmpty(p.get(KEY_MODEL_NAME)) ? null : p.get(KEY_MODEL_NAME)).temperature(doubleValue(p, KEY_TEMPERATURE))
                .maxOutputTokens(intValue(p, KEY_MAX_OUTPUT_TOKENS)).topP(doubleValue(p, KEY_TOP_P)).topK(intValue(p, KEY_TOP_K))
                .frequencyPenalty(doubleValue(p, KEY_FREQUENCY_PENALTY)).presencePenalty(doubleValue(p, KEY_PRESENCE_PENALTY))
                .stopSequences(stringList(p, KEY_STOP_SEQUENCES)).cachedContentName(stringValue(p, KEY_CACHED_CONTENT)).build();

        return new Settings(isNullOrEmpty(p.get(KEY_API_KEY)) ? null : p.get(KEY_API_KEY), baseUrl, longValue(p, KEY_TIMEOUT),
                isNullOrEmpty(p.get(KEY_LOG_REQUESTS_AND_RESPONSES)) ? null : Boolean.parseBoolean(p.get(KEY_LOG_REQUESTS_AND_RESPONSES)),
                isNullOrEmpty(p.get(KEY_ALLOW_CODE_EXECUTION)) ? null : Boolean.parseBoolean(p.get(KEY_ALLOW_CODE_EXECUTION)),
                booleanValue(p, KEY_RETURN_THINKING, THINKING_DEFAULT), booleanValue(p, KEY_SEND_THINKING, THINKING_DEFAULT),
                intValue(p, KEY_SEED), thinkingConfig, booleanOrNull(p, KEY_ALLOW_GOOGLE_SEARCH), booleanOrNull(p, KEY_ALLOW_GOOGLE_MAPS),
                booleanOrNull(p, KEY_ALLOW_URL_CONTEXT), safetySettings(p), mediaResolution, stringMap(p, KEY_CUSTOM_HEADERS),
                requestParameters);
    }

    /**
     * {@code {"HARASSMENT":"BLOCK_ONLY_HIGH"}}: category names with or without the
     * {@code HARM_CATEGORY_} prefix, thresholds as the enum name. Entries that do
     * not resolve are dropped with a warning rather than failing the model build.
     */
    static Map<GeminiHarmCategory, GeminiHarmBlockThreshold> safetySettings(Map<String, String> p) {
        Map<String, Object> raw = objectMap(p, KEY_SAFETY_SETTINGS);
        if (raw == null) {
            return null;
        }
        Map<GeminiHarmCategory, GeminiHarmBlockThreshold> result = new LinkedHashMap<>();
        raw.forEach((category, threshold) -> {
            GeminiHarmCategory c = enumConstant(GeminiHarmCategory.class, "HARM_CATEGORY_", category);
            GeminiHarmBlockThreshold t = enumConstant(GeminiHarmBlockThreshold.class, "", String.valueOf(threshold));
            if (c != null && t != null) {
                result.put(c, t);
            } else {
                LOGGER.warnf("Gemini safetySettings entry for %s is not a valid category/threshold pair - skipped.",
                        LogSanitizer.sanitize(category));
            }
        });
        return result.isEmpty() ? null : result;
    }

    /**
     * The constant of {@code type} named {@code name} (trimmed, upper-cased,
     * {@code -} and space read as {@code _}), with or without {@code prefix};
     * {@code null} when none matches. Shared with
     * {@link VertexGeminiLanguageModelBuilder}, whose safety settings take the same
     * JSON shape.
     */
    static <E extends Enum<E>> E enumConstant(Class<E> type, String prefix, String name) {
        String wanted = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E candidate : type.getEnumConstants()) {
            String constant = candidate.name();
            if (constant.equals(wanted) || constant.equals(prefix + wanted)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return build(parameters, BoundedJdkHttpClient.builder(targetPolicy));
    }

    /**
     * Seam for the round-trip test. The HTTP client is the only place the effect of
     * these two flags is observable — asserting on the flags themselves would just
     * restate this code, while a fake {@link HttpClient} runs langchain4j's real
     * request/response mapping and shows whether the signature reaches the wire.
     * Production goes through {@link #build(Map)}.
     */
    ChatModel build(Map<String, String> parameters, HttpClientBuilder httpClientBuilder) {
        Settings s = read(parameters, OutboundTargetPolicy.orPermissive(targetPolicy));
        var b = GoogleAiGeminiChatModel.builder().httpClientBuilder(httpClientBuilder).defaultRequestParameters(s.requestParameters());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequestsAndResponses() != null)
            b.logRequestsAndResponses(s.logRequestsAndResponses());
        if (s.allowCodeExecution() != null)
            b.allowCodeExecution(s.allowCodeExecution());
        b.returnThinking(s.returnThinking());
        b.sendThinking(s.sendThinking());
        if (s.seed() != null)
            b.seed(s.seed());
        if (s.thinkingConfig() != null)
            b.thinkingConfig(s.thinkingConfig());
        if (s.allowGoogleSearch() != null)
            b.allowGoogleSearch(s.allowGoogleSearch());
        if (s.allowGoogleMaps() != null)
            b.allowGoogleMaps(s.allowGoogleMaps());
        if (s.allowUrlContext() != null)
            b.allowUrlContext(s.allowUrlContext());
        if (s.safetySettings() != null)
            b.safetySettings(s.safetySettings());
        if (s.mediaResolution() != null)
            b.mediaResolution(s.mediaResolution());
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
        var b = GoogleAiGeminiStreamingChatModel.builder().httpClientBuilder(httpClientBuilder)
                .defaultRequestParameters(s.requestParameters());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.baseUrl() != null)
            b.baseUrl(s.baseUrl());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequestsAndResponses() != null)
            b.logRequestsAndResponses(s.logRequestsAndResponses());
        if (s.allowCodeExecution() != null)
            b.allowCodeExecution(s.allowCodeExecution());
        b.returnThinking(s.returnThinking());
        b.sendThinking(s.sendThinking());
        if (s.seed() != null)
            b.seed(s.seed());
        if (s.thinkingConfig() != null)
            b.thinkingConfig(s.thinkingConfig());
        if (s.allowGoogleSearch() != null)
            b.allowGoogleSearch(s.allowGoogleSearch());
        if (s.allowGoogleMaps() != null)
            b.allowGoogleMaps(s.allowGoogleMaps());
        if (s.allowUrlContext() != null)
            b.allowUrlContext(s.allowUrlContext());
        if (s.safetySettings() != null)
            b.safetySettings(s.safetySettings());
        if (s.mediaResolution() != null)
            b.mediaResolution(s.mediaResolution());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());
        return b.build();
    }
}
