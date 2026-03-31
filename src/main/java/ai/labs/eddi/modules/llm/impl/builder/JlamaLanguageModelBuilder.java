package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class JlamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_AUTH_TOKEN = "authToken";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";

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

        return builder.build();
    }
}
