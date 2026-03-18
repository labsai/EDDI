package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
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
    private static final String KEY_BASE_URL = "baseUrl";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = OllamaChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_BASE_URL))) {
            builder.baseUrl(parameters.get(KEY_BASE_URL));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
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

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = OllamaStreamingChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder());

        if (!isNullOrEmpty(parameters.get(KEY_BASE_URL))) {
            builder.baseUrl(parameters.get(KEY_BASE_URL));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MODEL))) {
            builder.modelName(parameters.get(KEY_MODEL));
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
