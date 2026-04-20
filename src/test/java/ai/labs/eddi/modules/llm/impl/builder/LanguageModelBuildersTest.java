package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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
        @DisplayName("builds ChatModel")
        void build() {
            Map<String, String> params = new HashMap<>();
            params.put("apiKey", "sk-test");
            params.put("modelName", "claude-sonnet-4-6");
            params.put("temperature", "0.3");
            params.put("timeout", "60000");
            params.put("logRequests", "true");
            params.put("logResponses", "true");

            ChatModel model = builder.build(params);
            assertNotNull(model);
        }

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

}
