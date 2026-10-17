/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyLong;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Builds a HuggingFace chat model using the legacy HuggingFace Inference API.
 *
 * <p>
 * <b>Note:</b> {@code HuggingFaceChatModel} is deprecated in langchain4j since
 * v1.7.0. The recommended approach is to use {@code OpenAiChatModel} with
 * HuggingFace's OpenAI-compatible router endpoint:
 *
 * <pre>
 * OpenAiChatModel.builder().apiKey(hfApiKey).baseUrl("https://router.huggingface.co/v1").modelName("HuggingFaceTB/SmolLM3-3B:hf-inference").build();
 * </pre>
 *
 * In EDDI, this can be achieved by using the "openai" model type with the above
 * baseUrl and your HuggingFace API key as the apiKey. This builder is retained
 * for backward compatibility with existing configurations.
 *
 * <p>
 * <b>Core builder methods:</b> {@code accessToken}, {@code modelId},
 * {@code baseUrl}, {@code timeout}, {@code temperature}, {@code maxNewTokens},
 * {@code returnFullText}, {@code waitForModel}. Parameters like {@code topK},
 * {@code topP}, {@code doSample}, {@code repetitionPenalty} are NOT supported
 * by the core HuggingFaceChatModel builder (they were quarkiverse extensions).
 */
@ApplicationScoped
public class HuggingFaceLanguageModelBuilder implements ILanguageModelBuilder {

    /**
     * Applies {@code eddi.security.ssrf-protection.enabled} to the configured
     * endpoint. Field-injected; null in tests built with {@code new}, which then
     * validate as with protection off (metadata check only).
     */
    @Inject
    OutboundTargetPolicy targetPolicy;
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_MAX_NEW_TOKENS = "maxNewTokens";
    private static final String KEY_WAIT_FOR_MODEL = "waitForModel";
    /**
     * The service root, without {@code models/} — langchain4j appends
     * {@code models/<modelId>} itself. Unset, langchain4j's own default
     * ({@code https://router.huggingface.co/hf-inference/}) applies.
     */
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_RETURN_FULL_TEXT = "returnFullText";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_ACCESS_TOKEN, KEY_MODEL_ID, KEY_TEMPERATURE, KEY_TIMEOUT, KEY_MAX_NEW_TOKENS, KEY_WAIT_FOR_MODEL,
                KEY_BASE_URL, KEY_RETURN_FULL_TEXT);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        return applyTo(HuggingFaceChatModel.builder(), parameters, OutboundTargetPolicy.orPermissive(targetPolicy)).build();
    }

    /**
     * Applies the configured parameters and hands the builder back unbuilt, so the
     * mapping can be asserted without an access token.
     * <p>
     * The model id goes to {@code modelId}: langchain4j's client requests
     * {@code <baseUrl>models/<modelId>} and refuses to start without a model id.
     * This builder used to fold the id into a {@code baseUrl} of
     * {@code https://api-inference.huggingface.co/models/<id>} and never set
     * {@code modelId}, which fails that check.
     */
    static HuggingFaceChatModel.Builder applyTo(HuggingFaceChatModel.Builder builder, Map<String, String> parameters) {
        return applyTo(builder, parameters, OutboundTargetPolicy.permissive());
    }

    @SuppressWarnings("removal")
    static HuggingFaceChatModel.Builder applyTo(HuggingFaceChatModel.Builder builder, Map<String, String> parameters,
                                                OutboundTargetPolicy targetPolicy) {
        if (!isNullOrEmpty(parameters.get(KEY_ACCESS_TOKEN))) {
            builder.accessToken(parameters.get(KEY_ACCESS_TOKEN));
        }

        var modelId = stringValue(parameters, KEY_MODEL_ID);
        if (modelId != null) {
            if (modelId.startsWith("/")) {
                modelId = modelId.substring(1);
            }
            builder.modelId(modelId);
        }

        String baseUrl = stringValue(parameters, KEY_BASE_URL);
        if (baseUrl != null) {
            // Never the cloud instance-metadata service; with
            // eddi.security.ssrf-protection.enabled no private address either.
            targetPolicy.validateUrl(baseUrl);
            builder.baseUrl(baseUrl);
        }

        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_NEW_TOKENS, builder::maxNewTokens);
        applyBoolean(parameters, KEY_WAIT_FOR_MODEL, builder::waitForModel);
        applyBoolean(parameters, KEY_RETURN_FULL_TEXT, builder::returnFullText);
        return builder;
    }
}
