/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import com.azure.ai.openai.models.ReasoningEffortValue;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.booleanOrNull;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.doubleValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.intValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.longValue;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringList;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringMap;
import static ai.labs.eddi.modules.llm.impl.builder.ModelParameterValues.stringValue;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Azure OpenAI builder — uses Azure SDK HTTP pipeline (NOT JdkHttpClient).
 * <p>
 * Key differences from standard OpenAI:
 * <ul>
 * <li>Uses {@code deploymentName} instead of {@code modelName}</li>
 * <li>Uses {@code logRequestsAndResponses} (combined) instead of separate
 * flags</li>
 * <li>Requires {@code endpoint} (format:
 * {@code https://{resource}.openai.azure.com/})</li>
 * <li>Auth via {@code apiKey} (Azure key) or {@code nonAzureApiKey} (standard
 * OpenAI key)</li>
 * <li>{@code baseModel} names the model behind the deployment. It is read by
 * {@link ModelParameterCatalog} (a deployment name says nothing about which
 * parameters the model accepts) and is <em>never sent</em> to Azure.</li>
 * </ul>
 * <p>
 * Parameters are read once into a {@link Settings} and applied to the
 * synchronous and the streaming client alike, so the two cannot drift.
 * <p>
 * ⚠️ Native image: medium risk — Azure SDK uses Kotlin+Jackson reflection. Ship
 * for JVM mode, fix native config during Phase 12 CI/CD (native-image agent
 * tracing).
 */
@ApplicationScoped
public class AzureOpenAiLanguageModelBuilder implements ILanguageModelBuilder {
    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_NON_AZURE_API_KEY = "nonAzureApiKey";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_DEPLOYMENT_NAME = "deploymentName";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_TIMEOUT = "timeout";
    private static final String KEY_LOG_REQUESTS_AND_RESPONSES = "logRequestsAndResponses";
    static final String KEY_BASE_MODEL = "baseModel";
    static final String KEY_TOP_P = "topP";
    static final String KEY_STOP_SEQUENCES = "stopSequences";
    static final String KEY_SEED = "seed";
    static final String KEY_FREQUENCY_PENALTY = "frequencyPenalty";
    static final String KEY_PRESENCE_PENALTY = "presencePenalty";
    static final String KEY_USER = "user";
    static final String KEY_SERVICE_VERSION = "serviceVersion";
    static final String KEY_MAX_COMPLETION_TOKENS = "maxCompletionTokens";
    static final String KEY_REASONING_EFFORT = "reasoningEffort";
    static final String KEY_LOGIT_BIAS = "logitBias";
    static final String KEY_STRICT_JSON_SCHEMA = "strictJsonSchema";
    static final String KEY_CUSTOM_HEADERS = "customHeaders";

    @Override
    public Set<String> recognisedParameters() {
        return Set.of(KEY_API_KEY, KEY_NON_AZURE_API_KEY, KEY_ENDPOINT, KEY_DEPLOYMENT_NAME, KEY_TEMPERATURE,
                KEY_MAX_TOKENS, KEY_TIMEOUT, KEY_LOG_REQUESTS_AND_RESPONSES, KEY_BASE_MODEL, KEY_TOP_P,
                KEY_STOP_SEQUENCES, KEY_SEED, KEY_FREQUENCY_PENALTY, KEY_PRESENCE_PENALTY, KEY_USER,
                KEY_SERVICE_VERSION, KEY_MAX_COMPLETION_TOKENS, KEY_REASONING_EFFORT, KEY_LOGIT_BIAS,
                KEY_STRICT_JSON_SCHEMA, KEY_CUSTOM_HEADERS);
    }

    /**
     * Every setting the builders read; {@code null} leaves the provider default.
     * {@code baseModel} is deliberately absent: it never reaches the client.
     */
    record Settings(String endpoint, String apiKey, String nonAzureApiKey, String deploymentName, String serviceVersion,
            Double temperature, Double topP, Integer maxTokens, Integer maxCompletionTokens, List<String> stop, Long seed,
            Double frequencyPenalty, Double presencePenalty, String user, ReasoningEffortValue reasoningEffort,
            Map<String, Integer> logitBias, Boolean strictJsonSchema, Long timeoutMs, Boolean logRequestsAndResponses,
            Map<String, String> customHeaders) {
    }

    static Settings read(Map<String, String> p) {
        String endpoint = stringValue(p, KEY_ENDPOINT);
        if (endpoint != null) {
            // Never let the endpoint point at the cloud instance-metadata service
            // (always-on, independent of eddi.security.ssrf-protection.enabled).
            UrlValidationUtils.rejectCloudMetadataTarget(endpoint);
        }
        // An unknown effort is passed through: ReasoningEffortValue is an expandable
        // enum, and Azure adds values before the SDK names them.
        String effort = stringValue(p, KEY_REASONING_EFFORT);
        return new Settings(endpoint, isNullOrEmpty(p.get(KEY_API_KEY)) ? null : p.get(KEY_API_KEY),
                isNullOrEmpty(p.get(KEY_NON_AZURE_API_KEY)) ? null : p.get(KEY_NON_AZURE_API_KEY),
                isNullOrEmpty(p.get(KEY_DEPLOYMENT_NAME)) ? null : p.get(KEY_DEPLOYMENT_NAME), stringValue(p, KEY_SERVICE_VERSION),
                doubleValue(p, KEY_TEMPERATURE), doubleValue(p, KEY_TOP_P), intValue(p, KEY_MAX_TOKENS),
                intValue(p, KEY_MAX_COMPLETION_TOKENS), stringList(p, KEY_STOP_SEQUENCES), longValue(p, KEY_SEED),
                doubleValue(p, KEY_FREQUENCY_PENALTY), doubleValue(p, KEY_PRESENCE_PENALTY), stringValue(p, KEY_USER),
                effort == null ? null : ReasoningEffortValue.fromString(effort), intMap(p, KEY_LOGIT_BIAS),
                booleanOrNull(p, KEY_STRICT_JSON_SCHEMA), longValue(p, KEY_TIMEOUT), booleanOrNull(p, KEY_LOG_REQUESTS_AND_RESPONSES),
                stringMap(p, KEY_CUSTOM_HEADERS));
    }

    @Override
    public ChatModel build(Map<String, String> parameters) {
        Settings s = read(parameters);
        var b = AzureOpenAiChatModel.builder();
        if (s.endpoint() != null)
            b.endpoint(s.endpoint());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.nonAzureApiKey() != null)
            b.nonAzureApiKey(s.nonAzureApiKey());
        if (s.deploymentName() != null)
            b.deploymentName(s.deploymentName());
        if (s.serviceVersion() != null)
            b.serviceVersion(s.serviceVersion());
        if (s.temperature() != null)
            b.temperature(s.temperature());
        if (s.topP() != null)
            b.topP(s.topP());
        if (s.maxTokens() != null)
            b.maxTokens(s.maxTokens());
        if (s.maxCompletionTokens() != null)
            b.maxCompletionTokens(s.maxCompletionTokens());
        if (s.stop() != null)
            b.stop(s.stop());
        if (s.seed() != null)
            b.seed(s.seed());
        if (s.frequencyPenalty() != null)
            b.frequencyPenalty(s.frequencyPenalty());
        if (s.presencePenalty() != null)
            b.presencePenalty(s.presencePenalty());
        if (s.user() != null)
            b.user(s.user());
        if (s.reasoningEffort() != null)
            b.reasoningEffort(s.reasoningEffort());
        if (s.logitBias() != null)
            b.logitBias(s.logitBias());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequestsAndResponses() != null)
            b.logRequestsAndResponses(s.logRequestsAndResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());

        // executeWithRetry is the single retry owner; library retries multiply it.
        b.maxRetries(NO_LIBRARY_RETRIES);

        return b.build();
    }

    @Override
    public StreamingChatModel buildStreaming(Map<String, String> parameters) {
        Settings s = read(parameters);
        var b = AzureOpenAiStreamingChatModel.builder();
        if (s.endpoint() != null)
            b.endpoint(s.endpoint());
        if (s.apiKey() != null)
            b.apiKey(s.apiKey());
        if (s.nonAzureApiKey() != null)
            b.nonAzureApiKey(s.nonAzureApiKey());
        if (s.deploymentName() != null)
            b.deploymentName(s.deploymentName());
        if (s.serviceVersion() != null)
            b.serviceVersion(s.serviceVersion());
        if (s.temperature() != null)
            b.temperature(s.temperature());
        if (s.topP() != null)
            b.topP(s.topP());
        if (s.maxTokens() != null)
            b.maxTokens(s.maxTokens());
        if (s.maxCompletionTokens() != null)
            b.maxCompletionTokens(s.maxCompletionTokens());
        if (s.stop() != null)
            b.stop(s.stop());
        if (s.seed() != null)
            b.seed(s.seed());
        if (s.frequencyPenalty() != null)
            b.frequencyPenalty(s.frequencyPenalty());
        if (s.presencePenalty() != null)
            b.presencePenalty(s.presencePenalty());
        if (s.user() != null)
            b.user(s.user());
        if (s.reasoningEffort() != null)
            b.reasoningEffort(s.reasoningEffort());
        if (s.logitBias() != null)
            b.logitBias(s.logitBias());
        if (s.strictJsonSchema() != null)
            b.strictJsonSchema(s.strictJsonSchema());
        if (s.timeoutMs() != null)
            b.timeout(Duration.ofMillis(s.timeoutMs()));
        if (s.logRequestsAndResponses() != null)
            b.logRequestsAndResponses(s.logRequestsAndResponses());
        if (s.customHeaders() != null)
            b.customHeaders(s.customHeaders());

        return b.build();
    }
}
