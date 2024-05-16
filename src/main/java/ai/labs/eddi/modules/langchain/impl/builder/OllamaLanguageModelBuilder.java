package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkiverse.langchain4j.ollama.OllamaChatLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class OllamaLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL = "model";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    @Override
    public ChatLanguageModel build(Map<String, String> parameters) {
        var builder = OllamaChatLanguageModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.model(parameters.get(KEY_MODEL));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
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
