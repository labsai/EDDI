/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class VertexGeminiLanguageModelBuilder implements ILanguageModelBuilder {
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

    private static final Logger LOGGER = Logger.getLogger(VertexGeminiLanguageModelBuilder.class);

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_PUBLISHER, KEY_MODEL_ID, KEY_MODEL_ID_LEGACY, KEY_TEMPERATURE, KEY_PROJECT_ID, KEY_LOCATION,
                KEY_LOG_REQUESTS, KEY_LOG_RESPONSES);
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
        var builder = VertexAiGeminiChatModel.builder();

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

        // Parsed as a double and narrowed: this setter takes a float, and a separate
        // float helper would buy nothing — every value a float accepts, a double
        // accepts too.
        applyDouble(parameters, KEY_TEMPERATURE, temperature -> builder.temperature((float) temperature));

        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }
}
