/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling, finish reason metadata, and null safety in
 * {@link LegacyChatExecutor}.
 */
class LegacyChatExecutorErrorHandlingTest {

    private LegacyChatExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LegacyChatExecutor();
    }

    // ==================== Null AiMessage Handling ====================

    @Nested
    @DisplayName("Null aiMessage handling")
    class NullAiMessageTests {

        @Test
        @DisplayName("should handle empty aiMessage text gracefully")
        void emptyAiMessage_doesNotThrow() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(""))
                            .build();
                }
            };

            var task = createTask();
            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("", result.response(), "responseContent should be empty string");
            assertNotNull(result.responseMetadata());
        }
    }

    // ==================== FinishReason Warning Metadata ====================

    @Nested
    @DisplayName("FinishReason warning metadata")
    class FinishReasonWarningTests {

        @Test
        @DisplayName("finishReason LENGTH should produce warning=truncated")
        void finishReasonLength_warningTruncated() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("truncated response..."))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.LENGTH).build())
                            .build();
                }
            };

            var task = createTask();
            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("truncated response...", result.response());
            assertEquals("truncated", result.responseMetadata().get("warning"));
            assertEquals("LENGTH", result.responseMetadata().get("finishReason"));
        }

        @Test
        @DisplayName("finishReason CONTENT_FILTER should produce warning=content_filtered")
        void finishReasonContentFilter_warningContentFiltered() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from(""))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.CONTENT_FILTER).build())
                            .build();
                }
            };

            var task = createTask();
            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("content_filter", result.responseMetadata().get("warning"));
            assertEquals("CONTENT_FILTER", result.responseMetadata().get("finishReason"));
        }

        @Test
        @DisplayName("finishReason STOP should have no warning")
        void finishReasonStop_noWarning() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("Normal response"))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.STOP).build())
                            .build();
                }
            };

            var task = createTask();
            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("Normal response", result.response());
            assertNull(result.responseMetadata().get("warning"),
                    "Normal STOP finish reason should not produce a warning");
            assertEquals("STOP", result.responseMetadata().get("finishReason"));
        }

        @Test
        @DisplayName("normal response without metadata has no warning")
        void normalResponseNoMetadata_noWarning() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("Hello!")).build();
                }
            };

            var task = createTask();
            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("Hello!", result.response());
            assertNull(result.responseMetadata().get("warning"));
        }
    }

    // ==================== Helpers ====================

    private LlmConfiguration.Task createTask() {
        var task = new LlmConfiguration.Task();
        task.setId("testTask");
        task.setType("openai");
        task.setActions(List.of("action1"));
        task.setParameters(Map.of("apiKey", "test-key"));
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(1);
        retryConfig.setBackoffDelayMs(1L);
        task.setRetry(retryConfig);
        return task;
    }

    private List<ChatMessage> createMessages(String userInput) {
        return List.of(UserMessage.from(userInput));
    }
}
