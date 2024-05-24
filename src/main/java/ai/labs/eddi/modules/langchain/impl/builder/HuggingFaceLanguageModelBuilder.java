package ai.labs.eddi.modules.langchain.impl.builder;

import ai.labs.eddi.modules.langchain.impl.LangchainTask;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkiverse.langchain4j.huggingface.QuarkusHuggingFaceChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class HuggingFaceLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    private static final String BASE_URL_HUGGING_FACE = "https://api-inference.huggingface.co/models/";

    private static final Logger LOGGER = Logger.getLogger(LangchainTask.class);

    @Override
    public ChatLanguageModel build(Map<String, String> parameters) {
        var builder = QuarkusHuggingFaceChatModel.builder();
        if (!isNullOrEmpty(parameters.get(KEY_ACCESS_TOKEN))) {
            builder.accessToken(parameters.get(KEY_ACCESS_TOKEN));
        }

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            try {
                builder.url(URI.create(BASE_URL_HUGGING_FACE + parameters.get(KEY_MODEL_ID)).toURL());
            } catch (MalformedURLException e) {
                LOGGER.error("Malformed URL", e);
            }
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
