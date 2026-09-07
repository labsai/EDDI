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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    // ==================== Interruption ====================

    @Nested
    @DisplayName("interruption")
    class InterruptionTests {

        /** A model that abandons the stream after marking the thread interrupted. */
        private StreamingChatModel interruptingModel(String partialBeforeInterrupt) {
            return new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (partialBeforeInterrupt != null) {
                        handler.onPartialResponse(partialBeforeInterrupt);
                    }
                    // Never completes; the awaiting thread is asked to stop instead.
                    Thread.currentThread().interrupt();
                }
            };
        }

        @Test
        @DisplayName("interrupt with no content must fail, not return an empty success")
        void interruptWithNoContent_throws() {
            try {
                var ex = assertThrows(RuntimeException.class,
                        () -> executor.execute(interruptingModel(null), createMessages("Hi"), eventSink, createTask()));
                assertTrue(ex.getMessage().contains("interrupted"), "the failure must name the interruption: " + ex.getMessage());
            } finally {
                Thread.interrupted(); // clear so the flag does not leak into other tests
            }
        }

        @Test
        @DisplayName("interrupt must not be retried — cancellation is not a transient failure")
        void interruptIsNotRetried() {
            var callCount = new AtomicInteger(0);
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            };

            try {
                var task = createTaskWithRetry(3, 1L);
                assertThrows(RuntimeException.class, () -> executor.execute(model, createMessages("Hi"), eventSink, task));
                assertEquals(1, callCount.get(), "a cancelled call must not be retried against the provider");
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("executeCapturing propagates an interrupt so the cascade can fall back")
        void executeCapturing_propagatesInterrupt() {
            try {
                assertThrows(RuntimeException.class,
                        () -> executor.executeCapturing(interruptingModel("partial "), createMessages("Hi"), eventSink));
            } finally {
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("interrupt with partial content keeps the text on the salvaging path, flagged")
        void interruptWithPartialContent_salvagesWithWarning() {
            try {
                var result = executor.execute(interruptingModel("Half an answer"), createMessages("Hi"), eventSink, createTask());

                assertEquals("Half an answer", result.response());
                assertEquals("streaming_interrupted_partial", result.metadata().get("warning"));
                assertTrue((Boolean) result.metadata().get("streamingInterrupted"));
            } finally {
                Thread.interrupted();
            }
        }
    }

    // ==================== finishReason-derived Warnings ====================

    @Nested
    @DisplayName("finishReason-derived warnings")
    class FinishReasonWarningTests {

        private StreamingChatModel modelFinishingWith(FinishReason finishReason) {
            return new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Some answer");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("Some answer"))
                            .metadata(ChatResponseMetadata.builder().finishReason(finishReason).build())
                            .build());
                }
            };
        }

        @Test
        @DisplayName("should flag warning=truncated when finishReason is LENGTH")
        void lengthFinishReason_flagsTruncated() {
            var result = executor.execute(modelFinishingWith(FinishReason.LENGTH), createMessages("Hi"), eventSink, createTask());

            // Without this, responseValidation.onTruncation can never fire on the
            // streaming path even though finishReason=LENGTH is right there.
            assertEquals("truncated", result.metadata().get("warning"));
            assertEquals("LENGTH", result.metadata().get("finishReason"));
        }

        @Test
        @DisplayName("should flag warning=content_filter when finishReason is CONTENT_FILTER")
        void contentFilterFinishReason_flagsContentFilter() {
            var result = executor.execute(modelFinishingWith(FinishReason.CONTENT_FILTER), createMessages("Hi"), eventSink, createTask());

            assertEquals("content_filter", result.metadata().get("warning"));
            assertEquals("CONTENT_FILTER", result.metadata().get("finishReason"));
        }

        @Test
        @DisplayName("should not flag any warning on a normal STOP finish")
        void stopFinishReason_noWarning() {
            var result = executor.execute(modelFinishingWith(FinishReason.STOP), createMessages("Hi"), eventSink, createTask());

            assertFalse(result.metadata().containsKey("warning"));
            assertEquals("STOP", result.metadata().get("finishReason"));
        }

        @Test
        @DisplayName("a mid-stream error warning should win over a finishReason warning")
        void errorWarningTakesPrecedenceOverFinishReason() {
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Truncated then broke");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("Truncated then broke"))
                            .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.LENGTH).build())
                            .build());
                    handler.onError(new RuntimeException("boom"));
                }
            };

            var result = executor.execute(model, createMessages("Hi"), eventSink, createTask());

            // The transport failure is the more urgent signal for the operator.
            assertEquals("streaming_error_partial", result.metadata().get("warning"));
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

        /**
         * The engine ceiling on {@code maxAttempts} bounds how long one config can hold
         * a pipeline thread (AGENTS.md §4.1 rule 5). This path read
         * {@code getMaxAttempts()} raw, so the identical {@code retry} block was capped
         * on the tool-loop path and unbounded here — a streaming task really did run
         * all 50 attempts.
         */
        @Test
        @DisplayName("should stop at the engine ceiling, not at a configured maxAttempts above it")
        void maxAttemptsIsCappedByTheEngineCeiling() {
            var callCount = new AtomicInteger(0);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    callCount.incrementAndGet();
                    handler.onError(new RuntimeException("persistent failure"));
                }
            };

            var retryConfig = createRetryConfig(50, 1L);
            // keeps the whole run in single-digit milliseconds
            retryConfig.setMaxBackoffDelayMs(1L);
            var task = createTask();
            task.setRetry(retryConfig);

            assertThrows(RuntimeException.class,
                    () -> executor.execute(model, createMessages("Hi"), eventSink, task));

            assertEquals(RetryConfiguration.MAX_ATTEMPTS_CEILING, callCount.get(),
                    "a configured maxAttempts of 50 must stop at the engine ceiling");
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

    /**
     * The total-backoff budget, not the attempt count, is what stops this loop.
     *
     * <p>
     * {@code executeWithRetry} has always applied
     * {@code RetryConfiguration.MAX_TOTAL_BACKOFF_MS}; the streaming path ran its
     * own retry loop and applied only the per-sleep ceiling, so ten attempts at a
     * configured 30-second delay parked a pipeline thread for 270 seconds.
     * Threading {@code totalBackoffMs} back into {@code backoff} is what makes the
     * two agree, and the two "budget is spent" branches are the only places that
     * say so.
     * </p>
     *
     * <h3>Why this runs in milliseconds rather than a minute</h3>
     * <p>
     * Spending a 60-second budget does NOT require sleeping for 60 seconds. An
     * interrupted {@code Thread.sleep} still reports the amount it was budgeted —
     * that is exactly what {@code RetryConfigurationTest
     * .interruptedBackoffRestoresTheInterruptFlag} pins — so a watcher that
     * interrupts the pipeline thread while, and only while, it is parked inside
     * {@code RetryConfiguration.backoff} spends the whole budget in two instant
     * attempts. The watcher is armed by the fake model itself and matches on the
     * pipeline thread's own stack, so it can never fire during {@code latch.await}:
     * an interrupt there is a cancellation, which this executor deliberately
     * refuses to retry ("Streaming chat interrupted"). The fake clears the pending
     * flag at the start of each attempt for the same reason — a provider client
     * that consumed the cancellation — leaving the retry decision itself untouched.
     * </p>
     */
    @Nested
    @DisplayName("total backoff budget")
    class TotalBackoffBudgetTests {

        /**
         * A backoff configured AT the per-sleep ceiling: two of them exhaust the
         * 60-second total budget, so the third attempt is refused by the budget rather
         * than by {@code maxAttempts} (which is 6 here and never reached).
         */
        private LlmConfiguration.Task ceilingBackoffTask(int maxAttempts) {
            var task = createTask();
            var retry = new RetryConfiguration();
            retry.setMaxAttempts(maxAttempts);
            retry.setBackoffDelayMs(30_000L);
            retry.setBackoffMultiplier(1.0);
            retry.setMaxBackoffDelayMs(30_000L);
            task.setRetry(retry);
            return task;
        }

        @Test
        @DisplayName("a retried error stops on the spent backoff budget, not on maxAttempts")
        void errorRetryStopsWhenTheBackoffBudgetIsSpent() {
            var callCount = new AtomicInteger(0);
            try (var interrupter = new BackoffInterrupter(Thread.currentThread())) {
                StreamingChatModel model = new StreamingChatModel() {
                    @Override
                    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        // A provider client that consumed the cancellation; see the class
                        // comment. Without this the flag left by the previous interrupted
                        // backoff would make latch.await throw and the executor would
                        // (correctly) refuse to retry a cancellation.
                        Thread.interrupted();
                        callCount.incrementAndGet();
                        handler.onError(new RuntimeException("provider 503"));
                        interrupter.arm();
                    }
                };

                List<String> warnings = captureExecutorWarnings(
                        () -> assertThrows(RuntimeException.class,
                                () -> executor.execute(model, createMessages("Hi"), eventSink, ceilingBackoffTask(6))));

                assertEquals(3, callCount.get(),
                        "two full-ceiling backoffs spend the 60s budget, so the third attempt is the last");
                assertTrue(warnings.stream().anyMatch(w -> w.contains("Streaming error with empty response and the retry backoff budget is spent")),
                        "the operator has to be told the budget stopped this, not the attempt count; captured: " + warnings);
                assertTrue(warnings.stream().noneMatch(w -> w.contains("retrying (attempt 3/")),
                        "attempt 3 must not report itself as retrying — nothing was slept and nothing follows; captured: " + warnings);
            } finally {
                // Never leak an interrupt into whatever test runs next on this thread.
                Thread.interrupted();
            }
        }

        /**
         * The same budget bound on the timeout branch. The first two attempts fail fast
         * with an error so the budget is spent without any real waiting; only the third
         * attempt takes the timeout path, at the shortest backstop the config allows.
         */
        @Test
        @DisplayName("a retried timeout stops on the spent backoff budget and answers an empty result")
        void timeoutRetryStopsWhenTheBackoffBudgetIsSpent() {
            var callCount = new AtomicInteger(0);
            try (var interrupter = new BackoffInterrupter(Thread.currentThread())) {
                StreamingChatModel model = new StreamingChatModel() {
                    @Override
                    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        Thread.interrupted();
                        if (callCount.incrementAndGet() < 3) {
                            handler.onError(new RuntimeException("provider 503"));
                        }
                        // Third attempt onwards: the provider never answers at all, so
                        // the backstop expires and the timeout branch runs with the
                        // budget already spent. Arming here is safe — and keeps a
                        // regression fast rather than 90 seconds slow — because the
                        // watcher requires a RetryConfiguration.backoff frame, which
                        // latch.await does not have.
                        interrupter.arm();
                    }
                };

                var task = ceilingBackoffTask(6);
                task.setStreamingTimeoutSeconds(1);

                List<String> warnings = captureExecutorWarnings(
                        () -> {
                            var result = executor.execute(model, createMessages("Hi"), eventSink, task);
                            assertEquals("", result.response(), "a spent budget on the timeout branch answers empty, it does not throw");
                            assertEquals(true, result.metadata().get("streamingTimeout"));
                        });

                assertEquals(3, callCount.get(), "the budget, not maxAttempts=6, ended the loop");
                assertTrue(
                        warnings.stream().anyMatch(w -> w.contains("Streaming timed out with empty response and the retry backoff budget is spent")),
                        "captured: " + warnings);
                assertTrue(warnings.stream().noneMatch(w -> w.contains("Streaming timed out with empty response, retrying (attempt 3/")),
                        "attempt 3 must not report itself as retrying; captured: " + warnings);
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Interrupts {@code target} while it is parked inside
     * {@link RetryConfiguration#backoff}, and nowhere else.
     *
     * <p>
     * The stack-frame match is what makes this safe rather than merely fast: the
     * executor also parks in {@code CountDownLatch.await}, where an interrupt means
     * "cancelled" and takes a completely different branch. One arm, one interrupt.
     * </p>
     */
    private static final class BackoffInterrupter implements AutoCloseable {
        private final Thread target;
        private final Thread watcher;
        private volatile boolean armed;
        private volatile boolean stopped;

        BackoffInterrupter(Thread target) {
            this.target = target;
            this.watcher = new Thread(this::run, "backoff-interrupter");
            this.watcher.setDaemon(true);
            this.watcher.start();
        }

        void arm() {
            armed = true;
        }

        private void run() {
            // Reading another thread's stack needs a safepoint, so it is probed on
            // every Nth spin rather than continuously — cheap enough not to perturb
            // the thread being watched, frequent enough that no sleep is needed.
            int spins = 0;
            while (!stopped) {
                if (armed && (++spins & 0x3FF) == 0 && target.getState() == Thread.State.TIMED_WAITING && parkedInBackoff()) {
                    armed = false;
                    target.interrupt();
                }
                Thread.onSpinWait();
            }
        }

        private boolean parkedInBackoff() {
            for (StackTraceElement frame : target.getStackTrace()) {
                if (RetryConfiguration.class.getName().equals(frame.getClassName()) && "backoff".equals(frame.getMethodName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            stopped = true;
            try {
                watcher.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Every WARN {@link StreamingLegacyChatExecutor} emits while {@code body} runs,
     * with its parameters substituted into the format string.
     * {@code logging.properties} turns the whole {@code ai.labs.eddi} namespace OFF
     * for plain unit tests, so the logger is opened explicitly and put back.
     */
    private static List<String> captureExecutorWarnings(Runnable body) {
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                String message = String.valueOf(record.getMessage());
                Object[] parameters = record.getParameters();
                if (parameters != null && parameters.length > 0) {
                    try {
                        message = String.format(message, parameters);
                    } catch (RuntimeException ignored) {
                        message = message + " " + Arrays.toString(parameters);
                    }
                }
                captured.add(message);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        Logger julLogger = Logger.getLogger(StreamingLegacyChatExecutor.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            body.run();
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }
        return captured;
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
