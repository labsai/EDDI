package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Amazon Bedrock builder — uses AWS SDK default credential chain.
 * <p>
 * No {@code apiKey} — authentication via:
 * <ul>
 * <li>Environment variables
 * ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY})</li>
 * <li>IAM roles (EC2 instance profile, ECS task role, EKS IRSA)</li>
 * <li>{@code ~/.aws/credentials} file</li>
 * </ul>
 * <p>
 * Params: {@code modelId} (e.g. {@code anthropic.claude-v2},
 * {@code meta.llama3-70b-instruct-v1:0}), {@code region} (e.g.
 * {@code us-east-1}).
 */
@ApplicationScoped
public class BedrockLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_REGION = "region";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TIMEOUT = "timeout";

    @Override
    public ChatModel build(Map<String, String> parameters) {
        var builder = BedrockChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_REGION))) {
            builder.region(software.amazon.awssdk.regions.Region.of(parameters.get(KEY_REGION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        var builder = BedrockStreamingChatModel.builder();

        if (!isNullOrEmpty(parameters.get(KEY_MODEL_ID))) {
            builder.modelId(parameters.get(KEY_MODEL_ID));
        }
        if (!isNullOrEmpty(parameters.get(KEY_REGION))) {
            builder.region(software.amazon.awssdk.regions.Region.of(parameters.get(KEY_REGION)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TEMPERATURE))) {
            builder.temperature(Double.parseDouble(parameters.get(KEY_TEMPERATURE)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_MAX_TOKENS))) {
            builder.maxTokens(Integer.parseInt(parameters.get(KEY_MAX_TOKENS)));
        }
        if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
            builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
        }

        return builder.build();
    }
}
