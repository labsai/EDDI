package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class GeminiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL_NAME = "modelName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    private static final String KEY_RESPONSE_MIMETYPE = "responseMimeType";
    private static final String KEY_MAX_OUTPUT_TOKENS = "maxOutputTokens";
    private static final String KEY_ALLOW_CODE_EXECUTION = "allowCodeExecution";
    // private static final String KEY_TIMEOUT = "timeout"; // not yet available

    @Override
    public ChatLanguageModel build(Map<String, String> parameters) {
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

        if (!isNullOrEmpty(parameters.get(KEY_RESPONSE_MIMETYPE))) {
            builder.responseMimeType(parameters.get(KEY_RESPONSE_MIMETYPE));
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

        return builder.build();
    }
}
