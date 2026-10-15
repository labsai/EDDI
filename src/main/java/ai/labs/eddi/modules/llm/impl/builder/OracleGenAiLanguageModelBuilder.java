/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.generativeaiinference.model.ServingMode;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyDouble;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyInt;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.applyStringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Oracle GenAI builder — uses OCI authentication.
 * <p>
 * Authentication via OCI {@link ConfigFileAuthenticationDetailsProvider} (reads
 * {@code ~/.oci/config}). No simple {@code apiKey} pattern.
 * <p>
 * Params: {@code modelName}, {@code compartmentId}, {@code configProfile} (OCI
 * profile name, default "DEFAULT"), plus the sampling settings of the generic
 * OCI chat builder ({@code region}, {@code topP}, {@code topK}, {@code seed},
 * {@code stopSequences}, the penalties, {@code servingType}).
 * <p>
 * Note: OCI GenAI currently only supports synchronous (non-streaming) chat
 * models via this builder. See {@code OciGenAiStreamingChatModel} for
 * streaming.
 */
@ApplicationScoped
public class OracleGenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final Logger LOGGER = Logger.getLogger(OracleGenAiLanguageModelBuilder.class);

    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_COMPARTMENT_ID = "compartmentId";
    private static final String KEY_CONFIG_PROFILE = "configProfile";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    static final String KEY_REGION = "region";
    static final String KEY_TOP_P = "topP";
    static final String KEY_TOP_K = "topK";
    static final String KEY_SEED = "seed";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_FREQUENCY_PENALTY = "frequencyPenalty";
    static final String KEY_PRESENCE_PENALTY = "presencePenalty";
    static final String KEY_SERVING_TYPE = "servingType";
    private static final String DEFAULT_CONFIG_PROFILE = "DEFAULT";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_MODEL_NAME, KEY_COMPARTMENT_ID, KEY_CONFIG_PROFILE, KEY_TEMPERATURE, KEY_MAX_TOKENS, KEY_REGION,
                KEY_TOP_P, KEY_TOP_K, KEY_SEED, KEY_STOP_SEQUENCES, KEY_FREQUENCY_PENALTY, KEY_PRESENCE_PENALTY,
                KEY_SERVING_TYPE);
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = OciGenAiChatModel.builder();

        var profile = parameters.getOrDefault(KEY_CONFIG_PROFILE, DEFAULT_CONFIG_PROFILE);
        try {
            var authProvider = new ConfigFileAuthenticationDetailsProvider(profile);
            builder.authProvider(authProvider);
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format("Failed to load OCI config profile '%s'. " + "Ensure ~/.oci/config exists with the correct profile.", profile), e);
        }

        return applyTo(builder, parameters).build();
    }

    /**
     * Applies everything but authentication and hands the builder back unbuilt:
     * {@code build()} needs a real {@code ~/.oci/config}, so the unit tests assert
     * on the populated builder instead.
     */
    static OciGenAiChatModel.Builder applyTo(OciGenAiChatModel.Builder builder, Map<String, String> parameters) {
        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_COMPARTMENT_ID))) {
            builder.compartmentId(parameters.get(KEY_COMPARTMENT_ID));
        }
        applyDouble(parameters, KEY_TEMPERATURE, builder::temperature);
        applyInt(parameters, KEY_MAX_TOKENS, builder::maxTokens);
        applyDouble(parameters, KEY_TOP_P, builder::topP);
        applyInt(parameters, KEY_TOP_K, builder::topK);
        applyInt(parameters, KEY_SEED, builder::seed);
        applyStringList(parameters, KEY_STOP_SEQUENCES, builder::stop);
        applyDouble(parameters, KEY_FREQUENCY_PENALTY, builder::frequencyPenalty);
        applyDouble(parameters, KEY_PRESENCE_PENALTY, builder::presencePenalty);

        String region = stringValue(parameters, KEY_REGION);
        if (region != null) {
            try {
                builder.region(Region.fromRegionCodeOrId(region));
            } catch (IllegalArgumentException e) {
                LOGGER.warnf("OCI region '%s' is not known to the OCI SDK - using the region from the OCI config.",
                        sanitize(region));
            }
        }

        String servingType = stringValue(parameters, KEY_SERVING_TYPE);
        if (servingType != null) {
            ServingMode.ServingType resolved = null;
            for (ServingMode.ServingType candidate : ServingMode.ServingType.values()) {
                if (candidate.getValue().equalsIgnoreCase(servingType) || candidate.name().equalsIgnoreCase(servingType)) {
                    resolved = candidate;
                }
            }
            if (resolved != null) {
                builder.servingType(resolved);
            } else {
                LOGGER.warnf("OCI servingType must be one of %s - using the provider default.",
                        Arrays.toString(ServingMode.ServingType.values()));
            }
        }
        return builder;
    }

    // OCI GenAI does not currently support streaming — uses default
    // UnsupportedOperationException
}
