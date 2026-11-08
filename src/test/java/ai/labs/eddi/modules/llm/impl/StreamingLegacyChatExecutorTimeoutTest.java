/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.LogCaptureSupport.CapturedRecord;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.utils.LogCaptureSupport.captureRecordsOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

        /**
         * F11's "your timeout does not shorten the backstop" warning sits on the
         * PER-REQUEST path — {@code backstopSeconds} runs once per streaming turn, per
         * cascade step and per streamed tool-loop turn. Emitted unconditionally it
         * fires on every single turn, forever, for a config that will never change.
         * {@code ChatModelRegistry} makes the same distinction explicitly (build path
         * only, never on a cache hit).
         */
        @Test
        @DisplayName("the below-backstop warning is emitted once per task, not once per turn")
        void shortTimeoutWarningIsNotPerRequest() {
            var task = task("5000");

            var warnings = captureWarnings(() -> {
                for (int turn = 0; turn < 20; turn++) {
                    assertEquals(120L, executor.backstopSeconds(task));
                }
            });

            assertEquals(1, warnings.size(), "20 turns of the same task must not produce 20 WARN lines: " + warnings);
        }

        @Test
        @DisplayName("a different task still gets its own warning")
        void eachTaskIsWarnedAboutSeparately() {
            var first = task("5000");
            var second = task("7000");
            second.setId("otherTask");

            var warnings = captureWarnings(() -> {
                executor.backstopSeconds(first);
                executor.backstopSeconds(first);
                executor.backstopSeconds(second);
                executor.backstopSeconds(second);
            });

            assertEquals(2, warnings.size(), "suppression must be per task, not global: " + warnings);
        }

        @Test
        @DisplayName("a backstop-or-longer timeout is never warned about at all")
        void longTimeoutIsNotWarned() {
            var warnings = captureWarnings(() -> assertEquals(300L, executor.backstopSeconds(task("300000"))));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }

        /**
         * The static resolution is what the cascade calls to size a step's margin; the
         * step itself then runs through {@code backstopSeconds}, which warns. Warning
         * from both would repeat the line for a cascade's live step.
         */
        @Test
        @DisplayName("the pure resolution never warns — only a request made through the executor does")
        void staticResolutionDoesNotWarn() {
            var warnings = captureWarnings(() -> assertEquals(120L, StreamingLegacyChatExecutor.resolveTimeoutSeconds(task("5000"))));
            assertTrue(warnings.isEmpty(), warnings.toString());
        }

        /**
         * The suppression set is bounded by clearing it, not by refusing new keys: a
         * hard cap would silently stop warning about genuinely new misconfigurations.
         * Past the bound an already-warned task may warn once more — noise, not
         * silence.
         */
        @Test
        @DisplayName("past its bound the suppression memory is cleared, so a new task is still warned about")
        void suppressionMemoryIsBoundedByClearing() {
            var first = task("5000");

            var warnings = captureWarnings(() -> {
                executor.backstopSeconds(first);
                for (int i = 0; i < 1_000; i++) {
                    var other = task("5000");
                    other.setId("task-" + i);
                    executor.backstopSeconds(other);
                }
                executor.backstopSeconds(first);
            });

            assertEquals(1_002, warnings.size(), "every new key warns, and the cleared memory warns about the first task again");
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
    @DisplayName("The cascade's live-stream backstop tracks the executor's bound")
    class CascadeBackstopTests {

        /**
         * A live-streamed cascade step must never have its future cancelled while the
         * provider is still emitting: cancelling the awaiting thread does not stop the
         * callback thread, so the SSE client would keep receiving tokens for a step the
         * cascade has already moved past. The cascade's bound therefore has to stay
         * strictly above whatever bound the streaming executor applies — it can no
         * longer assume the 120s default.
         */
        @Test
        @DisplayName("the cascade bound always exceeds the executor's own bound")
        void cascadeBackstop_exceedsExecutorBound() {
            for (LlmConfiguration.Task candidate : List.of(task(null), task("15000"), task("300000"), taskWithStreamingTimeout(300),
                    taskWithStreamingTimeout(30))) {
                long executorMs = TimeUnit.SECONDS.toMillis(StreamingLegacyChatExecutor.resolveTimeoutSeconds(candidate));
                long cascadeMs = CascadingModelExecutor.resolveStreamingStepTimeoutMs(candidate);

                assertTrue(cascadeMs > executorMs,
                        "Cascade backstop (" + cascadeMs + "ms) must exceed the executor bound (" + executorMs + "ms)");
            }
        }

        @Test
        @DisplayName("the historical 125s bound is preserved for the default case")
        void cascadeBackstop_defaultUnchanged() {
            assertEquals(125_000L, CascadingModelExecutor.resolveStreamingStepTimeoutMs(task(null)));
            assertEquals(125_000L, CascadingModelExecutor.resolveStreamingStepTimeoutMs(task("15000")));
        }

        @Test
        @DisplayName("a longer configured bound raises the cascade backstop with it")
        void cascadeBackstop_followsLongerBound() {
            assertEquals(305_000L, CascadingModelExecutor.resolveStreamingStepTimeoutMs(task("300000")));
            assertEquals(305_000L, CascadingModelExecutor.resolveStreamingStepTimeoutMs(taskWithStreamingTimeout(300)));
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

        /**
         * A failed attempt is abandoned before it is retried, exactly like a timed-out
         * one: a provider that reports an error can still have a callback in flight,
         * and its late token must not reach the sink the retry is streaming into.
         */
        @Test
        @DisplayName("a retried error's late token must not interleave with the retry's output")
        void lateTokenFromRetriedErrorAttempt_isNotEmitted() {
            var failedAttempt = new AtomicReference<StreamingChatResponseHandler>();

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    if (failedAttempt.get() == null) {
                        failedAttempt.set(handler);
                        handler.onError(new IllegalStateException("provider 500"));
                        return;
                    }
                    // The failed attempt's callback delivers a straggler while the retry runs.
                    failedAttempt.get().onPartialResponse("LATE");
                    handler.onPartialResponse("good");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("good")).build());
                }
            };

            var task = task(null);
            var retry = new RetryConfiguration();
            retry.setMaxAttempts(2);
            retry.setBackoffDelayMs(1L);
            task.setRetry(retry);

            var result = executor.execute(model, messages(), eventSink, task);

            assertEquals("good", result.response());
            verify(eventSink, never()).onToken("LATE");
            verify(eventSink, times(1)).onToken("good");
        }

        /**
         * C2: the abandoned flag was a check-then-act and the accumulating
         * StringBuilder was read without synchronization.
         * <p>
         * A callback that had already passed the {@code abandoned} check could still
         * append and emit while the executor was setting the flag and calling
         * {@code toString()} — an unsynchronized read of a concurrently mutated
         * StringBuilder (torn count/value after a grow), and a token pushed into the
         * shared sink after the executor believed the attempt silenced.
         * <p>
         * The observable contract of the fix: abandoning waits for an in-flight
         * emission, so the text handed to memory is exactly the text the client was
         * sent. Here the sink blocks mid-emission and the backstop fires underneath it;
         * the executor must not return until the emission completes.
         */
        @Test
        @DisplayName("abandoning a timed-out attempt waits for an in-flight token emission")
        void abandonment_isMutuallyExclusiveWithTokenEmission() throws Exception {
            var emitStarted = new CountDownLatch(1);
            var releaseEmit = new CountDownLatch(1);
            var emitted = new CopyOnWriteArrayList<String>();

            ConversationEventSink blockingSink = mock(ConversationEventSink.class);
            doAnswer(invocation -> {
                emitted.add(invocation.getArgument(0));
                emitStarted.countDown();
                if (!releaseEmit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("emission was never released");
                }
                return null;
            }).when(blockingSink).onToken(any(String.class));

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    // Emit from the provider's own thread and never complete, so the
                    // executor's backstop fires while the emission is still in flight.
                    var emitter = new Thread(() -> handler.onPartialResponse("A"));
                    emitter.setDaemon(true);
                    emitter.start();
                }
            };

            var task = task(null);
            task.setStreamingTimeoutSeconds(1);

            var pool = Executors.newSingleThreadExecutor();
            try {
                var running = pool.submit(() -> executor.execute(model, messages(), blockingSink, task));

                assertTrue(emitStarted.await(5, TimeUnit.SECONDS), "the provider should have started emitting");
                // Well past the 1s backstop: the executor has timed out and wants to
                // abandon, but must block until the emission it is racing has finished.
                Thread.sleep(2500);
                assertFalse(running.isDone(),
                        "The executor must not set `abandoned` and read the buffer while a token emission is in flight");

                releaseEmit.countDown();
                var result = running.get(10, TimeUnit.SECONDS);

                assertEquals(List.of("A"), emitted, "exactly one token reached the sink");
                assertEquals(String.join("", emitted), result.response(),
                        "the text stored in memory must be exactly the text streamed to the client");
                assertEquals(Boolean.TRUE, result.metadata().get("streamingTimeout"), "the attempt did time out");
            } finally {
                pool.shutdownNow();
            }
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

    private static LlmConfiguration.Task taskWithStreamingTimeout(int seconds) {
        var task = task(null);
        task.setStreamingTimeoutSeconds(seconds);
        return task;
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("Hi"));
    }

    /**
     * Every below-backstop {@code timeout} WARN {@link StreamingLegacyChatExecutor}
     * logs while {@code body} runs — matched on its wording, so an unrelated
     * warning can neither satisfy nor spoil a count.
     */
    private static List<String> captureWarnings(Runnable body) {
        return captureRecordsOf(StreamingLegacyChatExecutor.class, body).stream()
                .filter(CapturedRecord::isWarning)
                .map(CapturedRecord::message)
                .filter(message -> message.contains("SHORTER than the 120s streaming backstop"))
                .toList();
    }
}
