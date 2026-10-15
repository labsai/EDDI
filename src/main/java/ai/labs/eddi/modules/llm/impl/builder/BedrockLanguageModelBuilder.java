/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.bedrock.BedrockCachePointPlacement;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockGuardrailConfiguration;
import dev.langchain4j.model.bedrock.BedrockServiceTier;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyBoolean;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyLong;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyStringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.objectMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Amazon Bedrock builder — uses AWS SDK default credential chain.
 * <p>
 * No {@code apiKey} — authentication via:
 * <ul>
 * <li>Environment variables
 * ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY})</li>
 * <li>IAM roles (EC2 instance profile, ECS task role, EKS IRSA)</li>
 * <li>{@code ~/.aws/credentials} file</li>
 * </ul>
 * <p>
 * Params: {@code modelId} (e.g. {@code anthropic.claude-v2},
 * {@code meta.llama3-70b-instruct-v1:0}), {@code region} (e.g.
 * {@code us-east-1}).
 * <p>
 * Note: Bedrock takes almost every request setting through
 * {@code defaultRequestParameters} rather than direct builder methods. One
 * {@link BedrockChatRequestParameters} is built from the configuration and
 * handed to the synchronous and the streaming client alike.
 */
@ApplicationScoped
public class BedrockLanguageModelBuilder implements ILanguageModelBuilder {
    private static final Logger LOGGER = Logger.getLogger(BedrockLanguageModelBuilder.class);

    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_REGION = "region";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TIMEOUT = "timeout";
    static final String KEY_TOP_P = "topP";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    /** Extended thinking with a fixed budget ({@code reasoning_config}). */
    static final String KEY_REASONING_BUDGET_TOKENS = "reasoningBudgetTokens";
    /** Adaptive thinking at an effort level ({@code output_config.effort}). */
    static final String KEY_REASONING_EFFORT = "reasoningEffort";
    static final String KEY_PROMPT_CACHE_PLACEMENT = "promptCachePlacement";
    static final String KEY_PROMPT_CACHE_TTL = "promptCacheTtl";
    static final String KEY_GUARDRAIL_ID = "guardrailId";
    static final String KEY_GUARDRAIL_VERSION = "guardrailVersion";
    static final String KEY_GUARDRAIL_STREAM_MODE = "guardrailStreamMode";
    static final String KEY_SERVICE_TIER = "serviceTier";
    static final String KEY_ADDITIONAL_MODEL_REQUEST_FIELDS = "additionalModelRequestFields";
    static final String KEY_REQUEST_METADATA = "requestMetadata";
    static final String KEY_RETURN_THINKING = "returnThinking";
    static final String KEY_SEND_THINKING = "sendThinking";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL_ID, KEY_REGION, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_TIMEOUT, KEY_TOP_P,
                KEY_STOP_SEQUENCES, KEY_REASONING_BUDGET_TOKENS, KEY_REASONING_EFFORT, KEY_PROMPT_CACHE_PLACEMENT,
                KEY_PROMPT_CACHE_TTL, KEY_GUARDRAIL_ID, KEY_GUARDRAIL_VERSION, KEY_GUARDRAIL_STREAM_MODE,
                KEY_SERVICE_TIER, KEY_ADDITIONAL_MODEL_REQUEST_FIELDS, KEY_REQUEST_METADATA, KEY_RETURN_THINKING,
                KEY_SEND_THINKING, KEY_CUSTOM_HEADERS);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = BedrockChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_REGION))) {
            builder.region(Region.of(parameters.get(KEY_REGION)));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        applyBoolean(parameters, KEY_SEND_THINKING, builder::sendThinking);
        Map<String, String> headers = stringMap(parameters, KEY_CUSTOM_HEADERS);
        if (headers != null) {
            builder.customHeaders(headers);
        }

        var requestParams = buildRequestParameters(parameters);
        if (requestParams != null) {
            builder.defaultRequestParameters(requestParams);
        }

        // executeWithRetry is the single retry owner; library retries multiply it.
        builder.maxRetries(NO_LIBRARY_RETRIES);

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = BedrockStreamingChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_REGION))) {
            builder.region(Region.of(parameters.get(KEY_REGION)));
        }
        applyLong(parameters, KEY_TIMEOUT, ms -> builder.timeout(Duration.ofMillis(ms)));
        applyBoolean(parameters, KEY_RETURN_THINKING, builder::returnThinking);
        applyBoolean(parameters, KEY_SEND_THINKING, builder::sendThinking);
        Map<String, String> headers = stringMap(parameters, KEY_CUSTOM_HEADERS);
        if (headers != null) {
            builder.customHeaders(headers);
        }

        var requestParams = buildRequestParameters(parameters);
        if (requestParams != null) {
            builder.defaultRequestParameters(requestParams);
        }

        return builder.build();
    }

    /**
     * The single request-parameter object both clients use, or {@code null} when
     * nothing in it is configured (the provider defaults then apply untouched).
     * <p>
     * Order matters inside: langchain4j's {@code enableReasoning} and
     * {@code enableAdaptiveReasoning} write into the same
     * {@code additionalModelRequestFields} map that
     * {@code additionalModelRequestFields(map)} would <em>replace</em>, so the
     * user's own fields are added afterwards one by one — they win per key, as
     * {@code customParameters} does on the OpenAI-family builders.
     */
    static BedrockChatRequestParameters buildRequestParameters(Map<String, String> parameters) {
        var req = BedrockChatRequestParameters.builder();
        boolean[] any = {false};

        applyDouble(parameters, KEY_TEMPERATURE, v -> {
            req.temperature(v);
            any[0] = true;
        });
        applyInt(parameters, KEY_MAX_TOKENS, v -> {
            req.maxOutputTokens(v);
            any[0] = true;
        });
        applyDouble(parameters, KEY_TOP_P, v -> {
            req.topP(v);
            any[0] = true;
        });
        applyStringList(parameters, KEY_STOP_SEQUENCES, v -> {
            req.stopSequences(v);
            any[0] = true;
        });

        String effort = stringValue(parameters, KEY_REASONING_EFFORT);
        Integer budget = intValue(parameters, KEY_REASONING_BUDGET_TOKENS);
        if (effort != null && budget != null) {
            LOGGER.warn("Bedrock reasoningEffort and reasoningBudgetTokens are mutually exclusive - using reasoningEffort.");
            budget = null;
        }
        if (effort != null) {
            req.enableAdaptiveReasoning(effort);
            any[0] = true;
        } else if (budget != null) {
            req.enableReasoning(budget);
            any[0] = true;
        }

        Map<String, Object> extraFields = objectMap(parameters, KEY_ADDITIONAL_MODEL_REQUEST_FIELDS);
        if (extraFields != null) {
            new LinkedHashMap<>(extraFields).forEach(req::additionalModelRequestField);
            any[0] = true;
        }
        Map<String, String> metadata = stringMap(parameters, KEY_REQUEST_METADATA);
        if (metadata != null) {
            req.requestMetadata(metadata);
            any[0] = true;
        }

        BedrockCachePointPlacement placement = constant(parameters, KEY_PROMPT_CACHE_PLACEMENT, BedrockCachePointPlacement.values());
        String ttlRaw = stringValue(parameters, KEY_PROMPT_CACHE_TTL);
        CacheTTL ttl = null;
        if (ttlRaw != null) {
            ttl = CacheTTL.fromValue(ttlRaw.toLowerCase(Locale.ROOT));
            if (ttl == null || ttl == CacheTTL.UNKNOWN_TO_SDK_VERSION) {
                LOGGER.warnf("Bedrock %s must be one of %s - using the provider default.", KEY_PROMPT_CACHE_TTL,
                        CacheTTL.knownValues());
                ttl = null;
            }
        }
        if (placement != null) {
            if (ttl != null) {
                req.promptCaching(placement, ttl);
            } else {
                req.promptCaching(placement);
            }
            any[0] = true;
        } else if (ttl != null) {
            LOGGER.warnf("Bedrock %s has no effect without %s.", KEY_PROMPT_CACHE_TTL, KEY_PROMPT_CACHE_PLACEMENT);
        }

        String guardrailId = stringValue(parameters, KEY_GUARDRAIL_ID);
        String guardrailVersion = stringValue(parameters, KEY_GUARDRAIL_VERSION);
        if (guardrailId != null && guardrailVersion != null) {
            var guardrail = BedrockGuardrailConfiguration.builder().guardrailIdentifier(guardrailId)
                    .guardrailVersion(guardrailVersion);
            BedrockGuardrailConfiguration.ProcessingMode mode = constant(parameters, KEY_GUARDRAIL_STREAM_MODE,
                    BedrockGuardrailConfiguration.ProcessingMode.values());
            if (mode != null) {
                guardrail.streamProcessingMode(mode);
            }
            req.guardrailConfiguration(guardrail.build());
            any[0] = true;
        } else if (guardrailId != null || guardrailVersion != null) {
            LOGGER.warnf("Bedrock guardrails need both %s and %s - no guardrail applied.", KEY_GUARDRAIL_ID,
                    KEY_GUARDRAIL_VERSION);
        }

        BedrockServiceTier tier = constant(parameters, KEY_SERVICE_TIER, BedrockServiceTier.values());
        if (tier != null) {
            req.serviceTier(tier);
            any[0] = true;
        }

        return any[0] ? req.build() : null;
    }

    /**
     * The enum constant named by the parameter (case-insensitive, {@code -} read as
     * {@code _}), or {@code null} with a warning naming the accepted values.
     */
    private static <E extends Enum<E>> E constant(Map<String, String> parameters, String key, E[] values) {
        String raw = stringValue(parameters, key);
        if (raw == null) {
            return null;
        }
        List<String> names = Arrays.stream(values).map(Enum::name).toList();
        String canonical = ModelParameterValues.enumValue(Map.of(key, raw.replace('-', '_')), key, names);
        if (canonical == null) {
            return null;
        }
        for (E value : values) {
            if (value.name().equals(canonical)) {
                return value;
            }
        }
        return null;
    }
}
