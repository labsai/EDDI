/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.StreamingConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
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
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The no-tools streaming path under the streaming contract: retries like every
 * other model call (default three attempts, classified, inside the turn
 * deadline) but only before the first token reached the client; a failure after
 * it fails the turn and keeps the partial text as error detail; a stream counts
 * as complete only with a finish reason.
 * <p>
 * Replaces the tests that pinned the old behaviour: a partial answer stored as
 * a success after a mid-stream error
 * ({@code errorWithPartialTokens_returnsPartialWithWarning}), one attempt when
 * the task sets no retry block ({@code noRetryWhenMaxAttemptsOne} kept, now
 * only for an explicit 1), a timed-out partial returned as the answer, and an
 * empty timeout returned as an empty success.
 */
class StreamingLegacyChatExecutorRetryTest {

    private StreamingLegacyChatExecutor executor;
    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        executor = new StreamingLegacyChatExecutor();
        eventSink = mock(ConversationEventSink.class);
    }

    private static ChatResponse complete(String text, FinishReason reason) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).metadata(ChatResponseMetadata.builder().finishReason(reason).build())
                .build();
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("Hi"));
    }

    private static LlmConfiguration.Task task(Integer maxAttempts) {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(maxAttempts);
        retry.setBackoffDelayMs(1L);
        retry.setJitterRatio(0.0);
        task.setRetry(retry);
        return task;
    }

    /** A task with no retry block at all — what the setup wizard writes. */
    private static LlmConfiguration.Task taskWithoutRetry() {
        var task = task(null);
        task.setRetry(null);
        return task;
    }

    private static StreamingChatModel failingWith(RuntimeException failure, AtomicInteger calls) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onError(failure);
            }
        };
    }

    @Nested
    @DisplayName("Retries before the first token (B5)")
    class RetryParity {

        @Test
        @DisplayName("a transient error before any token is retried and the retry's answer is returned")
        void retryOnZeroTokenError_succeedsOnSecondAttempt() throws Exception {
            var calls = new AtomicInteger();
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (calls.incrementAndGet() == 1) {
                        handler.onError(new InternalServerException("provider 500"));
                    } else {
                        handler.onPartialResponse("Hello");
                        handler.onCompleteResponse(complete("Hello", FinishReason.STOP));
                    }
                }
            };

            var result = executor.execute(model, messages(), eventSink, task(3));

            assertEquals("Hello", result.response());
            assertEquals(2, calls.get());
            verify(eventSink).onToken("Hello");
        }

        @Test
        @DisplayName("without a retry block the default policy applies — three attempts, as on the non-streaming path")
        void defaultPolicyMakesThreeAttempts() {
            var calls = new AtomicInteger();
            var task = taskWithoutRetry();
            var retry = new RetryConfiguration();
            retry.setBackoffDelayMs(1L);
            // Only the backoff is shortened for the test; maxAttempts stays the default.
            task.setRetry(retry);

            assertThrows(LifecycleException.class,
                    () -> executor.execute(failingWith(new InternalServerException("provider 500"), calls), messages(), eventSink, task));

            assertEquals(3, calls.get(), "the streaming path used to substitute ONE attempt when no retry block was set");
        }

        @Test
        @DisplayName("a non-retryable class (401) is not retried")
        void nonRetryableIsNotRetried() {
            var calls = new AtomicInteger();

            assertThrows(LifecycleException.class,
                    () -> executor.execute(failingWith(new AuthenticationException("invalid api key"), calls), messages(), eventSink, task(3)));

            assertEquals(1, calls.get(), "an authentication failure fails identically on every attempt");
        }

        @Test
        @DisplayName("an explicit maxAttempts of 1 makes one call")
        void noRetryWhenMaxAttemptsOne() {
            var calls = new AtomicInteger();

            assertThrows(LifecycleException.class,
                    () -> executor.execute(failingWith(new InternalServerException("provider 500"), calls), messages(), eventSink, task(1)));

            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("an expired turn deadline means no call is started (B11)")
        void turnDeadlineIsHonoured() {
            var calls = new AtomicInteger();
            var memory = mock(IConversationMemory.class);
            Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC);
            when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(clock, 0L, 1_000L, 0L));

            assertThrows(LifecycleException.class, () -> executor.execute(failingWith(new InternalServerException("x"), calls), messages(),
                    eventSink, task(3), JsonResponseFormatPolicy.DISABLED, memory));

            assertEquals(0, calls.get());
        }
    }

    @Nested
    @DisplayName("A failure after text reached the client fails the turn (B2)")
    class FailureAfterText {

        private StreamingChatModel partialThenError(AtomicInteger calls) {
            return new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    calls.incrementAndGet();
                    handler.onPartialResponse("The answer is");
                    handler.onError(new InternalServerException("connection lost mid-stream"));
                }
            };
        }

        @Test
        @DisplayName("the turn fails, is not retried, and the partial is kept as error detail — never as the answer")
        void errorWithPartialTokens_failsTheTurn() {
            var calls = new AtomicInteger();
            var memory = mock(IConversationMemory.class);
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);

            var thrown = assertThrows(LifecycleException.class,
                    () -> executor.execute(partialThenError(calls), messages(), eventSink, task(3), JsonResponseFormatPolicy.DISABLED, memory));

            assertEquals(1, calls.get(), "a retry would send the fragment again");
            assertInstanceOf(ToolLoopStreamingChatModel.PartiallyStreamedRoundException.class, thrown.getCause());
            @SuppressWarnings("rawtypes")
            ArgumentCaptor<IData> stored = ArgumentCaptor.forClass(IData.class);
            verify(step, atLeastOnce()).storeData(stored.capture());
            var detail = stored.getAllValues().stream().filter(d -> d.getKey().equals(MemoryKeys.LLM_PARTIAL_OUTPUT_PREFIX + "t1")).findFirst();
            assertTrue(detail.isPresent(), "the partial text is recorded as error detail");
            assertEquals("The answer is", detail.get().getResult());
        }

        @Test
        @DisplayName("onFailureAfterText=keepPartial restores the old salvage, flagged")
        void keepPartialOptIn() throws Exception {
            var task = task(3);
            var streaming = new StreamingConfig();
            streaming.setOnFailureAfterText(StreamingConfig.ON_FAILURE_AFTER_TEXT_KEEP_PARTIAL);
            task.setStreaming(streaming);

            var result = executor.execute(partialThenError(new AtomicInteger()), messages(), eventSink, task);

            assertEquals("The answer is", result.response());
            assertEquals("streaming_error_partial", result.metadata().get("warning"));
        }

        @Test
        @DisplayName("tokens after the provider's error are not forwarded (B3, the live #garbage case)")
        void tokensAfterErrorAreNotForwarded() {
            StreamingChatModel garbage = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("good1 ");
                    handler.onError(new IllegalStateException("malformed frame"));
                    handler.onPartialResponse("good2");
                    handler.onCompleteResponse(complete("good1 good2", FinishReason.STOP));
                }
            };

            assertThrows(LifecycleException.class, () -> executor.execute(garbage, messages(), eventSink, task(3)));

            verify(eventSink).onToken("good1 ");
            verify(eventSink, never()).onToken("good2");
        }

        @Test
        @DisplayName("executeCapturing (the cascade) throws even when tokens were already emitted")
        void executeCapturing_propagatesErrorDespitePartialTokens() {
            assertThrows(RuntimeException.class, () -> executor.executeCapturing(partialThenError(new AtomicInteger()), messages(), eventSink));
        }
    }

    @Nested
    @DisplayName("Completion needs a finish reason (B1)")
    class FinishReasons {

        @Test
        @DisplayName("a stream that just ends after text fails the turn instead of storing a fragment")
        void noFinishReasonAfterTextFails() {
            StreamingChatModel truncated = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("Hel");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hel")).build());
                }
            };

            assertThrows(LifecycleException.class, () -> executor.execute(truncated, messages(), eventSink, task(3)));
        }

        @Test
        @DisplayName("a stream that ends empty without a finish reason is retried")
        void noFinishReasonBeforeTextIsRetried() throws Exception {
            var calls = new AtomicInteger();
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (calls.incrementAndGet() == 1) {
                        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("")).build());
                        return;
                    }
                    handler.onPartialResponse("ok");
                    handler.onCompleteResponse(complete("ok", FinishReason.STOP));
                }
            };

            assertEquals("ok", executor.execute(model, messages(), eventSink, task(3)).response());
            assertEquals(2, calls.get());
        }

        @Test
        @DisplayName("requireFinishReason=false accepts a server that never sends one")
        void optOut() throws Exception {
            var task = task(3);
            var streaming = new StreamingConfig();
            streaming.setRequireFinishReason(false);
            task.setStreaming(streaming);
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("fine");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("fine")).build());
                }
            };

            assertEquals("fine", executor.execute(model, messages(), eventSink, task).response());
        }

        @Test
        @DisplayName("LENGTH and CONTENT_FILTER are answers with their warning; STOP has none")
        void abnormalFinishReasonsAreFlagged() throws Exception {
            for (var reason : List.of(FinishReason.LENGTH, FinishReason.CONTENT_FILTER, FinishReason.STOP)) {
                StreamingChatModel model = new StreamingChatModel() {
                    @Override
                    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        handler.onPartialResponse("x");
                        handler.onCompleteResponse(complete("x", reason));
                    }
                };
                var metadata = executor.execute(model, messages(), eventSink, task(3)).metadata();

                assertEquals(reason.toString(), metadata.get("finishReason"));
                assertEquals(reason == FinishReason.LENGTH ? "truncated" : reason == FinishReason.CONTENT_FILTER ? "content_filter" : null,
                        metadata.get("warning"));
            }
        }
    }

    @Nested
    @DisplayName("Timeouts, interrupts and cancels")
    class Abandonment {

        private StreamingChatModel partialThenStall() {
            return new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    handler.onPartialResponse("partial ");
                }
            };
        }

        @Test
        @DisplayName("a backstop timeout after text fails the turn")
        void timeoutWithPartialContentFails() {
            var task = task(3);
            task.setStreamingTimeoutSeconds(1);

            assertThrows(LifecycleException.class, () -> executor.execute(partialThenStall(), messages(), eventSink, task));
        }

        @Test
        @DisplayName("…unless responseValidation.onStreamingTimeout acts on it: the partial goes to validation, flagged")
        void timeoutWithPartialGoesToAnActiveValidationPolicy() throws Exception {
            var task = task(3);
            task.setStreamingTimeoutSeconds(1);
            var validation = new LlmConfiguration.ResponseValidation();
            validation.setEnabled(true);
            validation.setOnStreamingTimeout("fallback");
            task.setResponseValidation(validation);

            var result = executor.execute(partialThenStall(), messages(), eventSink, task);

            assertEquals("partial ", result.response());
            assertEquals(Boolean.TRUE, result.metadata().get("streamingTimeout"));
        }

        @Test
        @DisplayName("a timeout with no content is retried, then fails — never an empty success")
        void timeoutWithNoContentFails() {
            var calls = new AtomicInteger();
            var task = task(2);
            task.setStreamingTimeoutSeconds(1);
            StreamingChatModel silent = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    calls.incrementAndGet();
                }
            };

            assertThrows(LifecycleException.class, () -> executor.execute(silent, messages(), eventSink, task));
            assertEquals(2, calls.get());
        }

        @Test
        @DisplayName("an interrupt is not retried")
        void interruptIsNotRetried() {
            var calls = new AtomicInteger();
            StreamingChatModel silent = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    calls.incrementAndGet();
                }
            };
            Thread.currentThread().interrupt();
            try {
                assertThrows(LifecycleException.class, () -> executor.execute(silent, messages(), eventSink, task(3)));
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, calls.get());
        }

        @Test
        @DisplayName("a turn already cancelled makes no request at all, and is not retried (B9, M3)")
        void cancelledTurnIsNotRetried() {
            var calls = new AtomicInteger();
            var memory = mock(IConversationMemory.class);
            when(memory.isCancelled()).thenReturn(true);
            StreamingChatModel silent = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    calls.incrementAndGet();
                }
            };

            var thrown = assertThrows(LifecycleException.class,
                    () -> executor.execute(silent, messages(), eventSink, task(3), JsonResponseFormatPolicy.DISABLED, memory));

            assertEquals(0, calls.get(), "the retry loop sees the cancel before the first attempt");
            assertInstanceOf(LifecycleException.LifecycleInterruptedException.class, thrown);
        }

        @Test
        @DisplayName("a turn cancelled during the stream stops it after one request")
        void cancelledDuringTheStreamIsNotRetried() {
            var calls = new AtomicInteger();
            var cancelled = new AtomicBoolean();
            var memory = mock(IConversationMemory.class);
            when(memory.isCancelled()).thenAnswer(inv -> cancelled.get());
            StreamingChatModel silent = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    calls.incrementAndGet();
                    cancelled.set(true);
                }
            };

            var thrown = assertThrows(LifecycleException.class,
                    () -> executor.execute(silent, messages(), eventSink, task(3), JsonResponseFormatPolicy.DISABLED, memory));

            assertEquals(1, calls.get());
            assertInstanceOf(LifecycleException.LifecycleInterruptedException.class, thrown);
        }
    }

    @Test
    @DisplayName("a successful stream returns the full text, its finish reason and no warning")
    void successfulResponse_capturesFinishReason() throws Exception {
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hel");
                handler.onPartialResponse("lo");
                handler.onCompleteResponse(complete("Hello", FinishReason.STOP));
            }
        };

        var result = executor.execute(model, messages(), eventSink, task(3));

        assertEquals("Hello", result.response());
        assertEquals("STOP", result.metadata().get("finishReason"));
        assertNull(result.metadata().get("warning"));
    }
}
