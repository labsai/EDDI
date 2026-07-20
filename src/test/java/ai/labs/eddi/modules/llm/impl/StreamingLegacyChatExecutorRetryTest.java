/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for error handling, retry, timeout, and metadata features in
 * {@link StreamingLegacyChatExecutor}.
 */
class StreamingLegacyChatExecutorRetryTest {

    private StreamingLegacyChatExecutor executor;
    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        executor = new StreamingLegacyChatExecutor();
        eventSink = mock(ConversationEventSink.class);
    }

    // ==================== Zero-Token Error Retry ====================

    @Nested
    @DisplayName("Zero-token error retry")
    class ZeroTokenErrorRetryTests {

        @Test
        @DisplayName("should retry on zero-token error and succeed on second attempt")
        void retryOnZeroTokenError_succeedsOnSecondAttempt() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (callCount.incrementAndGet() == 1) {
                        // First attempt: error with no tokens emitted
                        handler.onError(new RuntimeException("connection timeout"));
                    } else {
                        // Second attempt: success
                        handler.onPartialResponse("Hello");
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("Hello")).build());
                    }
                }
            };

            var task = createTaskWithRetry(2, 1L);
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals("Hello", result.response());
            assertEquals(2, callCount.get());
            verify(eventSink).onToken("Hello");
        }

        @Test
        @DisplayName("should throw after all retry attempts exhausted on zero-token error")
        void retryExhausted_throwsRuntimeException() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onError(new RuntimeException("persistent failure"));
                }
            };

            var task = createTaskWithRetry(2, 1L);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> executor.execute(model, createMessages("Hi"), eventSink, task));

            assertTrue(ex.getMessage().contains("Streaming chat failed"));
        }

        @Test
        @DisplayName("should not retry when maxAttempts is 1")
        void noRetryWhenMaxAttemptsOne() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    handler.onError(new RuntimeException("fail"));
                }
            };

            var task = createTaskWithRetry(1, 1L);
            assertThrows(RuntimeException.class,
                    () -> executor.execute(model, createMessages("Hi"), eventSink, task));

            assertEquals(1, callCount.get());
        }
    }

    // ==================== Partial-Token Error ====================

    @Nested
    @DisplayName("Partial-token error")
    class PartialTokenErrorTests {

        @Test
        @DisplayName("should return partial content with streaming_error_partial warning")
        void errorWithPartialTokens_returnsPartialWithWarning() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("The answer is");
                    handler.onError(new RuntimeException("connection lost mid-stream"));
                }
            };

            var task = createTaskWithRetry(2, 1L);
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals("The answer is", result.response());
            assertEquals("streaming_error_partial", result.metadata().get("warning"));
            assertEquals("connection lost mid-stream", result.metadata().get("errorMessage"));
            verify(eventSink).onToken("The answer is");
        }

        @Test
        @DisplayName("should not retry when partial content is available despite error")
        void errorWithPartialTokens_doesNotRetry() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    handler.onPartialResponse("partial");
                    handler.onError(new RuntimeException("mid-stream error"));
                }
            };

            var task = createTaskWithRetry(3, 1L);
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals(1, callCount.get(), "Should not retry when partial content exists");
            assertEquals("partial", result.response());
        }

        @Test
        @DisplayName("executeCapturing should throw even when tokens were already emitted")
        void executeCapturing_propagatesErrorDespitePartialTokens() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("partial ");
                    handler.onError(new RuntimeException("provider blew up mid-stream"));
                }
            };

            // The cascade has no try/catch around executeCapturing — it relies on this
            // throw to fall back to the best previous step. Salvaging the partial text
            // here would let a failed final step be accepted as a successful one.
            var ex = assertThrows(RuntimeException.class,
                    () -> executor.executeCapturing(model, createMessages("Hi"), eventSink));
            assertTrue(ex.getMessage().contains("Streaming chat failed"));
        }
    }

    // ==================== Timeout with Partial Content ====================

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutTests {

        @Test
        @DisplayName("should return partial content with streaming_timeout_partial on timeout")
        void timeoutWithPartialContent_returnsPartialWithWarning() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Partial ");
                    handler.onPartialResponse("response");
                    // Never call onCompleteResponse or onError — simulates timeout
                }
            };

            var task = createTaskWithTimeout(1); // 1 second timeout
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals("Partial response", result.response());
            assertEquals("streaming_timeout_partial", result.metadata().get("warning"));
            assertTrue((Boolean) result.metadata().get("streamingTimeout"));
        }

        @Test
        @DisplayName("should return empty with streamingTimeout=true on full timeout")
        void timeoutWithNoContent_returnsEmptyWithTimeout() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    // No tokens, no response, no error — pure timeout
                }
            };

            var task = createTaskWithTimeout(1);
            task.setRetry(createRetryConfig(1, 1L)); // No retries
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals("", result.response());
            assertTrue((Boolean) result.metadata().get("streamingTimeout"));
            verify(eventSink, never()).onToken(anyString());
        }
    }

    // ==================== Per-Attempt Metadata Isolation ====================

    @Nested
    @DisplayName("Per-attempt metadata isolation")
    class MetadataIsolationTests {

        @Test
        @DisplayName("should not leak a failed attempt's streamingTimeout into a successful retry")
        void timeoutThenSuccess_doesNotLeakTimeoutMetadata() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (callCount.incrementAndGet() == 1) {
                        // First attempt: no tokens, no completion — pure timeout
                        return;
                    }
                    handler.onPartialResponse("Recovered");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("Recovered"))
                            .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build())
                            .build());
                }
            };

            var task = createTaskWithTimeout(1);
            task.setRetry(createRetryConfig(2, 1L));
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals("Recovered", result.response());
            assertEquals(2, callCount.get());
            // The retry succeeded — stale signals from attempt 1 must not survive, or
            // responseValidation.onStreamingTimeout would fire on a perfectly good answer.
            assertFalse(result.metadata().containsKey("streamingTimeout"),
                    "streamingTimeout from the failed attempt must not leak into the successful retry");
            assertFalse(result.metadata().containsKey("warning"),
                    "stale warning from the failed attempt must not leak into the successful retry");
            assertEquals("STOP", result.metadata().get("finishReason"));
        }
    }

    // ==================== Degenerate Retry Configuration ====================

    @Nested
    @DisplayName("Degenerate retry configuration")
    class DegenerateRetryConfigTests {

        @Test
        @DisplayName("should still invoke the model exactly once when maxAttempts is 0")
        void zeroMaxAttempts_stillRunsOnce() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    handler.onPartialResponse("Hello");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build());
                }
            };

            var task = createTask();
            task.setRetry(createRetryConfig(0, 1L));
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            // maxAttempts <= 0 must mean "one attempt, no retries" — never "skip the
            // model and hand back a null response nobody can distinguish from silence".
            assertEquals(1, callCount.get(), "model must be invoked once even with maxAttempts=0");
            assertEquals("Hello", result.response());
            assertNotNull(result.response(), "a degenerate retry config must never yield a null response");
        }

        @Test
        @DisplayName("should treat a negative maxAttempts as a single attempt")
        void negativeMaxAttempts_stillRunsOnce() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    handler.onPartialResponse("Hi there");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hi there")).build());
                }
            };

            var task = createTask();
            task.setRetry(createRetryConfig(-5, 1L));
            var result = executor.execute(model, createMessages("Hi"), eventSink, task);

            assertEquals(1, callCount.get());
            assertEquals("Hi there", result.response());
        }
    }

    // ==================== Configurable Timeout ====================

    @Nested
    @DisplayName("Configurable timeout from task")
    class ConfigurableTimeoutTests {

        @Test
        @DisplayName("should use task's streamingTimeoutSeconds when set")
        void taskTimeoutOverridesDefault() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    // Never completes — will timeout
                }
            };

            var task = createTaskWithTimeout(1); // 1 second timeout
            task.setRetry(createRetryConfig(1, 1L));

            long start = System.currentTimeMillis();
            executor.execute(model, createMessages("Hi"), eventSink, task);
            long elapsed = System.currentTimeMillis() - start;

            // Should timeout around 1 second, not the default 120 seconds
            assertTrue(elapsed < 10000, "Timeout should be ~1s, but took " + elapsed + "ms");
        }

        @Test
        @DisplayName("should use default timeout when task is null")
        void nullTask_usesDefaultTimeout() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("fast");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("fast")).build());
                }
            };

            // null task should not throw — uses defaults
            var result = executor.execute(model, createMessages("Hi"), eventSink, null);
            assertEquals("fast", result.response());
        }

        @Test
        @DisplayName("should ignore zero or negative streamingTimeoutSeconds")
        void zeroTimeout_usesDefault() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("ok");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("ok")).build());
                }
            };

            var task = createTask();
            task.setStreamingTimeoutSeconds(0);

            var result = executor.execute(model, createMessages("Hi"), eventSink, task);
            assertEquals("ok", result.response());
        }
    }

    // ==================== StreamingResult Record ====================

    @Nested
    @DisplayName("StreamingResult metadata")
    class StreamingResultTests {

        @Test
        @DisplayName("should capture finishReason in metadata on success")
        void successfulResponse_capturesFinishReason() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Done");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("Done"))
                            .metadata(ChatResponseMetadata.builder()
                                    .finishReason(FinishReason.STOP).build())
                            .build());
                }
            };

            var result = executor.execute(model, createMessages("Hi"), eventSink, createTask());

            assertEquals("Done", result.response());
            assertEquals("STOP", result.metadata().get("finishReason"));
        }

        @Test
        @DisplayName("should have empty metadata when no finishReason")
        void noFinishReason_emptyMetadata() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("OK");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("OK")).build());
                }
            };

            var result = executor.execute(model, createMessages("Hi"), eventSink, createTask());

            assertEquals("OK", result.response());
            assertFalse(result.metadata().containsKey("finishReason"));
        }

        @Test
        @DisplayName("StreamingResult record holds response and metadata")
        void streamingResult_holdsValues() {
            var metadata = Map.<String, Object>of("key", "value");
            var result = new StreamingLegacyChatExecutor.StreamingResult("test", metadata);

            assertEquals("test", result.response());
            assertEquals("value", result.metadata().get("key"));
        }
    }

    // ==================== Backward Compatibility ====================

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompatTests {

        @Test
        @DisplayName("old execute(model, messages, eventSink) should still work")
        void legacyExecute_returnsStringResponse() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Hello");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("Hello")).build());
                }
            };

            // The old 3-arg overload returns String, not StreamingResult
            String result = executor.execute(model, createMessages("Hi"), eventSink);

            assertEquals("Hello", result);
            verify(eventSink).onToken("Hello");
        }

        @Test
        @DisplayName("old execute delegates to new overload with null task")
        void legacyExecute_delegatesToNewOverload() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("")).build());
                }
            };

            // Should not throw even though task is null internally
            assertDoesNotThrow(() -> executor.execute(model, createMessages("Hi"), eventSink));
        }
    }

    // ==================== Helpers ====================

    private LlmConfiguration.Task createTask() {
        var task = new LlmConfiguration.Task();
        task.setId("testTask");
        task.setType("openai");
        task.setActions(List.of("action1"));
        task.setParameters(Map.of("apiKey", "test-key"));
        return task;
    }

    private LlmConfiguration.Task createTaskWithRetry(int maxAttempts, long backoffMs) {
        var task = createTask();
        task.setRetry(createRetryConfig(maxAttempts, backoffMs));
        return task;
    }

    private LlmConfiguration.Task createTaskWithTimeout(int timeoutSeconds) {
        var task = createTask();
        task.setStreamingTimeoutSeconds(timeoutSeconds);
        return task;
    }

    private RetryConfiguration createRetryConfig(int maxAttempts, long backoffMs) {
        var retryConfig = new RetryConfiguration();
        retryConfig.setMaxAttempts(maxAttempts);
        retryConfig.setBackoffDelayMs(backoffMs);
        return retryConfig;
    }

    private List<ChatMessage> createMessages(String userInput) {
        return List.of(UserMessage.from(userInput));
    }
}
