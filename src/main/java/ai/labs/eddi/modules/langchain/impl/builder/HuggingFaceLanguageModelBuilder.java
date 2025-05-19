package ai.labs.eddi.modules.langchain.impl.builder;

import ai.labs.eddi.modules.langchain.impl.LangchainTask;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.huggingface.QuarkusHuggingFaceChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class HuggingFaceLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_ACCESS_TOKEN = "accessToken";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS = "logRequests";
    private static final String KEY_LOG_RESPONSES = "logResponses";
    private static final String KEY_DO_SAMPLE = "doSample";
    private static final String KEY_TOP_K = "topK";
    private static final String KEY_TOP_P = "topP";
    private static final String KEY_REPETITION_PENALTY = "repetitionPenalty";
    private static final String BASE_URL_HUGGING_FACE = "https://api-inference.huggingface.co/models/";

    private static final int DEFAULT_TOP_K = 50;
    private static final double DEFAULT_TOP_P = 0.9;
    private static final boolean DEFAULT_DO_SAMPLE = false;
    private static final double DEFAULT_REPETITION_PENALTY = 1.2;

    private static final Logger LOGGER = Logger.getLogger(LangchainTask.class);

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = QuarkusHuggingFaceChatModel.builder();
        if (!isNullOrEmpty(parameters.get(KEY_ACCESS_TOKEN))) {
            builder.accessToken(parameters.get(KEY_ACCESS_TOKEN));
        }

        var modelId = parameters.get(KEY_MODEL_ID);
        if (!isNullOrEmpty(modelId)) {
            if(modelId.startsWith("/")) {
                modelId = modelId.substring(1);
            }
            var huggingFaceUrl = BASE_URL_HUGGING_FACE + modelId;
            try {
                builder.url(URI.create(huggingFaceUrl).toURL());
            } catch (MalformedURLException e) {
                LOGGER.error(String.format("Malformed Hugging Face URL (%s)", huggingFaceUrl), e);
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

        if (!isNullOrEmpty(parameters.get(KEY_TOP_K))) {
            builder.topK(OptionalInt.of(Integer.parseInt(parameters.get(KEY_TOP_K))));
        } else {
            builder.topK(OptionalInt.of(DEFAULT_TOP_K));
        }

        if (!isNullOrEmpty(parameters.get(KEY_TOP_P))) {
            builder.topP(OptionalDouble.of(Double.parseDouble(parameters.get(KEY_TOP_P))));
        } else {
            builder.topP(OptionalDouble.of(DEFAULT_TOP_P));
        }

        if (!isNullOrEmpty(parameters.get(KEY_DO_SAMPLE))) {
            builder.doSample(Optional.of(Boolean.parseBoolean(parameters.get(KEY_DO_SAMPLE))));
        } else {
            builder.doSample(Optional.of(DEFAULT_DO_SAMPLE));
        }

        if (!isNullOrEmpty(parameters.get(KEY_REPETITION_PENALTY))) {
            builder.repetitionPenalty(OptionalDouble.of(Double.parseDouble(parameters.get(KEY_REPETITION_PENALTY))));
        } else {
            builder.repetitionPenalty(OptionalDouble.of(DEFAULT_REPETITION_PENALTY));
        }

        return builder.build();
    }
}
