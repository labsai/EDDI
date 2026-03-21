package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Builds a HuggingFace chat model using the legacy HuggingFace Inference API.
 *
 * <p><b>Note:</b> {@code HuggingFaceChatModel} is deprecated in langchain4j since v1.7.0.
 * The recommended approach is to use {@code OpenAiChatModel} with HuggingFace's
 * OpenAI-compatible router endpoint:
 * <pre>
 * OpenAiChatModel.builder()
 *     .apiKey(hfApiKey)
 *     .baseUrl("https://router.huggingface.co/v1")
 *     .modelName("HuggingFaceTB/SmolLM3-3B:hf-inference")
 *     .build();
 * </pre>
 * In EDDI, this can be achieved by using the "openai" model type with the above
 * baseUrl and your HuggingFace API key as the apiKey. This builder is retained for
 * backward compatibility with existing configurations.
 *
 * <p><b>Core builder methods:</b> {@code accessToken}, {@code modelId} (via baseUrl),
 * {@code timeout}, {@code temperature}, {@code maxNewTokens}, {@code waitForModel}.
 * Parameters like {@code topK}, {@code topP}, {@code doSample}, {@code repetitionPenalty}
 * are NOT supported by the core HuggingFaceChatModel builder (they were quarkiverse extensions).
 */
@ApplicationScoped
public class HuggingFaceLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_MAX_NEW_TOKENS = "maxNewTokens";
    private static final String KEY_WAIT_FOR_MODEL = "waitForModel";
    private static final String BASE_URL_HUGGING_FACE = "https://api-inference.huggingface.co/models/";

    @SuppressWarnings("removal")
    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = HuggingFaceChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_ACCESS_TOKEN))) {
            builder.accessToken(parameters.get(KEY_ACCESS_TOKEN));
        }

        var modelId = parameters.get(KEY_MODEL_ID);
        if (!isNullOrEmpty(modelId)) {
            if (modelId.startsWith("/")) {
                modelId = modelId.substring(1);
            }
            var huggingFaceUrl = BASE_URL_HUGGING_FACE + modelId;
            builder.baseUrl(huggingFaceUrl);
        }

        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MAX_NEW_TOKENS))) {
            builder.maxNewTokens(Integer.parseInt(parameters.get(KEY_MAX_NEW_TOKENS)));
        }

        if (!isNullOrEmpty(parameters.get(KEY_WAIT_FOR_MODEL))) {
            builder.waitForModel(Boolean.parseBoolean(parameters.get(KEY_WAIT_FOR_MODEL)));
        }

        return builder.build();
    }
}
