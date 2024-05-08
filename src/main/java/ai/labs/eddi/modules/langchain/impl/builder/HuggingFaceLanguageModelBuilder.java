package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class HuggingFaceLanguageModelBuilder implements IHuggingFaceLanguageModelBuilder {
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";

    @Override
    public ChatLanguageModel build(Map<String, String> parameters) {
        var builder = HuggingFaceChatModel.builder();
        if (!isNullOrEmpty(parameters.get(KEY_ACCESS_TOKEN))) {
            builder.accessToken(parameters.get(KEY_ACCESS_TOKEN));
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

        return builder.build();
    }
}
