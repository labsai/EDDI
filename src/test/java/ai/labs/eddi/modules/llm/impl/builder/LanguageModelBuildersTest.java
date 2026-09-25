/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.jlama.JlamaChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    // ==================== Jlama ====================

    /**
     * Jlama is the one provider whose {@code build()} cannot be exercised here:
     * {@link JlamaChatModel}'s constructor downloads the weights from Hugging Face
     * and loads them into memory, so a test that called it would need network
     * access and several gigabytes of disk. That is why the parameter mapping is
     * factored into an {@code applyTo} that stops short of building, and why it is
     * asserted through the Jlama builder's {@code toString()} — which prints every
     * field, masking only the auth token.
     * <p>
     * Until this existed, Jlama's mapping was the least covered of the eleven
     * providers while being the only one where a dropped parameter can leave a pod
     * unable to load a model at all (see {@code modelCachePath}).
     */
    @Nested
    @DisplayName("JlamaLanguageModelBuilder")
    class JlamaTests {

        private final JlamaLanguageModelBuilder builder = new JlamaLanguageModelBuilder();

        private String applied(Map<String, String> params) {
            return JlamaLanguageModelBuilder.applyTo(JlamaChatModel.builder(), params).toString();
        }

        @Test
        @DisplayName("maps every recognised parameter onto the Jlama builder")
        void mapsEveryRecognisedParameter() {
            Map<String, String> params = new HashMap<>();
            params.put("modelName", "tjake/Llama-3.2-1B-Instruct-JQ4");
            params.put("authToken", "hf_secret");
            params.put("temperature", "0.7");
            params.put("maxTokens", "512");
            params.put("modelCachePath", "/models/jlama");
            params.put("quantizeModelAtRuntime", "true");
            params.put("workingDirectory", "/tmp/jlama-work");
            params.put("workingQuantizedType", "F32");

            String state = applied(params);

            assertTrue(state.contains("modelName=tjake/Llama-3.2-1B-Instruct-JQ4"), state);
            assertTrue(state.contains("temperature=0.7"), state);
            assertTrue(state.contains("maxTokens=512"), state);
            assertTrue(state.contains("quantizeModelAtRuntime=true"), state);
            // Path.of normalises separators per platform, so assert against a Path
            // rather than a literal that only holds on one OS.
            assertTrue(state.contains("modelCachePath=" + Path.of("/models/jlama")), state);
            assertTrue(state.contains("workingDirectory=" + Path.of("/tmp/jlama-work")), state);
            assertTrue(state.contains("workingQuantizedType=F32"), state);
        }

        /**
         * {@code modelCachePath} and {@code workingDirectory} are routed through
         * {@link ModelParameterValues#applyPath}, not a bare {@code Path.of}, precisely
         * so that a path the platform cannot express falls back to Jlama's own default
         * instead of throwing {@link java.nio.file.InvalidPathException} out of model
         * construction — the same failure mode {@code applyPath} exists to prevent for
         * every other caller.
         */
        @Test
        @DisplayName("an unusable modelCachePath or workingDirectory falls back to the Jlama default")
        void unusablePathsFallBackToDefaults() {
            // NUL is rejected by every platform's path parser.
            String withNul = "bad" + (char) 0 + "path";
            Map<String, String> params = new HashMap<>();
            params.put("modelCachePath", withNul);
            params.put("workingDirectory", withNul);

            String state = applied(params);

            assertTrue(state.contains("modelCachePath=null"), state);
            assertTrue(state.contains("workingDirectory=null"), state);
        }

        /**
         * Every key this builder declares must actually reach the Jlama builder.
         * {@code recognisedParameters()} is what suppresses the "parameter has no
         * effect" warning, so a key listed there but never read is worse than one that
         * was never listed at all: the agent designer is actively reassured their
         * setting is fine.
         */
        @Test
        @DisplayName("every declared parameter changes the resulting builder state")
        void everyDeclaredParameterIsActuallyRead() {
            Map<String, String> values = Map.of(
                    "modelName", "some-model",
                    "authToken", "hf_secret",
                    "temperature", "0.25",
                    "maxTokens", "99",
                    "modelCachePath", "/cache",
                    "quantizeModelAtRuntime", "true",
                    "workingDirectory", "/work",
                    "workingQuantizedType", "F32");

            assertEquals(values.keySet(), builder.recognisedParameters(),
                    "this test enumerates the declared parameters; update both together");

            String empty = applied(new HashMap<>());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                Map<String, String> one = new HashMap<>();
                one.put(entry.getKey(), entry.getValue());
                assertNotEquals(empty, applied(one),
                        "'" + entry.getKey() + "' is declared in recognisedParameters() but does not change the"
                                + " Jlama builder, so configuring it silently does nothing");
            }
        }

        /**
         * The token must never be printable from the builder. This whole nested class
         * asserts through {@code toString()}, so a masking regression here would also
         * be a live credential-leak path into any log that prints it.
         */
        @Test
        @DisplayName("the auth token is masked in the builder's own toString")
        void authTokenIsMasked() {
            Map<String, String> params = new HashMap<>();
            params.put("authToken", "hf_super_secret_value");

            String state = applied(params);

            assertFalse(state.contains("hf_super_secret_value"),
                    "the Hugging Face token must not be printable from the builder: " + state);
            assertTrue(state.contains("authToken=********"), state);
        }

        @Test
        @DisplayName("an empty parameter map leaves every Jlama default in place")
        void emptyParametersLeaveDefaults() {
            String state = applied(new HashMap<>());

            assertTrue(state.contains("modelName=null"), state);
            assertTrue(state.contains("modelCachePath=null"), state);
            assertTrue(state.contains("authToken=null"), state);
        }

        /**
         * A non-numeric value is a typo in the Manager, not a programming error:
         * {@code ModelParameterValues} logs it and leaves the provider default in
         * place. If it propagated instead, one bad character would take down every turn
         * the agent serves.
         */
        @Test
        @DisplayName("unparseable numbers fall back to the Jlama default instead of throwing")
        void unparseableNumbersFallBackToDefaults() {
            Map<String, String> params = new HashMap<>();
            params.put("temperature", "warm");
            params.put("maxTokens", "lots");

            String state = applied(params);

            assertTrue(state.contains("temperature=null"), state);
            assertTrue(state.contains("maxTokens=null"), state);
        }

        /**
         * A mistyped boolean must not silently become {@code false}. That is the whole
         * reason {@code ModelParameterValues.applyBoolean} exists instead of
         * {@code Boolean.parseBoolean}, and the reason this builder must use it:
         * {@code "ture"} pinning quantization off, with no log line, is worse than
         * leaving Jlama's own default in place.
         */
        @Test
        @DisplayName("a mistyped boolean leaves the Jlama default rather than silently meaning false")
        void mistypedBooleanLeavesTheDefault() {
            Map<String, String> params = new HashMap<>();
            params.put("quantizeModelAtRuntime", "ture");

            String state = applied(params);

            assertTrue(state.contains("quantizeModelAtRuntime=null"),
                    "Boolean.parseBoolean would have produced 'false' here, which is indistinguishable from an"
                            + " explicit opt-out. Expected the value to be left unset: " + state);
        }

        /**
         * {@code workingQuantizedType} is the one setting whose value space is a
         * third-party enum, so an unrecognised name has to be handled rather than
         * handed to {@code DType.valueOf} — which would throw on every turn the agent
         * serves.
         */
        @Test
        @DisplayName("an unknown workingQuantizedType is ignored rather than failing the turn")
        void unknownWorkingQuantizedTypeIsIgnored() {
            Map<String, String> params = new HashMap<>();
            params.put("workingQuantizedType", "NOT_A_DTYPE");

            String state = applied(params);

            assertTrue(state.contains("workingQuantizedType=null"),
                    "an unusable enum name must leave Jlama's default in place: " + state);
        }

        @Test
        @DisplayName("workingQuantizedType is matched case-insensitively and trimmed")
        void workingQuantizedTypeIsCaseInsensitive() {
            Map<String, String> params = new HashMap<>();
            params.put("workingQuantizedType", "  f32  ");

            String state = applied(params);

            assertTrue(state.contains("workingQuantizedType=F32"),
                    "a value typed in the Manager should not have to match enum casing exactly: " + state);
        }

        /**
         * {@code threadCount} must stay unmapped. Jlama's builder accepts it, but
         * {@code JlamaModel.Loader} hands it to {@code ModelSupport.loadModel}, which
         * calls the process-global {@code PhysicalCoreExecutor.overrideThreadCount} — a
         * one-shot latch that throws {@code IllegalStateException("Executor already
         * started")} on any second call, and which the executor's memoized
         * {@code instance} supplier also arms just by running inference. Since this
         * registry rebuilds models on cache eviction, secret rotation and a 30-minute
         * idle TTL, a second build is routine, so exposing the parameter would turn a
         * working deployment into one that fails on its second model load.
         * <p>
         * Pinned as a test because the setter is right there on the builder next to the
         * ones that are mapped, and re-adding it looks like an obvious omission being
         * corrected.
         */
        @Test
        @DisplayName("threadCount stays unmapped: Jlama applies it to a process-global one-shot latch")
        void threadCountIsDeliberatelyNotMapped() {
            assertFalse(builder.recognisedParameters().contains("threadCount"),
                    "threadCount reaches PhysicalCoreExecutor.overrideThreadCount, which throws on its second"
                            + " call; a per-model parameter cannot honour a process-global one-shot setting");

            Map<String, String> params = new HashMap<>();
            params.put("threadCount", "4");

            assertTrue(applied(params).contains("threadCount=null"),
                    "even when configured, threadCount must not reach the Jlama builder");
        }

        /**
         * {@code timeout} is honoured for Jlama by {@code ObservableChatModel} rather
         * than by the provider, which is why it is a pipeline key rather than a
         * recognised parameter. Pinned because the obvious "fix" — adding it to
         * {@code recognisedParameters()} — would be wrong, and the obvious other "fix",
         * deleting it from the documented example, would remove a setting that does
         * work.
         */
        @Test
        @DisplayName("timeout stays a pipeline key, not a Jlama builder parameter")
        void timeoutIsNotABuilderParameter() {
            assertFalse(builder.recognisedParameters().contains("timeout"),
                    "JlamaChatModel.builder() has no timeout setter; the value is applied by ObservableChatModel"
                            + " as a wall-clock bound, and ModelParameterValues.PIPELINE_KEYS is what stops it"
                            + " being reported as unrecognised");
        }
    }

}
