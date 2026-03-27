package ai.labs.eddi.modules.llm.impl.builder;

import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Oracle GenAI builder — uses OCI authentication.
 * <p>
 * Authentication via OCI {@link ConfigFileAuthenticationDetailsProvider} (reads
 * {@code ~/.oci/config}). No simple {@code apiKey} pattern.
 * <p>
 * Params: {@code modelName}, {@code compartmentId}, {@code configProfile} (OCI
 * profile name, default "DEFAULT").
 * <p>
 * Note: OCI GenAI currently only supports synchronous (non-streaming) chat
 * models via this builder. See {@code OciGenAiStreamingChatModel} for
 * streaming.
 */
@ApplicationScoped
public class OracleGenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_COMPARTMENT_ID = "compartmentId";
    private static final String KEY_CONFIG_PROFILE = "configProfile";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String DEFAULT_CONFIG_PROFILE = "DEFAULT";

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

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }
        if (!isNullOrEmpty(parameters.get(KEY_COMPARTMENT_ID))) {
            builder.compartmentId(parameters.get(KEY_COMPARTMENT_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }

        return builder.build();
    }

    // OCI GenAI does not currently support streaming — uses default
    // UnsupportedOperationException
}
