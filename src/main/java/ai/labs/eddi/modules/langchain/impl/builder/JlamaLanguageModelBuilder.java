package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.jlama.JlamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class JlamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = JlamaChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }

        if (!isNullOrEmpty(parameters.get(KEY_AUTH_TOKEN))) {
            builder.authToken(parameters.get(KEY_AUTH_TOKEN));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Float.parseFloat(parameters.get(KEY_TEMPERATURE)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS))) {
            builder.logRequests(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_LOG_RESPONSES))) {
            builder.logResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_RESPONSES)));
        }

        return builder.build();
    }
}
