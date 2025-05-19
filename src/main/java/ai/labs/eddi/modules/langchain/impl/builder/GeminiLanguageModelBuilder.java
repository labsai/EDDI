package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static dev.langchain4j.model.chat.request.ResponseFormat.JSON;
import static dev.langchain4j.model.chat.request.ResponseFormat.TEXT;

@ApplicationScoped
public class GeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    private static final String KEY_RESPONSE_FORMAT = "responseFormat";
    private static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String KEY_ALLOW_CODE_EXECUTION = "allowCodeExecution";
    private static final String TYPE_JSON = "json";
    private static final String KEY_TIMEOUT = "timeout";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = GoogleAiGeminiChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_API_KEY))) {
            builder.apiKey(parameters.get(KEY_API_KEY));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_NAME))) {
            builder.modelName(parameters.get(KEY_MODEL_NAME));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_RESPONSE_FORMAT))) {
            builder.responseFormat(TYPE_JSON.equalsIgnoreCase(parameters.get(KEY_RESPONSE_FORMAT))? JSON : TEXT);
        }

        if (!isNullOrEmpty(parameters.get(KEY_MAX_OUTPUT_TOKENS))) {
            builder.maxOutputTokens(Integer.parseInt(parameters.get(KEY_MAX_OUTPUT_TOKENS)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_ALLOW_CODE_EXECUTION))) {
            builder.allowCodeExecution(Boolean.parseBoolean(parameters.get(KEY_ALLOW_CODE_EXECUTION)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES))) {
            builder.logRequestsAndResponses(Boolean.parseBoolean(parameters.get(KEY_LOG_REQUESTS_AND_RESPONSES)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        return builder.build();
    }
}
