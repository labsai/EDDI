/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * D11 — how the two streaming bounds relate, and what happens to a stream that
 * has been abandoned.
 * <p>
 * EDDI exposes two settings that bound a streaming turn: the {@code timeout}
 * model parameter (milliseconds, handed to the provider's streaming HTTP
 * client) and the task-level {@code streamingTimeoutSeconds} (the overall
 * wall-clock backstop applied here). They are kept separate on purpose; these
 * tests pin the precedence between them and the back-compat of both stored
 * shapes.
 */
class StreamingLegacyChatExecutorTimeoutTest {

    private StreamingLegacyChatExecutor executor;
    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        executor = new StreamingLegacyChatExecutor();
        eventSink = mock(ConversationEventSink.class);
    }

    @Nested
    @DisplayName("Backstop resolution (timeout vs streamingTimeoutSeconds)")
    class BackstopResolutionTests {

        @Test
        @DisplayName("no task at all falls back to the 120s default")
        void nullTask_usesDefault() {
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(null));
        }

        @Test
        @DisplayName("back-compat: a config setting only streamingTimeoutSeconds keeps exactly that bound")
        void explicitStreamingTimeout_wins() {
            var task = task(null);
            task.setStreamingTimeoutSeconds(45);

            assertEquals(45L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task));
        }

        @Test
        @DisplayName("an explicit streamingTimeoutSeconds still wins when timeout is also set")
        void explicitStreamingTimeout_beatsTimeoutParameter() {
            var task = task("600000");
            task.setStreamingTimeoutSeconds(45);

            assertEquals(45L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task),
                    "streamingTimeoutSeconds is the overall backstop and must not be overridden by the model timeout");
        }

        @Test
        @DisplayName("back-compat: a config setting only a short timeout keeps the 120s default backstop")
        void shortTimeoutParameter_keepsDefaultBackstop() {
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("15000")),
                    "A timeout below the default must not shorten the overall backstop");
        }

        @Test
        @DisplayName("a timeout longer than the default raises the backstop so it never truncates it")
        void longTimeoutParameter_raisesBackstop() {
            assertEquals(300L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("300000")),
                    "The backstop must never fire before the timeout the operator configured");
        }

        @Test
        @DisplayName("a sub-second timeout never collapses the backstop")
        void subSecondTimeoutParameter_keepsDefaultBackstop() {
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("1")));
        }

        @Test
        @DisplayName("a zero or negative streamingTimeoutSeconds falls through to the timeout-derived bound")
        void nonPositiveStreamingTimeout_fallsThrough() {
            var task = task("300000");
            task.setStreamingTimeoutSeconds(0);
            assertEquals(300L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task));

            task.setStreamingTimeoutSeconds(-1);
            assertEquals(300L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task));
        }

        @Test
        @DisplayName("an unresolved (templated) or non-numeric timeout leaves the default in place")
        void unresolvableTimeoutParameter_usesDefault() {
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("{vars.llm-timeout}")));
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("")));
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("0")));
        }

        @Test
        @DisplayName("a task with no parameters map at all resolves to the default")
        void noParameters_usesDefault() {
            var task = new LlmConfiguration.Task();
            task.setId("t");
            task.setType("openai");
            assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task));
        }

        @Test
        @DisplayName("a short timeout must not cut off a stream that is still healthy")
        void shortTimeoutParameter_doesNotTruncateHealthyStream() {
            // End to end through execute(): with only `timeout` set to 1s, the overall
            // backstop stays at the 120s default, so a stream that takes ~1.5s completes.
            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    var emitter = new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        handler.onPartialResponse("slow but fine");
                        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("slow but fine")).build());
                    });
                    emitter.setDaemon(true);
                    emitter.start();
                }
            };

            var result = executor.execute(model, messages(), eventSink, task("1000"));

            assertEquals("slow but fine", result.response(),
                    "Deriving the backstop from `timeout` must never shorten it below the 120s default");
            assertNull(result.metadata().get("streamingTimeout"), "The stream completed; no timeout must be reported");
        }
    }

    @Nested
    @DisplayName("Abandoned streams stop writing to the event sink")
    class AbandonedStreamTests {

        @Test
        @DisplayName("a timed-out attempt's late token must not interleave with the retry's output")
        void lateTokenFromAbandonedAttempt_isNotEmitted() throws Exception {
            var attempts = new AtomicInteger(0);
            var retryStarted = new CountDownLatch(1);
            var lateTokenEmitted = new CountDownLatch(1);

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (attempts.incrementAndGet() == 1) {
                        // Attempt 1 never terminates. Its provider callback thread stays alive
                        // and delivers a token only after the executor has given up and retried
                        // — exactly the race that used to corrupt the SSE stream.
                        var straggler = new Thread(() -> {
                            try {
                                if (!retryStarted.await(5, TimeUnit.SECONDS)) {
                                    return;
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            handler.onPartialResponse("LATE");
                            lateTokenEmitted.countDown();
                        });
                        straggler.setDaemon(true);
                        straggler.start();
                        return;
                    }

                    retryStarted.countDown();
                    try {
                        // Make the ordering deterministic: the straggler token is delivered
                        // before the retry emits anything.
                        if (!lateTokenEmitted.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("straggler token was never delivered");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    handler.onPartialResponse("good");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("good")).build());
                }
            };

            var task = task(null);
            task.setStreamingTimeoutSeconds(1);
            var retry = new RetryConfiguration();
            retry.setMaxAttempts(2);
            retry.setBackoffDelayMs(1L);
            task.setRetry(retry);

            var result = executor.execute(model, messages(), eventSink, task);

            assertEquals(2, attempts.get(), "The empty first attempt should have been retried");
            assertEquals("good", result.response());
            verify(eventSink, never()).onToken("LATE");
            verify(eventSink, times(1)).onToken("good");
        }
    }

    private static LlmConfiguration.Task task(String timeoutMs) {
        var task = new LlmConfiguration.Task();
        task.setId("testTask");
        task.setType("openai");
        task.setActions(List.of("action1"));
        Map<String, String> parameters = new HashMap<>();
        parameters.put("apiKey", "test-key");
        if (timeoutMs != null) {
            parameters.put("timeout", timeoutMs);
        }
        task.setParameters(parameters);
        return task;
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("Hi"));
    }
}
