/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyChatExecutorTest {

    private LegacyChatExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new LegacyChatExecutor();
    }

    // ==================== Successful Execution Tests ====================

    @Nested
    @DisplayName("Successful execution")
    class SuccessfulExecutionTests {

        @Test
        @DisplayName("should return response text from model")
        void execute_success_returnsResponseText() throws Exception {
            ChatModel model = buildMockModel("Hello! How can I help?");
            var task = createTask();

            var result = executor.execute(model, createMessages("Hi"), task);

            assertEquals("Hello! How can I help?", result.response());
        }

        @Test
        @DisplayName("should return empty metadata when response has no metadata")
        void execute_noMetadata_emptyResponseMetadata() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("Response")).build();
                }
            };
            var task = createTask();

            var result = executor.execute(model, createMessages("Test"), task);

            assertNotNull(result.responseMetadata());
        }
    }

    // ==================== Error Handling Tests ====================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw LifecycleException when model throws non-retryable error")
        void execute_modelError_throwsLifecycleException() {
            ChatModel failingModel = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    throw new RuntimeException("Model error: authentication failed");
                }
            };
            var task = createTask();
            var retryConfig = new LlmConfiguration.RetryConfiguration();
            retryConfig.setMaxAttempts(1);
            task.setRetry(retryConfig);

            assertThrows(LifecycleException.class, () -> executor.execute(failingModel, createMessages("Hi"), task));
        }

        @Test
        @DisplayName("should handle empty response text")
        void execute_emptyResponseText() throws Exception {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse chat(List<ChatMessage> messages) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
                }
            };
            var task = createTask();

            var result = executor.execute(model, createMessages("Test"), task);

            assertEquals("", result.response());
        }
    }

    // ==================== ChatResult Record Tests ====================

    @Nested
    @DisplayName("ChatResult record")
    class ChatResultTests {

        @Test
        @DisplayName("ChatResult should hold response and metadata")
        void chatResult_holdsValues() {
            var metadata = Map.<String, Object>of("finishReason", "STOP");
            var result = new LegacyChatExecutor.ChatResult("test response", metadata);

            assertEquals("test response", result.response());
            assertEquals("STOP", result.responseMetadata().get("finishReason"));
        }

        @Test
        @DisplayName("ChatResult with empty metadata")
        void chatResult_emptyMetadata() {
            var result = new LegacyChatExecutor.ChatResult("response", Map.of());

            assertEquals("response", result.response());
            assertTrue(result.responseMetadata().isEmpty());
        }
    }

    // ==================== Helpers ====================

    private ChatModel buildMockModel(String response) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
            }
        };
    }

    private LlmConfiguration.Task createTask() {
        var task = new LlmConfiguration.Task();
        task.setId("testTask");
        task.setType("openai");
        task.setActions(List.of("action1"));
        task.setParameters(Map.of("apiKey", "test-key"));
        var retryConfig = new LlmConfiguration.RetryConfiguration();
        retryConfig.setMaxAttempts(2);
        retryConfig.setBackoffDelayMs(10L);
        task.setRetry(retryConfig);
        return task;
    }

    private List<ChatMessage> createMessages(String userInput) {
        return List.of(UserMessage.from(userInput));
    }
}
