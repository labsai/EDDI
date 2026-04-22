package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link LegacyChatExecutor} — JSON mode, metadata
 * extraction, and JSON mode fallback paths.
 */
class LegacyChatExecutorExtendedTest {

    private LegacyChatExecutor executor;
    private LlmConfiguration.Task task;
    private List<ChatMessage> messages;

    @BeforeEach
    void setUp() {
        executor = new LegacyChatExecutor();
        task = new LlmConfiguration.Task();
        task.setId("testTask");
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(1);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);
        messages = List.of(UserMessage.from("Hello"));
    }

    // ─── Metadata extraction ────────────────────────────────

    @Nested
    @DisplayName("Response metadata extraction")
    class MetadataExtraction {

        @Test
        @DisplayName("should extract finishReason and tokenUsage")
        void extractsFullMetadata() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> msgs) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("Answer"))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.STOP)
                                    .tokenUsage(new TokenUsage(100, 50))
                                    .build())
                            .build();
                }
            };

            var result = executor.execute(model, messages, task);

            assertEquals("Answer", result.response());
            assertEquals("STOP", result.responseMetadata().get("finishReason"));

            @SuppressWarnings("unchecked")
            var usage = (Map<String, Object>) result.responseMetadata().get("tokenUsage");
            assertNotNull(usage);
            assertEquals(100, usage.get("inputTokens"));
            assertEquals(50, usage.get("outputTokens"));
        }

        @Test
        @DisplayName("should handle null finishReason gracefully")
        void handlesNullFinishReason() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> msgs) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("Answer"))
                            .metadata(ChatResponseMetadata.builder()
                                    .tokenUsage(new TokenUsage(10, 5))
                                    .build())
                            .build();
                }
            };

            var result = executor.execute(model, messages, task);

            assertFalse(result.responseMetadata().containsKey("finishReason"));
            assertTrue(result.responseMetadata().containsKey("tokenUsage"));
        }

        @Test
        @DisplayName("should handle null tokenUsage gracefully")
        void handlesNullTokenUsage() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> msgs) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("Answer"))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.LENGTH)
                                    .build())
                            .build();
                }
            };

            var result = executor.execute(model, messages, task);

            assertEquals("LENGTH", result.responseMetadata().get("finishReason"));
            assertFalse(result.responseMetadata().containsKey("tokenUsage"));
        }
    }

    // ─── JSON mode ──────────────────────────────────────────

    @Nested
    @DisplayName("JSON mode execution")
    class JsonModeExecution {

        @Test
        @DisplayName("should use ChatRequest with JSON response format")
        void usesJsonResponseFormat() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    // Verify JSON format was set
                    assertNotNull(request.responseFormat());
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("{\"key\":\"value\"}"))
                            .build();
                }

                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    fail("Should not use messages-based API in JSON mode");
                    return null;
                }
            };

            var result = executor.execute(model, messages, task, true);

            assertEquals("{\"key\":\"value\"}", result.response());
        }

        @Test
        @DisplayName("should fallback to standard mode when JSON not supported")
        void fallsBackOnUnsupportedJsonFormat() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest request) {
                    throw new RuntimeException("JSON format not supported");
                }

                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("fallback response"))
                            .build();
                }
            };

            var result = executor.execute(model, messages, task, true);

            assertEquals("fallback response", result.response());
        }
    }
}
