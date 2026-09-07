/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LLM provider {@link ILanguageModelBuilder} implementations.
 * <p>
 * These test that each builder produces a non-null ChatModel and
 * StreamingChatModel from a parameter map, exercising all parameter branches.
 * No actual API calls are made — the builders just configure client objects.
 */
@DisplayName("LanguageModelBuilders")
class LanguageModelBuildersTest {

    // ==================== OpenAI ====================

    @Nested
    @DisplayName("OpenAILanguageModelBuilder")
    class OpenAITests {

        private final OpenAILanguageModelBuilder builder = new OpenAILanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel with all parameters")
        void buildWithAllParams() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "gpt-4o");
            params.put("temperature", "0.7");
            params.put("timeout", "30000");
            params.put("logRequests", "true");
            params.put("logResponses", "false");
            params.put("responseFormat", "json");
            params.put("baseUrl", "https://api.openai.com/v1");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds ChatModel with minimal parameters")
        void buildMinimal() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "gpt-4o-mini");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel with all parameters")
        void buildStreamingAll() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "gpt-4o");
            params.put("temperature", "0.5");
            params.put("responseFormat", "json");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

    // ==================== Anthropic ====================

    @Nested
    @DisplayName("AnthropicLanguageModelBuilder")
    class AnthropicTests {

        private final AnthropicLanguageModelBuilder builder = new AnthropicLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel with explicit maxTokens, topP, topK")
        void buildExplicitParams() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "claude-sonnet-4-6");
            params.put("temperature", "0.3");
            params.put("timeout", "60000");
            params.put("maxTokens", "4096");
            params.put("topP", "0.9");
            params.put("topK", "40");
            params.put("logRequests", "true");
            params.put("logResponses", "true");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds ChatModel without maxTokens defaults to 16384")
        void buildDefaultMaxTokens() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "claude-sonnet-4-6");
            params.put("temperature", "0.3");

            ChatModel model = builder.build(params);
            assertNotNull(model);

            // Read the default through public API rather than reflection. There is no
            // 'maxTokens' field to reflect on: AnthropicChatModel stores the builder's
            // maxTokens as defaultRequestParameters.maxOutputTokens, so the old
            // getDeclaredField("maxTokens") always threw NoSuchFieldException and the
            // assertion inside the try was never reached — the test verified nothing
            // for as long as it has existed.
            //
            // This matters more than a tidy-up: langchain4j falls back to 1024 output
            // tokens when maxTokens is unset, which is the exact footgun
            // DEFAULT_MAX_TOKENS exists to avoid for extended-thinking models.
            assertEquals(Integer.valueOf(16384), model.defaultRequestParameters().maxOutputTokens());
        }

        // Lenient parsing of unparseable numeric parameters is covered by
        // ModelParameterValuesTest — that is where the logic lives, and it runs
        // without constructing a real HTTP client.

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "claude-sonnet-4-6");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

    // ==================== Ollama ====================

    @Nested
    @DisplayName("OllamaLanguageModelBuilder")
    class OllamaTests {

        private final OllamaLanguageModelBuilder builder = new OllamaLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel")
        void build() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "llama3");
            params.put("baseUrl", "http://localhost:11434");
            params.put("temperature", "0.7");
            params.put("timeout", "120000");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "llama3");
            params.put("baseUrl", "http://localhost:11434");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }

        /**
         * Ollama was the only one of the eleven builders that read no sampling
         * parameters at all — a configured {@code temperature} was silently dropped on
         * the default local provider the setup wizard steers new users towards.
         * Asserted through the model's own {@code defaultRequestParameters}, which is
         * exactly what the request is built from.
         */
        @Test
        @DisplayName("sampling parameters actually reach the model")
        void samplingParametersReachTheModel() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "llama3");
            params.put("baseUrl", "http://localhost:11434");
            params.put("temperature", "0.42");
            params.put("maxTokens", "1234");
            params.put("topP", "0.77");
            params.put("topK", "23");

            var defaults = builder.build(params).defaultRequestParameters();

            assertEquals(0.42, defaults.temperature(), 1e-9, "temperature must reach the model");
            assertEquals(Integer.valueOf(1234), defaults.maxOutputTokens(),
                    "maxTokens maps onto Ollama's num_predict");
            assertEquals(0.77, defaults.topP(), 1e-9);
            assertEquals(Integer.valueOf(23), defaults.topK());
        }

        /**
         * The knob that decides whether a first local-LLM agent looks alive.
         * <p>
         * A reasoning model left on its own default thinks before it answers, and the
         * reasoning is not part of the streamed content — so the chat window shows
         * nothing for many seconds and then the whole answer at once, which reads as a
         * hang. Asserted through {@code defaultRequestParameters}, since that is what
         * the outgoing request is built from.
         */
        @Test
        @DisplayName("think reaches the model, and unset leaves the model's own default")
        void thinkReachesTheModel() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "gemma3n:e4b");
            params.put("baseUrl", "http://localhost:11434");

            var unset = (OllamaChatRequestParameters) builder.build(params).defaultRequestParameters();
            assertNull(unset.think(), "unset must stay unset — it is a third, meaningful state");

            params.put("think", "false");
            var off = (OllamaChatRequestParameters) builder.build(params).defaultRequestParameters();
            assertEquals(Boolean.FALSE, off.think());

            params.put("think", "true");
            var on = (OllamaChatRequestParameters) builder.build(params).defaultRequestParameters();
            assertEquals(Boolean.TRUE, on.think());
        }

        @Test
        @DisplayName("think reaches the streaming model too — the only place it is visible")
        void thinkReachesTheStreamingModel() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "gemma3n:e4b");
            params.put("baseUrl", "http://localhost:11434");
            params.put("think", "false");

            var defaults = (OllamaChatRequestParameters) builder.buildStreaming(params).defaultRequestParameters();

            assertEquals(Boolean.FALSE, defaults.think());
        }

        @Test
        @DisplayName("think and returnThinking are recognised, so neither is warned about as unread")
        void thinkingParametersAreRecognised() {
            assertTrue(builder.recognisedParameters().contains("think"));
            assertTrue(builder.recognisedParameters().contains("returnThinking"));
        }

        @Test
        @DisplayName("an unparseable think leaves the default rather than pinning it to false")
        void unparseableThinkIsIgnored() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "gemma3n:e4b");
            params.put("baseUrl", "http://localhost:11434");
            params.put("think", "yes please");

            var defaults = (OllamaChatRequestParameters) builder.build(params).defaultRequestParameters();

            // Boolean.parseBoolean would have made this false, silently turning
            // reasoning off on a typo.
            assertNull(defaults.think());
        }

        @Test
        @DisplayName("sampling parameters reach the streaming model too")
        void samplingParametersReachTheStreamingModel() {
            Map<String, String> params = new HashMap<>();
            params.put("model", "llama3");
            params.put("baseUrl", "http://localhost:11434");
            params.put("temperature", "0.15");
            params.put("maxTokens", "99");

            var defaults = builder.buildStreaming(params).defaultRequestParameters();

            assertEquals(0.15, defaults.temperature(), 1e-9);
            assertEquals(Integer.valueOf(99), defaults.maxOutputTokens());
        }

        @Test
        @DisplayName("declares the parameters it reads")
        void declaresRecognisedParameters() {
            assertTrue(builder.recognisedParameters().containsAll(
                    Set.of("model", "baseUrl", "timeout", "temperature", "maxTokens", "topP", "topK")));
        }
    }

    // ==================== VertexGemini ====================

    @Nested
    @DisplayName("VertexGeminiLanguageModelBuilder")
    class VertexGeminiTests {

        private final VertexGeminiLanguageModelBuilder builder = new VertexGeminiLanguageModelBuilder();

        /**
         * The key was spelled {@code "modelID"} — capital D — while Bedrock and
         * HuggingFace use {@code modelId} and {@code LlmTask.resolveModelName} reads
         * {@code modelId}. A gemini-vertex task therefore built a model with no name
         * AND resolved a null model name downstream, so capability lookup, token
         * estimation and audit-ledger model naming all lost the model identity.
         * <p>
         * Asserted on the declared key rather than on a built model: constructing a
         * real {@code VertexAiGeminiChatModel} needs GCP credentials.
         */
        @Test
        @DisplayName("reads the model name from 'modelId', matching every other builder")
        void modelIdKeyIsSpelledLikeEverywhereElse() {
            assertTrue(builder.recognisedParameters().contains("modelId"),
                    "gemini-vertex must read the same 'modelId' key that LlmTask.resolveModelName looks for");
        }

        @Test
        @DisplayName("the canonical 'modelId' spelling is used")
        void readsCanonicalKey() {
            assertEquals("gemini-2.0-flash",
                    VertexGeminiLanguageModelBuilder.resolveModelId(Map.of("modelId", "gemini-2.0-flash")));
        }

        /**
         * Backward compatibility: while the constant read {@code "modelID"} that
         * spelling was the ONLY one that worked, so agent configs stored in MongoDB (or
         * shipped in an import ZIP) use it. Renaming the constant without a fallback
         * silently built a nameless Vertex model for every one of them.
         */
        @Test
        @DisplayName("a stored config using the legacy 'modelID' spelling still resolves a model name")
        void legacyModelIdKeyStillWorks() {
            assertEquals("gemini-1.5-pro",
                    VertexGeminiLanguageModelBuilder.resolveModelId(Map.of("modelID", "gemini-1.5-pro")),
                    "the pre-6.2.0 spelling must keep working or stored gemini-vertex configs lose their model name");
        }

        @Test
        @DisplayName("the canonical key wins when both spellings are present")
        void canonicalKeyWinsOverLegacy() {
            assertEquals("gemini-2.0-flash", VertexGeminiLanguageModelBuilder.resolveModelId(
                    Map.of("modelId", "gemini-2.0-flash", "modelID", "gemini-1.5-pro")));
        }

        @Test
        @DisplayName("the legacy spelling is declared, so it is not also reported as unrecognised")
        void legacyKeyIsDeclared() {
            assertTrue(builder.recognisedParameters().contains("modelID"),
                    "declaring it keeps the deprecation warning the only message an operator sees");
        }

        @Test
        @DisplayName("neither spelling present resolves to null rather than an empty name")
        void noModelIdResolvesToNull() {
            assertNull(VertexGeminiLanguageModelBuilder.resolveModelId(Map.of("projectId", "p")));
            assertNull(VertexGeminiLanguageModelBuilder.resolveModelId(Map.of("modelId", "")));
        }
    }

    // ==================== Unrecognised parameter warnings ====================

    /**
     * Nothing used to tell an agent designer that a configured parameter was being
     * dropped — see the Ollama case above, which went unnoticed precisely because
     * the failure mode is silent. Every builder now declares the keys it reads so
     * {@code ChatModelRegistry} can warn about the rest.
     */
    @Nested
    @DisplayName("every builder declares its parameters")
    class RecognisedParameterDeclarations {

        @Test
        @DisplayName("no shipped builder falls back to the opt-out empty set")
        void allBuildersDeclareTheirParameters() {
            List<ILanguageModelBuilder> builders = List.of(
                    new OpenAILanguageModelBuilder(), new AnthropicLanguageModelBuilder(),
                    new OllamaLanguageModelBuilder(), new MistralAiLanguageModelBuilder(),
                    new AzureOpenAiLanguageModelBuilder(), new GeminiLanguageModelBuilder(),
                    new VertexGeminiLanguageModelBuilder(), new BedrockLanguageModelBuilder(),
                    new JlamaLanguageModelBuilder(), new HuggingFaceLanguageModelBuilder(),
                    new OracleGenAiLanguageModelBuilder());

            for (ILanguageModelBuilder builder : builders) {
                assertFalse(builder.recognisedParameters().isEmpty(),
                        builder.getClass().getSimpleName() + " must declare the parameters it reads, "
                                + "otherwise the unrecognised-key warning is silently skipped for it");
            }
        }
    }

    // ==================== MistralAi ====================

    @Nested
    @DisplayName("MistralAiLanguageModelBuilder")
    class MistralTests {

        private final MistralAiLanguageModelBuilder builder = new MistralAiLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel")
        void build() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "test-key");
            params.put("modelName", "mistral-large");
            params.put("temperature", "0.5");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "test-key");
            params.put("modelName", "mistral-large");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

    // ==================== AzureOpenAI ====================

    @Nested
    @DisplayName("AzureOpenAiLanguageModelBuilder")
    class AzureTests {

        private final AzureOpenAiLanguageModelBuilder builder = new AzureOpenAiLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel")
        void build() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "azure-key");
            params.put("deploymentName", "gpt-4o");
            params.put("endpoint", "https://my-resource.openai.azure.com/");
            params.put("temperature", "0.5");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "azure-key");
            params.put("deploymentName", "gpt-4o");
            params.put("endpoint", "https://my-resource.openai.azure.com/");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

    // ==================== Gemini ====================

    @Nested
    @DisplayName("GeminiLanguageModelBuilder")
    class GeminiTests {

        private final GeminiLanguageModelBuilder builder = new GeminiLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel with all parameters")
        void buildAll() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "gemini-key");
            params.put("modelName", "gemini-2.0-flash");
            params.put("temperature", "0.7");
            params.put("maxOutputTokens", "4096");
            params.put("allowCodeExecution", "true");
            params.put("logRequestsAndResponses", "true");
            params.put("timeout", "30000");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "gemini-key");
            params.put("modelName", "gemini-2.0-flash");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

    // ==================== Bedrock ====================

    @Nested
    @DisplayName("BedrockLanguageModelBuilder")
    class BedrockTests {

        private final BedrockLanguageModelBuilder builder = new BedrockLanguageModelBuilder();

        @Test
        @DisplayName("builds ChatModel with all parameters")
        void buildAll() {
            Map<String, String> params = new HashMap<>();
            params.put("modelId", "anthropic.claude-v2");
            params.put("region", "us-east-1");
            params.put("temperature", "0.5");
            params.put("maxTokens", "2048");
            params.put("timeout", "60000");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds ChatModel without request parameters")
        void buildMinimal() {
            Map<String, String> params = new HashMap<>();
            params.put("modelId", "meta.llama3-70b-instruct-v1:0");
            params.put("region", "us-west-2");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

        @Test
        @DisplayName("builds StreamingChatModel")
        void buildStreaming() {
            Map<String, String> params = new HashMap<>();
            params.put("modelId", "anthropic.claude-v2");
            params.put("region", "eu-west-1");
            params.put("temperature", "0.3");

            StreamingChatModel model = builder.buildStreaming(params);
            assertNotNull(model);
        }
    }

}
