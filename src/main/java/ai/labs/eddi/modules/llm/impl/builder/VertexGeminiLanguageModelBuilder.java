/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.HarmCategory;
import dev.langchain4j.model.vertexai.gemini.SafetyThreshold;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyStringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.objectMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class VertexGeminiLanguageModelBuilder implements ILanguageModelBuilder {

    /**
     * Applies {@code eddi.security.ssrf-protection.enabled} to the configured
     * endpoint. Field-injected; null in tests built with {@code new}, which then
     * validate as with protection off (metadata check only).
     */
    @Inject
    OutboundTargetPolicy targetPolicy;
    private static final String KEY_PUBLISHER = "publisher";
    /**
     * Spelled exactly {@code modelId} — the same key Bedrock and HuggingFace use
     * and the one {@code LlmTask.resolveModelName} falls back to. While this
     * constant read {@code "modelID"} a gemini-vertex task built a model with no
     * name at all AND resolved a null model name downstream, so capability lookup,
     * token estimation and audit-ledger model naming all lost the model identity.
     */
    private static final String KEY_MODEL_ID = "modelId";
    /**
     * The pre-6.2.0 spelling. Configs written against the old constant read this
     * key and worked; dropping it outright would build a nameless model for every
     * stored gemini-vertex config that used it, so it stays readable (and declared,
     * so it is not also reported as an unrecognised parameter) with a deprecation
     * warning.
     */
    static final String KEY_MODEL_ID_LEGACY = "modelID";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_LOCATION = "location";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    static final String KEY_TOP_P = "topP";
    static final String KEY_TOP_K = "topK";
    static final String KEY_SEED = "seed";
    static final String KEY_SAFETY_SETTINGS = "safetySettings";
    static final String KEY_USE_GOOGLE_SEARCH = "useGoogleSearch";
    static final String KEY_LABELS = "labels";
    static final String KEY_API_ENDPOINT = "apiEndpoint";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    private static final Logger LOGGER = Logger.getLogger(VertexGeminiLanguageModelBuilder.class);

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_PUBLISHER, KEY_MODEL_ID, KEY_MODEL_ID_LEGACY, KEY_TEMPERATURE, KEY_PROJECT_ID, KEY_LOCATION,
                KEY_LOG_REQUESTS, KEY_LOG_RESPONSES, KEY_MAX_OUTPUT_TOKENS, KEY_TOP_P, KEY_TOP_K, KEY_SEED,
                KEY_SAFETY_SETTINGS, KEY_USE_GOOGLE_SEARCH, KEY_LABELS, KEY_API_ENDPOINT, KEY_CUSTOM_HEADERS);
    }

    /**
     * The configured model name, preferring the canonical {@code modelId} and
     * falling back to the legacy {@code modelID} spelling. Returns {@code null}
     * when neither is set.
     */
    static String resolveModelId(Map<String, String> parameters) {
        if (parameters == null) {
            return null;
        }
        String modelId = parameters.get(KEY_MODEL_ID);
        if (!isNullOrEmpty(modelId)) {
            return modelId;
        }
        String legacy = parameters.get(KEY_MODEL_ID_LEGACY);
        if (!isNullOrEmpty(legacy)) {
            LOGGER.warnf("gemini-vertex parameter '%s' is deprecated — rename it to '%s'. "
                    + "The legacy spelling is still honoured for now.", KEY_MODEL_ID_LEGACY, KEY_MODEL_ID);
            return legacy;
        }
        return null;
    }

    /**
     * Model ids whose major version is 3 or above — {@code gemini-3-pro},
     * {@code gemini-3.8-flash} and anything later, bare or in Vertex's resource
     * form ({@code publishers/google/models/gemini-3-pro},
     * {@code projects/.../models/gemini-3-pro}). Two or more leading digits also
     * match, so a future {@code gemini-10-*} is not read as version 1.
     */
    private static final Pattern GEMINI_3_OR_LATER = Pattern
            .compile("(?i)^(?:.*/)?gemini-(?:[3-9]|\\d{2,})(?:[.\\-][^/]*)?$");

    /**
     * Whether {@code modelId} names Gemini 3.x or later — the models that cannot
     * use tools on this provider.
     * <p>
     * Gemini 3.x requires the opaque {@code thoughtSignature} on each
     * {@code functionCall} echoed back on the follow-up request. The {@code gemini}
     * provider now does that; this one cannot, and not through any EDDI setting:
     * <ul>
     * <li>{@code langchain4j-vertex-ai-gemini:1.20.0-beta30} does not model the
     * field — no {@code thought}, {@code thinking} or {@code signature} anywhere in
     * it, and {@code VertexAiGeminiChatModel} builds responses with
     * {@code AiMessage.from(...)}, which has no attributes channel.</li>
     * <li>The transport cannot carry it: that module pins
     * {@code com.google.cloud:google-cloud-vertexai:1.27.0}, and the generated
     * {@code com.google.cloud.vertexai.api.Part} it uses — from the transitive
     * {@code com.google.api.grpc:proto-google-cloud-vertexai-v1:1.27.0} — has no
     * {@code thought_signature} field.</li>
     * </ul>
     * So the build only warns, naming the alternative. A warning rather than a
     * failure: a Gemini 3.x agent without tools works here.
     */
    static boolean isGemini3OrLater(String modelId) {
        return modelId != null && GEMINI_3_OR_LATER.matcher(modelId.trim()).matches();
    }

    /** @see #isGemini3OrLater(String) */
    static void warnIfGemini3(String modelId) {
        if (isGemini3OrLater(modelId)) {
            LOGGER.warnf("gemini-vertex model '%s' is Gemini 3.x or later, which cannot use tools on this provider: "
                    + "Gemini 3.x requires the thoughtSignature it attaches to each functionCall echoed back on the "
                    + "follow-up request, and neither langchain4j's vertex-ai-gemini module nor the Vertex AI Part "
                    + "protobuf it depends on can carry that field. Tool calls will fail with "
                    + "'400 INVALID_ARGUMENT — Function call is missing a thought_signature'. Use the 'gemini' "
                    + "provider for Gemini 3.x with tools; 'gemini-vertex' supports Gemini 2.x. "
                    + "See https://ai.google.dev/gemini-api/docs/thought-signatures", sanitize(modelId));
        }
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = applyTo(VertexAiGeminiChatModel.builder(), parameters, OutboundTargetPolicy.orPermissive(targetPolicy));

        // executeWithRetry is the single retry owner; library retries multiply it.
        builder.maxRetries(NO_LIBRARY_RETRIES);

        return builder.build();
    }

    /**
     * Applies the configured parameters to a Vertex builder and hands it back
     * without building it: {@code build()} constructs a {@code VertexAI} client,
     * which needs GCP credentials, so the unit tests assert on the populated
     * builder instead.
     * <p>
     * Only the synchronous client exists for this provider. Should a streaming
     * client be added: langchain4j's {@code VertexAiGeminiStreamingChatModel}
     * builder has no {@code seed} setter (1.22.0-beta32).
     */
    static VertexAiGeminiChatModel.VertexAiGeminiChatModelBuilder applyTo(VertexAiGeminiChatModel.VertexAiGeminiChatModelBuilder builder,
                                                                          Map<String, String> parameters) {
        return applyTo(builder, parameters, OutboundTargetPolicy.permissive());
    }

    static VertexAiGeminiChatModel.VertexAiGeminiChatModelBuilder applyTo(VertexAiGeminiChatModel.VertexAiGeminiChatModelBuilder builder,
                                                                          Map<String, String> parameters, OutboundTargetPolicy targetPolicy) {

        if (!isNullOrEmpty(parameters.get(KEY_PROJECT_ID))) {
            builder.project(parameters.get(KEY_PROJECT_ID));
        }

        // Core langchain4j uses location() instead of publisher();
        // "location" param takes priority, fallback to "publisher" for backward compat
        if (!isNullOrEmpty(parameters.get(KEY_LOCATION))) {
            builder.location(parameters.get(KEY_LOCATION));
        } else if (!isNullOrEmpty(parameters.get(KEY_PUBLISHER))) {
            builder.location(parameters.get(KEY_PUBLISHER));
        }

        String modelId = resolveModelId(parameters);
        if (!isNullOrEmpty(modelId)) {
            builder.modelName(modelId);
        }
        warnIfGemini3(modelId);

        // Parsed as a double and narrowed: these setters take a float, and a separate
        // float helper would buy nothing — every value a float accepts, a double
        // accepts too.
        applyDouble(parameters, KEY_TEMPERATURE, temperature -> builder.temperature((float) temperature));
        applyDouble(parameters, KEY_TOP_P, topP -> builder.topP((float) topP));
        applyInt(parameters, KEY_MAX_OUTPUT_TOKENS, builder::maxOutputTokens);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyInt(parameters, KEY_SEED, builder::seed);
        applyBoolean(parameters, KEY_USE_GOOGLE_SEARCH, builder::useGoogleSearch);
        applyBoolean(parameters, KEY_LOG_REQUESTS, builder::logRequests);
        applyBoolean(parameters, KEY_LOG_RESPONSES, builder::logResponses);

        Map<HarmCategory, SafetyThreshold> safety = safetySettings(parameters);
        if (safety != null) {
            builder.safetySettings(safety);
        }
        applyStringMap(parameters, KEY_LABELS, builder::labels);
        applyStringMap(parameters, KEY_CUSTOM_HEADERS, builder::customHeaders);

        String apiEndpoint = stringValue(parameters, KEY_API_ENDPOINT);
        if (apiEndpoint != null) {
            // Vertex takes a bare "host[:port]"; give the check a scheme so it sees the
            // host. Never the cloud instance-metadata service; with
            // eddi.security.ssrf-protection.enabled no private address either.
            targetPolicy.validateUrl(apiEndpoint.contains("://") ? apiEndpoint : "https://" + apiEndpoint);
            builder.apiEndpoint(apiEndpoint);
        }
        return builder;
    }

    /**
     * The same JSON shape as the {@code gemini} provider:
     * {@code {"HARASSMENT":"BLOCK_ONLY_HIGH"}}, category names with or without the
     * {@code HARM_CATEGORY_} prefix. Entries that do not resolve — including the
     * protobuf placeholders {@code *_UNSPECIFIED} and {@code UNRECOGNIZED}, which
     * the request mapping cannot send — are dropped with a warning rather than
     * failing the model build.
     */
    static Map<HarmCategory, SafetyThreshold> safetySettings(Map<String, String> parameters) {
        Map<String, Object> raw = objectMap(parameters, KEY_SAFETY_SETTINGS);
        if (raw == null) {
            return null;
        }
        Map<HarmCategory, SafetyThreshold> result = new LinkedHashMap<>();
        raw.forEach((category, threshold) -> {
            HarmCategory c = sendable(GeminiLanguageModelBuilder.enumConstant(HarmCategory.class, "HARM_CATEGORY_", category));
            SafetyThreshold t = threshold == null
                    ? null
                    : sendable(GeminiLanguageModelBuilder.enumConstant(SafetyThreshold.class, "", String.valueOf(threshold)));
            if (c != null && t != null) {
                result.put(c, t);
            } else {
                LOGGER.warnf("gemini-vertex safetySettings entry for %s is not a valid category/threshold pair - skipped.",
                        sanitize(category));
            }
        });
        return result.isEmpty() ? null : result;
    }

    private static <E extends Enum<E>> E sendable(E constant) {
        if (constant == null || "UNRECOGNIZED".equals(constant.name()) || constant.name().endsWith("_UNSPECIFIED")) {
            return null;
        }
        return constant;
    }
}
