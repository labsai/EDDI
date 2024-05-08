package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class OpenAILanguageModelBuilder implements IOpenAILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TIMEOUT = "timeout";

    @Override
    public ChatLanguageModel build(Map<String, String> parameters) {
        var builder = OpenAiChatModel.builder();
        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }

        return builder.build();
    }
}
