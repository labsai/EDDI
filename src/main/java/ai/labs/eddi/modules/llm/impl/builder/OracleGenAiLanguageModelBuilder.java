package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.community.model.oci.genai.OciGenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Oracle GenAI builder — uses OCI authentication.
 * <p>
 * Authentication via OCI {@code ConfigFileAuthenticationDetailsProvider} (reads
 * {@code ~/.oci/config}). No simple {@code apiKey} pattern.
 * <p>
 * Params: {@code modelId}, {@code compartmentId}, {@code configProfile} (OCI
 * profile name, default "DEFAULT").
 * <p>
 * Note: OCI GenAI currently only supports synchronous (non-streaming) chat
 * models.
 */
@ApplicationScoped
public class OracleGenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_COMPARTMENT_ID = "compartmentId";
    private static final String KEY_CONFIG_PROFILE = "configProfile";
    private static final String DEFAULT_CONFIG_PROFILE = "DEFAULT";

    private static final Logger LOGGER = Logger.getLogger(OracleGenAiLanguageModelBuilder.class);

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = OciGenAiChatModel.builder();

        var profile = parameters.getOrDefault(KEY_CONFIG_PROFILE, DEFAULT_CONFIG_PROFILE);
        try {
            var authProvider = new com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider(profile);
            builder.authProvider(authProvider);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load OCI config profile '%s': %s. " + "Ensure ~/.oci/config exists with the correct profile.", profile,
                    e.getMessage());
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_COMPARTMENT_ID))) {
            builder.compartmentId(parameters.get(KEY_COMPARTMENT_ID));
        }

        return builder.build();
    }

    // OCI GenAI does not currently support streaming — uses default
    // UnsupportedOperationException
}
