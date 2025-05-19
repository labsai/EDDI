package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.vertexai.runtime.gemini.VertexAiGeminiChatLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class VertexGeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_PUBLISHER = "publisher";
    private static final String KEY_MODEL_ID = "modelID";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = VertexAiGeminiChatLanguageModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_PROJECT_ID))) {
            builder.projectId(parameters.get(KEY_PROJECT_ID));
        }

        if (!isNullOrEmpty(parameters.get(KEY_PUBLISHER))) {
            builder.publisher(parameters.get(KEY_PUBLISHER));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
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
