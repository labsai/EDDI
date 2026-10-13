/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.azure.ai.openai.models.ReasoningEffortValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.oracle.bmc.Region;
import com.oracle.bmc.generativeaiinference.model.ServingMode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.community.model.oracle.oci.genai.OciGenAiChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.bedrock.BedrockCachePointPlacement;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockGuardrailConfiguration;
import dev.langchain4j.model.bedrock.BedrockServiceTier;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.googleai.GeminiHarmBlockThreshold;
import dev.langchain4j.model.googleai.GeminiHarmCategory;
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatRequestParameters;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.vertexai.gemini.HarmCategory;
import dev.langchain4j.model.vertexai.gemini.SafetyThreshold;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every provider parameter added for the model-settings work reaches the
 * provider client, and a malformed value falls back to the provider default
 * instead of failing the build.
 * <p>
 * Where langchain4j builds a request we can intercept, the assertion is on the
 * request itself ({@link RecordingHttpClient}); where the client cannot be
 * built offline (Vertex: GCP credentials, OCI: {@code ~/.oci/config}), on the
 * populated langchain4j builder; otherwise on the model's
 * {@code defaultRequestParameters}, which is what each request is built from.
 */
@DisplayName("provider parameter coverage")
class ProviderParameterCoverageTest {

    private static Map<String, String> params(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params() takes key/value pairs, got " + keyValues.length + " arguments");
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Sends one turn through the model; the recording client fails it after
     * capture.
     */
    private static void send(ChatModel model, ChatRequest request) {
        assertThrows(RuntimeException.class, () -> model.chat(request));
    }

    private static ChatRequest hello() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hello"))).build();
    }

    /** A private field, searched up the class hierarchy. */
    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("langchain4j no longer has a field '" + name + "' on " + target.getClass().getName());
    }

    /** Swallows the recorded transport failure of a streaming call. */
    private static final class NoOpStreamingHandler implements StreamingChatResponseHandler {
        @Override
        public void onPartialResponse(String partialResponse) {
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
        }

        @Override
        public void onError(Throwable error) {
        }
    }

    // ==================== OpenAI ====================

    @Nested
    @DisplayName("openai")
    class OpenAi {

        private final OpenAILanguageModelBuilder builder = new OpenAILanguageModelBuilder();

        @Test
        @DisplayName("topP, stop, reasoningEffort, maxCompletionTokens, verbosity and customParameters reach the body; customHeaders the headers")
        void parametersReachTheRequest() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-test", "modelName", "gpt-5", "topP", "0.4",
                    "stopSequences", "[\"END\",\"STOP, NOW\"]", "reasoningEffort", "low", "maxCompletionTokens", "777",
                    "verbosity", "high", "customParameters", "{\"foo\":{\"bar\":1}}",
                    "customHeaders", "{\"X-Trace\":\"abc\"}");

            send(builder.build(p, Map.of(), http), hello());

            JsonNode body = http.body();
            assertEquals(0.4, body.path("top_p").asDouble(), 1e-9, body.toString());
            assertEquals("END", body.path("stop").get(0).asText(), body.toString());
            assertEquals("STOP, NOW", body.path("stop").get(1).asText(), "a JSON array keeps commas inside items");
            assertEquals("low", body.path("reasoning_effort").asText(), body.toString());
            assertEquals(777, body.path("max_completion_tokens").asInt(), body.toString());
            assertEquals("high", body.path("verbosity").asText(), "verbosity has no setter and travels as a body field");
            assertEquals(1, body.path("foo").path("bar").asInt(), "customParameters become top-level body fields");
            assertEquals("abc", http.header("X-Trace"));
        }

        @Test
        @DisplayName("the user's customParameters win over EDDI's own body fields")
        void userCustomParametersWin() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-test", "modelName", "gpt-5", "verbosity", "low",
                    "customParameters", "{\"verbosity\":\"high\"}");

            send(builder.build(p, Map.of(), http), hello());

            assertEquals("high", http.body().path("verbosity").asText());
        }

        @Test
        @DisplayName("the user's customParameters merge into nested provider fields instead of replacing them")
        void userCustomParametersDeepMerge() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-test", "modelName", "gpt-4o", "customParameters", "{\"provider\":{\"zdr\":true}}");

            send(builder.build(p, Map.of("provider", Map.of("order", List.of("anthropic"))), http), hello());

            JsonNode provider = http.body().path("provider");
            assertEquals("anthropic", provider.path("order").get(0).asText(), "the provider's own nested field survives: " + provider);
            assertTrue(provider.path("zdr").asBoolean(), "the user's nested field is added: " + provider);
        }

        @Test
        @DisplayName("malformed values fall back to the provider default instead of failing the build")
        void malformedValuesFallBack() {
            Map<String, String> p = params("apiKey", "sk-test", "modelName", "gpt-4o", "topP", "high",
                    "maxCompletionTokens", "lots", "stopSequences", "[unterminated", "customHeaders", "not json",
                    "customParameters", "{oops", "parallelToolCalls", "maybe");

            var defaults = (OpenAiChatRequestParameters) builder.build(p).defaultRequestParameters();

            assertNull(defaults.topP());
            assertNull(defaults.maxCompletionTokens());
            assertTrue(defaults.stopSequences() == null || defaults.stopSequences().isEmpty());
            assertTrue(defaults.customParameters() == null || defaults.customParameters().isEmpty());
            assertNull(defaults.parallelToolCalls());
        }
    }

    // ==================== Anthropic ====================

    @Nested
    @DisplayName("anthropic")
    class Anthropic {

        private final AnthropicLanguageModelBuilder builder = new AnthropicLanguageModelBuilder();

        private ChatRequest withSystemAndTool() {
            return ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("You are terse."), UserMessage.from("hello")))
                    .toolSpecifications(ToolSpecification.builder().name("lookup").description("Looks a thing up").build())
                    .build();
        }

        @Test
        @DisplayName("thinkingType/thinkingBudgetTokens, effort, cache flags and stopSequences reach the request")
        void parametersReachTheRequest() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5", "thinkingType", "enabled",
                    "thinkingBudgetTokens", "2048", "effort", "medium", "cacheSystemMessages", "true", "cacheTools", "true",
                    "stopSequences", "DONE,HALT");

            send(builder.build(p, http), withSystemAndTool());

            JsonNode body = http.body();
            assertEquals("enabled", body.path("thinking").path("type").asText(), body.toString());
            assertEquals(2048, body.path("thinking").path("budget_tokens").asInt(), body.toString());
            assertEquals("medium", body.path("output_config").path("effort").asText(), "effort travels as output_config.effort");
            assertEquals("DONE", body.path("stop_sequences").get(0).asText(), body.toString());
            assertEquals("HALT", body.path("stop_sequences").get(1).asText(), body.toString());
            assertTrue(body.path("system").toString().contains("cache_control"), "cacheSystemMessages: " + body.path("system"));
            assertTrue(body.path("tools").toString().contains("cache_control"), "cacheTools: " + body.path("tools"));
        }

        @Test
        @DisplayName("the streaming client sends the same settings as the synchronous one")
        void streamingParity() {
            Map<String, String> p = params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5", "thinkingType", "enabled",
                    "thinkingBudgetTokens", "2048", "effort", "medium", "cacheSystemMessages", "true", "cacheTools", "true",
                    "stopSequences", "DONE,HALT", "temperature", "1", "maxTokens", "5000");
            RecordingHttpClient sync = new RecordingHttpClient();
            send(builder.build(p, sync), withSystemAndTool());
            RecordingHttpClient streaming = new RecordingHttpClient();
            builder.buildStreaming(p, streaming).chat(withSystemAndTool(), new NoOpStreamingHandler());

            JsonNode expected = sync.body();
            JsonNode actual = streaming.body();
            for (String field : List.of("thinking", "output_config", "stop_sequences", "system", "tools", "max_tokens", "temperature")) {
                assertEquals(expected.path(field), actual.path(field), "streaming body differs in '" + field + "': " + actual);
            }
            assertTrue(actual.path("stream").asBoolean(), actual.toString());
        }

        @Test
        @DisplayName("the user's customParameters merge into output_config instead of replacing the effort")
        void userCustomParametersDeepMerge() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5", "effort", "medium",
                    "customParameters", "{\"output_config\":{\"format\":\"json\"}}");

            send(builder.build(p, http), withSystemAndTool());

            JsonNode outputConfig = http.body().path("output_config");
            assertEquals("medium", outputConfig.path("effort").asText(), outputConfig.toString());
            assertEquals("json", outputConfig.path("format").asText(), outputConfig.toString());
        }

        @Test
        @DisplayName("without the cache flags nothing is marked for caching")
        void noCacheFlagsNoCacheControl() {
            RecordingHttpClient http = new RecordingHttpClient();

            send(builder.build(params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5"), http), withSystemAndTool());

            assertFalse(http.body().toString().contains("cache_control"), http.body().toString());
            assertTrue(http.body().path("output_config").isMissingNode());
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = params("apiKey", "sk-ant", "modelName", "claude-sonnet-4-5", "thinkingBudgetTokens", "big",
                    "cacheSystemMessages", "yes", "customParameters", "[1,2]");

            send(builder.build(p, http), withSystemAndTool());

            JsonNode body = http.body();
            assertTrue(body.path("thinking").isMissingNode() || body.path("thinking").path("budget_tokens").isMissingNode(),
                    body.toString());
            assertFalse(body.path("system").toString().contains("cache_control"));
        }
    }

    // ==================== Gemini (AI Studio) ====================

    @Nested
    @DisplayName("gemini")
    class Gemini {

        private final GeminiLanguageModelBuilder builder = new GeminiLanguageModelBuilder();

        private JsonNode generationConfig(Map<String, String> p) {
            RecordingHttpClient http = new RecordingHttpClient();
            send(builder.build(p, http), hello());
            return http.body().path("generationConfig");
        }

        @Test
        @DisplayName("thinkingLevel reaches thinkingConfig")
        void thinkingLevel() {
            JsonNode config = generationConfig(params("apiKey", "k", "modelName", "gemini-3-pro", "thinkingLevel", "HIGH"));

            assertEquals("high", config.path("thinkingConfig").path("thinkingLevel").asText().toLowerCase(), config.toString());
        }

        @Test
        @DisplayName("thinkingBudget alone reaches thinkingConfig")
        void thinkingBudget() {
            JsonNode config = generationConfig(params("apiKey", "k", "modelName", "gemini-2.5-flash", "thinkingBudget", "512"));

            assertEquals(512, config.path("thinkingConfig").path("thinkingBudget").asInt(), config.toString());
        }

        @Test
        @DisplayName("thinkingLevel and thinkingBudget are exclusive: the level wins and the budget is not sent")
        void levelWinsOverBudget() {
            JsonNode thinking = generationConfig(params("apiKey", "k", "modelName", "gemini-3-pro", "thinkingLevel", "low",
                    "thinkingBudget", "512")).path("thinkingConfig");

            assertEquals("low", thinking.path("thinkingLevel").asText().toLowerCase(), thinking.toString());
            assertTrue(thinking.path("thinkingBudget").isMissingNode(), thinking.toString());
        }

        @Test
        @DisplayName("an unknown thinkingLevel is dropped, not sent")
        void unknownThinkingLevelDropped() {
            JsonNode config = generationConfig(params("apiKey", "k", "modelName", "gemini-3-pro", "thinkingLevel", "extreme"));

            assertTrue(config.path("thinkingConfig").isMissingNode(), config.toString());
        }

        @Test
        @DisplayName("mediaResolution accepts the short name and reaches the request")
        void mediaResolution() {
            JsonNode config = generationConfig(params("apiKey", "k", "modelName", "gemini-2.5-flash", "mediaResolution", "low"));

            assertEquals("MEDIA_RESOLUTION_LOW", config.path("mediaResolution").asText(), config.toString());
        }

        @Test
        @DisplayName("an unknown mediaResolution is ignored instead of failing the build")
        void unknownMediaResolution() {
            JsonNode config = generationConfig(params("apiKey", "k", "modelName", "gemini-2.5-flash", "mediaResolution", "ultra"));

            assertTrue(config.path("mediaResolution").isMissingNode(), config.toString());
        }

        @Test
        @DisplayName("safetySettings: short and prefixed category names resolve, invalid entries are skipped")
        void safetySettingsParsing() {
            var parsed = GeminiLanguageModelBuilder.safetySettings(params("safetySettings",
                    "{\"HARASSMENT\":\"BLOCK_ONLY_HIGH\",\"HARM_CATEGORY_HATE_SPEECH\":\"block-none\","
                            + "\"NOT_A_CATEGORY\":\"BLOCK_NONE\",\"DANGEROUS_CONTENT\":\"SOMETIMES\"}"));

            assertEquals(Map.of(GeminiHarmCategory.HARM_CATEGORY_HARASSMENT, GeminiHarmBlockThreshold.BLOCK_ONLY_HIGH,
                    GeminiHarmCategory.HARM_CATEGORY_HATE_SPEECH, GeminiHarmBlockThreshold.BLOCK_NONE), parsed);
            assertNull(GeminiLanguageModelBuilder.safetySettings(params("safetySettings", "{\"X\":\"Y\"}")));
            assertNull(GeminiLanguageModelBuilder.safetySettings(params("safetySettings", "not json")));
        }

        @Test
        @DisplayName("safetySettings reach the request")
        void safetySettingsReachTheRequest() {
            RecordingHttpClient http = new RecordingHttpClient();
            send(builder.build(params("apiKey", "k", "modelName", "gemini-2.5-flash", "safetySettings",
                    "{\"HARASSMENT\":\"BLOCK_ONLY_HIGH\"}"), http), hello());

            String safety = http.body().path("safetySettings").toString();
            assertTrue(safety.contains("HARM_CATEGORY_HARASSMENT") && safety.contains("BLOCK_ONLY_HIGH"), safety);
        }
    }

    // ==================== Vertex Gemini ====================

    @Nested
    @DisplayName("gemini-vertex")
    class Vertex {

        private VertexAiGeminiChatModel.VertexAiGeminiChatModelBuilder applied(Map<String, String> p) {
            return VertexGeminiLanguageModelBuilder.applyTo(VertexAiGeminiChatModel.builder(), p);
        }

        @Test
        @DisplayName("maxOutputTokens, topP, topK, seed, safetySettings, useGoogleSearch, labels, apiEndpoint, customHeaders reach the builder")
        void parametersReachTheBuilder() {
            var b = applied(params("modelId", "gemini-2.5-pro", "maxOutputTokens", "900", "topP", "0.5", "topK", "12",
                    "seed", "42", "safetySettings", "{\"HARASSMENT\":\"BLOCK_ONLY_HIGH\"}", "useGoogleSearch", "true",
                    "labels", "{\"team\":\"core\"}", "apiEndpoint", "europe-west4-aiplatform.googleapis.com:443",
                    "customHeaders", "{\"X-Trace\":\"abc\"}"));

            assertEquals(900, field(b, "maxOutputTokens"));
            assertEquals(0.5f, field(b, "topP"));
            assertEquals(12, field(b, "topK"));
            assertEquals(42, field(b, "seed"));
            assertEquals(Map.of(HarmCategory.HARM_CATEGORY_HARASSMENT, SafetyThreshold.BLOCK_ONLY_HIGH), field(b, "safetySettings"));
            assertEquals(Boolean.TRUE, field(b, "useGoogleSearch"));
            assertEquals(Map.of("team", "core"), field(b, "labels"));
            assertEquals("europe-west4-aiplatform.googleapis.com:443", field(b, "apiEndpoint"));
            assertEquals(Map.of("X-Trace", "abc"), field(b, "customHeaders"));
        }

        @Test
        @DisplayName("malformed values leave the Vertex defaults in place")
        void malformedValuesFallBack() {
            var b = applied(params("maxOutputTokens", "many", "topP", "x", "seed", "1.5", "useGoogleSearch", "ja",
                    "labels", "[1]", "safetySettings", "{\"HARASSMENT\":\"UNRECOGNIZED\",\"UNSPECIFIED\":\"BLOCK_NONE\"}",
                    "logRequests", "ture"));

            assertNull(field(b, "maxOutputTokens"));
            assertNull(field(b, "topP"));
            assertNull(field(b, "seed"));
            assertNull(field(b, "useGoogleSearch"));
            assertNull(field(b, "labels"));
            assertNull(field(b, "safetySettings"), "protobuf placeholders cannot be sent and must be dropped");
            assertNull(field(b, "logRequests"), "a mistyped boolean must not silently mean false");
        }

        @Test
        @DisplayName("an apiEndpoint on the cloud metadata service is refused")
        void metadataEndpointRefused() {
            assertThrows(IllegalArgumentException.class, () -> applied(params("apiEndpoint", "169.254.169.254")));
            assertThrows(IllegalArgumentException.class, () -> applied(params("apiEndpoint", "http://metadata.google.internal")));
        }

        @Test
        @DisplayName("every new key is declared, and maxOutputTokens is the one FormatRetryRunner reads")
        void declared() {
            assertTrue(new VertexGeminiLanguageModelBuilder().recognisedParameters().containsAll(List.of("maxOutputTokens",
                    "topP", "topK", "seed", "safetySettings", "useGoogleSearch", "labels", "apiEndpoint", "customHeaders")));
        }
    }

    // ==================== Azure OpenAI ====================

    @Nested
    @DisplayName("azure-openai")
    class Azure {

        private final AzureOpenAiLanguageModelBuilder builder = new AzureOpenAiLanguageModelBuilder();

        private Map<String, String> base(String... more) {
            Map<String, String> p = params("apiKey", "azure-key", "deploymentName", "prod-chat", "endpoint",
                    "https://my-resource.openai.azure.com/");
            p.putAll(params(more));
            return p;
        }

        @Test
        @DisplayName("sampling, seed, user, penalties, maxCompletionTokens, reasoningEffort, logitBias, strictJsonSchema reach the model")
        void parametersReachTheModel() {
            ChatModel model = builder.build(base("topP", "0.3", "stopSequences", "A,B", "seed", "12345678901",
                    "frequencyPenalty", "0.2", "presencePenalty", "0.1", "user", "u-1", "maxCompletionTokens", "500",
                    "reasoningEffort", "high", "logitBias", "{\"50256\":-100}", "strictJsonSchema", "true",
                    "baseModel", "o3-mini"));

            var defaults = model.defaultRequestParameters();
            assertEquals(0.3, defaults.topP(), 1e-9);
            assertEquals(List.of("A", "B"), defaults.stopSequences());
            assertEquals(0.2, defaults.frequencyPenalty(), 1e-9);
            assertEquals(0.1, defaults.presencePenalty(), 1e-9);
            assertEquals(12345678901L, field(model, "seed"), "Azure's seed is a long");
            assertEquals("u-1", field(model, "user"));
            assertEquals(500, field(model, "maxCompletionTokens"));
            assertEquals(ReasoningEffortValue.HIGH, field(model, "reasoningEffort"));
            assertEquals(Map.of("50256", -100), field(model, "logitBias"));
            assertEquals(Boolean.TRUE, field(model, "strictJsonSchema"));
        }

        @Test
        @DisplayName("the streaming client gets the same settings")
        void streamingGetsTheSameSettings() {
            var model = builder.buildStreaming(base("topP", "0.3", "seed", "7", "user", "u-1", "reasoningEffort", "low"));

            assertEquals(0.3, model.defaultRequestParameters().topP(), 1e-9);
            assertEquals(7L, field(model, "seed"));
            assertEquals("u-1", field(model, "user"));
            assertEquals(ReasoningEffortValue.LOW, field(model, "reasoningEffort"));
        }

        @Test
        @DisplayName("serviceVersion and customHeaders are read; baseModel is recognised but never part of what is sent")
        void settingsAndBaseModel() {
            var s = AzureOpenAiLanguageModelBuilder.read(base("serviceVersion", "2024-10-21", "customHeaders",
                    "{\"X-Trace\":\"abc\"}", "baseModel", "gpt-4o"));

            assertEquals("2024-10-21", s.serviceVersion());
            assertEquals(Map.of("X-Trace", "abc"), s.customHeaders());
            assertFalse(s.toString().contains("gpt-4o"), "baseModel must not reach the client: " + s);
            assertTrue(builder.recognisedParameters().contains("baseModel"));
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            ChatModel model = builder.build(base("topP", "x", "seed", "1e3", "logitBias", "{\"1\":\"a\"}",
                    "strictJsonSchema", "si", "maxCompletionTokens", "n"));

            assertNull(model.defaultRequestParameters().topP());
            assertNull(field(model, "seed"));
            assertTrue(field(model, "logitBias") == null || ((Map<?, ?>) field(model, "logitBias")).isEmpty());
            assertEquals(field(builder.build(base()), "strictJsonSchema"), field(model, "strictJsonSchema"));
            assertNull(field(model, "maxCompletionTokens"));
        }

        @Test
        @DisplayName("an endpoint on the cloud metadata service is refused")
        void metadataEndpointRefused() {
            Map<String, String> p = base();
            p.put("endpoint", "http://169.254.169.254/");
            assertThrows(IllegalArgumentException.class, () -> builder.build(p));
        }
    }

    // ==================== Mistral ====================

    @Nested
    @DisplayName("mistral")
    class Mistral {

        private final MistralAiLanguageModelBuilder builder = new MistralAiLanguageModelBuilder();

        @Test
        @DisplayName("topP, seed (randomSeed), safePrompt, stop, penalties and thinking flags reach the model")
        void parametersReachTheModel() {
            var defaults = (MistralAiChatRequestParameters) builder.build(params("apiKey", "k", "modelName", "mistral-large-latest",
                    "topP", "0.6", "seed", "99", "safePrompt", "true", "stopSequences", "X,Y", "frequencyPenalty", "0.3",
                    "presencePenalty", "0.4", "returnThinking", "true", "sendThinking", "true")).defaultRequestParameters();

            assertEquals(0.6, defaults.topP(), 1e-9);
            assertEquals(99, defaults.randomSeed());
            assertEquals(Boolean.TRUE, defaults.safePrompt());
            assertEquals(List.of("X", "Y"), defaults.stopSequences());
            assertEquals(0.3, defaults.frequencyPenalty(), 1e-9);
            assertEquals(0.4, defaults.presencePenalty(), 1e-9);
            assertEquals(Boolean.TRUE, defaults.returnThinking());
            assertEquals(Boolean.TRUE, defaults.sendThinking());
        }

        @Test
        @DisplayName("the streaming client gets the same settings")
        void streaming() {
            var defaults = (MistralAiChatRequestParameters) builder.buildStreaming(params("apiKey", "k", "topP", "0.6",
                    "seed", "99", "safePrompt", "false")).defaultRequestParameters();

            assertEquals(0.6, defaults.topP(), 1e-9);
            assertEquals(99, defaults.randomSeed());
            assertEquals(Boolean.FALSE, defaults.safePrompt());
        }

        @Test
        @DisplayName("baseUrl and customHeaders reach the request")
        void baseUrlAndHeaders() {
            RecordingHttpClient http = new RecordingHttpClient();
            send(builder.build(params("apiKey", "k", "modelName", "mistral-small", "baseUrl", "https://mistral.example.com/v1",
                    "customHeaders", "{\"X-Trace\":\"abc\"}", "seed", "5"), http), hello());

            assertTrue(http.request().url().startsWith("https://mistral.example.com/v1"), http.request().url());
            assertEquals("abc", http.header("X-Trace"));
            assertEquals(5, http.body().path("random_seed").asInt(), http.body().toString());
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            var defaults = (MistralAiChatRequestParameters) builder.build(params("apiKey", "k", "topP", "?", "seed", "x",
                    "safePrompt", "maybe")).defaultRequestParameters();

            assertNull(defaults.topP());
            assertNull(defaults.randomSeed());
            assertNull(defaults.safePrompt());
        }

        @Test
        @DisplayName("a baseUrl on the cloud metadata service is refused")
        void metadataBaseUrlRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(params("apiKey", "k", "baseUrl", "http://169.254.169.254/latest")));
        }
    }

    // ==================== Ollama ====================

    @Nested
    @DisplayName("ollama")
    class Ollama {

        private final OllamaLanguageModelBuilder builder = new OllamaLanguageModelBuilder();

        private Map<String, String> full() {
            return params("model", "llama3", "baseUrl", "http://localhost:11434", "numCtx", "8192", "minP", "0.05",
                    "repeatPenalty", "1.1", "repeatLastN", "64", "seed", "3", "stopSequences", "[\"<|end|>\"]", "truncate", "false");
        }

        private void assertFull(OllamaChatRequestParameters defaults) {
            assertEquals(8192, defaults.numCtx());
            assertEquals(0.05, defaults.minP(), 1e-9);
            assertEquals(1.1, defaults.repeatPenalty(), 1e-9);
            assertEquals(64, defaults.repeatLastN());
            assertEquals(3, defaults.seed());
            assertEquals(List.of("<|end|>"), defaults.stopSequences());
            assertEquals(Boolean.FALSE, defaults.truncate());
        }

        @Test
        @DisplayName("numCtx, minP, repeatPenalty, repeatLastN, seed, stop and truncate reach the model")
        void parametersReachTheModel() {
            assertFull((OllamaChatRequestParameters) builder.build(full()).defaultRequestParameters());
        }

        @Test
        @DisplayName("the streaming client gets the same settings")
        void streaming() {
            assertFull((OllamaChatRequestParameters) builder.buildStreaming(full()).defaultRequestParameters());
        }

        @Test
        @DisplayName("customHeaders reach the request")
        void customHeaders() {
            RecordingHttpClient http = new RecordingHttpClient();
            Map<String, String> p = full();
            p.put("customHeaders", "{\"Authorization\":\"Bearer proxy-token\"}");

            send(builder.build(p, http), hello());

            assertEquals("Bearer proxy-token", http.header("Authorization"));
            assertEquals(8192, http.body().path("options").path("num_ctx").asInt(), http.body().toString());
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            var defaults = (OllamaChatRequestParameters) builder.build(params("model", "llama3", "baseUrl", "http://localhost:11434", "numCtx", "big",
                    "minP", "-", "truncate", "nope", "logRequests", "ture")).defaultRequestParameters();

            assertNull(defaults.numCtx());
            assertNull(defaults.minP());
            assertNull(defaults.truncate());
        }
    }

    // ==================== Bedrock ====================

    @Nested
    @DisplayName("bedrock")
    class Bedrock {

        private final BedrockLanguageModelBuilder builder = new BedrockLanguageModelBuilder();

        private Map<String, String> base(String... more) {
            Map<String, String> p = params("modelId", "anthropic.claude-sonnet-4-5", "region", "us-east-1");
            p.putAll(params(more));
            return p;
        }

        @Test
        @DisplayName("a lone topP still produces request parameters (they used to need temperature or maxTokens)")
        void loneTopPIsNotDropped() {
            var defaults = (BedrockChatRequestParameters) builder.build(base("topP", "0.8")).defaultRequestParameters();

            assertEquals(0.8, defaults.topP(), 1e-9);
        }

        @Test
        @DisplayName("every Bedrock setting lands in the one defaultRequestParameters object")
        void parametersReachTheModel() {
            var defaults = (BedrockChatRequestParameters) builder.build(base("temperature", "0.2", "maxTokens", "4000",
                    "topP", "0.9", "stopSequences", "END", "reasoningBudgetTokens", "2048", "promptCachePlacement",
                    "after_system", "promptCacheTtl", "1h", "guardrailId", "gr-1", "guardrailVersion", "3",
                    "guardrailStreamMode", "async", "serviceTier", "priority", "additionalModelRequestFields",
                    "{\"top_k\":40}", "requestMetadata", "{\"tenant\":\"acme\"}")).defaultRequestParameters();

            assertEquals(0.2, defaults.temperature(), 1e-9);
            assertEquals(4000, defaults.maxOutputTokens());
            assertEquals(0.9, defaults.topP(), 1e-9);
            assertEquals(List.of("END"), defaults.stopSequences());
            assertEquals(Map.of("type", "enabled", "budget_tokens", 2048), defaults.additionalModelRequestFields().get("reasoning_config"));
            assertEquals(40, defaults.additionalModelRequestFields().get("top_k"));
            assertEquals(BedrockCachePointPlacement.AFTER_SYSTEM, defaults.cachePointPlacement());
            assertEquals(CacheTTL.VALUE_1_H, defaults.cacheTtl());
            BedrockGuardrailConfiguration guardrail = defaults.bedrockGuardrailConfiguration();
            assertNotNull(guardrail);
            assertTrue(guardrail.toString().contains("gr-1") && guardrail.toString().contains("3"), guardrail.toString());
            assertEquals(BedrockGuardrailConfiguration.ProcessingMode.ASYNC, field(guardrail, "streamProcessingMode"));
            assertEquals(BedrockServiceTier.PRIORITY, defaults.serviceTier());
            assertEquals(Map.of("tenant", "acme"), defaults.requestMetadata());
        }

        @Test
        @DisplayName("reasoningEffort turns on adaptive reasoning, and wins over a budget")
        void adaptiveReasoning() {
            var fields = ((BedrockChatRequestParameters) builder.build(base("reasoningEffort", "high", "reasoningBudgetTokens",
                    "1024")).defaultRequestParameters()).additionalModelRequestFields();

            assertEquals(Map.of("type", "adaptive"), fields.get("reasoning_config"));
            assertEquals(Map.of("effort", "high"), fields.get("output_config"));
        }

        @Test
        @DisplayName("the user's additionalModelRequestFields win per key over the reasoning switch")
        void userFieldsWin() {
            var fields = ((BedrockChatRequestParameters) builder.build(base("reasoningBudgetTokens", "1024",
                    "additionalModelRequestFields", "{\"reasoning_config\":{\"type\":\"disabled\"}}")).defaultRequestParameters())
                    .additionalModelRequestFields();

            assertEquals(Map.of("type", "disabled"), fields.get("reasoning_config"));
        }

        @Test
        @DisplayName("returnThinking, sendThinking and customHeaders reach both clients")
        void clientSettings() {
            Map<String, String> p = base("returnThinking", "true", "sendThinking", "true", "customHeaders", "{\"X-Trace\":\"abc\"}");

            for (Object model : List.of(builder.build(p), builder.buildStreaming(p))) {
                assertEquals(true, field(model, "returnThinking"));
                assertEquals(true, field(model, "sendThinking"));
                assertEquals(Map.of("X-Trace", "abc"), ((Supplier<?>) field(model, "customHeadersSupplier")).get());
            }
            var streamingDefaults = (BedrockChatRequestParameters) builder.buildStreaming(base("serviceTier", "flex"))
                    .defaultRequestParameters();
            assertEquals(BedrockServiceTier.FLEX, streamingDefaults.serviceTier());
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            var defaults = (BedrockChatRequestParameters) builder.build(base("topP", "x", "serviceTier", "turbo",
                    "promptCachePlacement", "everywhere", "promptCacheTtl", "1d", "guardrailId", "gr-1",
                    "additionalModelRequestFields", "{bad", "reasoningBudgetTokens", "lots")).defaultRequestParameters();

            assertNull(defaults.topP());
            assertNull(defaults.serviceTier());
            assertNull(defaults.cachePointPlacement());
            assertNull(defaults.bedrockGuardrailConfiguration(), "a guardrail id without a version is not applied");
            assertTrue(defaults.additionalModelRequestFields() == null || defaults.additionalModelRequestFields().isEmpty());
        }

        @Test
        @DisplayName("nothing configured leaves no request parameters at all")
        void nothingConfigured() {
            assertNull(BedrockLanguageModelBuilder.buildRequestParameters(base()));
        }
    }

    // ==================== HuggingFace ====================

    @Nested
    @DisplayName("huggingface")
    class HuggingFace {

        private final HuggingFaceLanguageModelBuilder builder = new HuggingFaceLanguageModelBuilder();

        @Test
        @DisplayName("modelId, baseUrl and returnFullText reach the builder")
        void parametersReachTheBuilder() {
            var b = HuggingFaceLanguageModelBuilder.applyTo(HuggingFaceChatModel.builder(), params("accessToken", "hf_x",
                    "modelId", "/HuggingFaceTB/SmolLM3-3B", "baseUrl", "https://tgi.example.com/", "returnFullText", "false"));

            assertEquals("HuggingFaceTB/SmolLM3-3B", field(b, "modelId"));
            assertEquals("https://tgi.example.com/", field(b, "baseUrl"));
            assertEquals(Boolean.FALSE, field(b, "returnFullText"));
        }

        @Test
        @DisplayName("a model builds from accessToken + modelId (langchain4j requires modelId)")
        void builds() {
            assertNotNull(builder.build(params("accessToken", "hf_x", "modelId", "HuggingFaceTB/SmolLM3-3B")));
        }

        @Test
        @DisplayName("malformed values and a metadata baseUrl")
        void malformed() {
            var b = HuggingFaceLanguageModelBuilder.applyTo(HuggingFaceChatModel.builder(), params("returnFullText", "nah",
                    "waitForModel", "ture"));
            var defaults = HuggingFaceChatModel.builder();
            assertEquals(field(defaults, "returnFullText"), field(b, "returnFullText"));
            assertEquals(field(defaults, "waitForModel"), field(b, "waitForModel"));
            assertThrows(IllegalArgumentException.class, () -> HuggingFaceLanguageModelBuilder.applyTo(HuggingFaceChatModel.builder(),
                    params("baseUrl", "http://169.254.169.254/")));
        }
    }

    // ==================== Oracle GenAI ====================

    @Nested
    @DisplayName("oracle-genai")
    class Oracle {

        private OciGenAiChatModel.Builder applied(Map<String, String> p) {
            return OracleGenAiLanguageModelBuilder.applyTo(OciGenAiChatModel.builder(), p);
        }

        @Test
        @DisplayName("region, topP, topK, seed, stop, penalties and servingType reach the builder")
        void parametersReachTheBuilder() {
            var b = applied(params("region", "eu-frankfurt-1", "topP", "0.7", "topK", "20", "seed", "11", "stopSequences",
                    "END", "frequencyPenalty", "0.1", "presencePenalty", "0.2", "servingType", "dedicated"));

            assertEquals(Region.EU_FRANKFURT_1, field(b, "region"));
            assertEquals(0.7, field(b, "topP"));
            assertEquals(20, field(b, "topK"));
            assertEquals(11, field(b, "seed"));
            assertEquals(List.of("END"), field(b, "stop"));
            assertEquals(0.1, field(b, "frequencyPenalty"));
            assertEquals(0.2, field(b, "presencePenalty"));
            assertEquals(ServingMode.ServingType.Dedicated, field(b, "servingType"));
        }

        @Test
        @DisplayName("malformed values fall back to the provider default")
        void malformedValuesFallBack() {
            var b = applied(params("region", "mars-north-1", "topK", "x", "servingType", "sometimes"));

            assertNull(field(b, "region"));
            assertNull(field(b, "topK"));
            assertEquals(field(OciGenAiChatModel.builder(), "servingType"), field(b, "servingType"));
        }
    }
}
