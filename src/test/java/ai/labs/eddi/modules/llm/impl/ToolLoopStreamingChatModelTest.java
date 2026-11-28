/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The bridge that lets the synchronous tool loop stream: partial tokens are
 * forwarded to the event sink as they arrive, the complete response is returned
 * synchronously, and {@code lastForwardedText} records exactly what the last
 * completed round token-forwarded — the caller's evidence for deciding whether
 * the single-chunk fallback emit is still needed.
 */
@DisplayName("ToolLoopStreamingChatModel")
class ToolLoopStreamingChatModelTest {

    private static final ChatResponse COMPLETE = ChatResponse.builder().aiMessage(AiMessage.from("Hello world"))
            .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build()).build();

    /**
     * A completion without a finish reason: how a client library reports a stream
     * that just closed.
     */
    private static final ChatResponse NO_FINISH = ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build();

    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        eventSink = mock(ConversationEventSink.class);
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    /** Emits the given partials, then completes — all synchronously. */
    private static StreamingChatModel streaming(String... partials) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                for (String partial : partials) {
                    handler.onPartialResponse(partial);
                }
                handler.onCompleteResponse(COMPLETE);
            }
        };
    }

    @Test
    @DisplayName("forwards partial tokens in order, returns the complete response, records the forwarded text")
    void forwardsTokensAndReturnsCompleteResponse() {
        var bridge = new ToolLoopStreamingChatModel(streaming("Hello ", "world"), eventSink, 30, "openai");

        var response = bridge.chat(request());

        assertSame(COMPLETE, response);
        InOrder inOrder = inOrder(eventSink);
        inOrder.verify(eventSink).onToken("Hello ");
        inOrder.verify(eventSink).onToken("world");
        assertEquals("Hello world", bridge.lastForwardedText());
    }

    @Test
    @DisplayName("a JSON-formatted round is not forwarded — partial JSON is unrenderable — but still completes")
    void jsonRoundIsNotForwarded() {
        var bridge = new ToolLoopStreamingChatModel(streaming("{\"a\":", "1}"), eventSink, 30, "openai");
        var jsonRequest = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).responseFormat(ResponseFormat.JSON).build();

        var response = bridge.chat(jsonRequest);

        assertSame(COMPLETE, response);
        verify(eventSink, never()).onToken(anyString());
        assertEquals("", bridge.lastForwardedText(), "a round that forwarded nothing must not claim it streamed");
    }

    @Test
    @DisplayName("each call resets the forwarded record — a later pure tool-call round must not inherit an earlier round's text")
    void lastForwardedTextIsPerCall() {
        // First round streams text; second round completes without partials, the
        // shape of a pure tool-call round. If the record survived the second call,
        // LlmTask would suppress the fallback emit against STALE evidence.
        var rounds = new ArrayDeque<>(List.of(true, false));
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                if (Boolean.TRUE.equals(rounds.poll())) {
                    handler.onPartialResponse("interim text");
                }
                handler.onCompleteResponse(COMPLETE);
            }
        };
        var bridge = new ToolLoopStreamingChatModel(model, eventSink, 30, "openai");

        bridge.chat(request());
        assertEquals("interim text", bridge.lastForwardedText());

        bridge.chat(request());
        assertEquals("", bridge.lastForwardedText());
    }

    @Test
    @DisplayName("a separator is streamed between rounds that both forward text — but never recorded as forwarded")
    void interRoundSeparatorIsStreamedButNotRecorded() {
        var bridge = new ToolLoopStreamingChatModel(streaming("interim commentary"), eventSink, 30, "openai");

        bridge.chat(request());
        var secondRound = new ToolLoopStreamingChatModel(streaming("final answer"), eventSink, 30, "openai");
        // Same bridge instance across rounds is the production shape — reuse it.
        bridge.chat(request());

        InOrder inOrder = inOrder(eventSink);
        inOrder.verify(eventSink).onToken("interim commentary");
        inOrder.verify(eventSink).onToken("\n\n");
        inOrder.verify(eventSink).onToken("interim commentary");
        // The suppression record stays pure round text — the separator would
        // otherwise break the caller's exact-match comparison.
        assertEquals("interim commentary", bridge.lastForwardedText());
        assertEquals("", secondRound.lastForwardedText(), "a fresh bridge has forwarded nothing");
    }

    @Test
    @DisplayName("a provider error is rethrown synchronously, as the loop's retry expects")
    void errorIsPropagated() {
        var boom = new IllegalStateException("provider failed");
        StreamingChatModel failing = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onError(boom);
            }
        };
        var bridge = new ToolLoopStreamingChatModel(failing, eventSink, 30, "openai");

        var thrown = assertThrows(IllegalStateException.class, () -> bridge.chat(request()));

        assertSame(boom, thrown);
    }

    @Test
    @DisplayName("timeout throws in ObservableChatModel's shape, and a late token is silenced, never forwarded")
    void timeoutAbandonsAndSilencesLateTokens() {
        var handlerRef = new AtomicReference<StreamingChatResponseHandler>();
        StreamingChatModel neverAnswers = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handlerRef.set(handler);
            }
        };
        // timeoutSeconds=0 → the await elapses immediately, no test latency.
        var bridge = new ToolLoopStreamingChatModel(neverAnswers, eventSink, 0, "openai");

        var thrown = assertThrows(RuntimeException.class, () -> bridge.chat(request()));
        assertTrue(thrown.getMessage().contains("timed out"), thrown.getMessage());
        // The typed TimeoutException cause is what RetryConfiguration keys on
        // ("timed out" does NOT hit the "timeout" message fallback). Without it,
        // streamed tool-loop timeouts fail the turn while the sync path retries.
        assertInstanceOf(TimeoutException.class, thrown.getCause());
        assertTrue(RetryConfiguration.isRetryableError(thrown),
                "a provider timeout on the streaming transport must stay retryable, exactly like the sync path");

        // The provider's callback thread cannot be cancelled — a token arriving
        // after abandonment must not reach the shared sink, where it would
        // interleave with a retry's stream.
        handlerRef.get().onPartialResponse("late token");
        verify(eventSink, never()).onToken(anyString());
    }

    @Test
    @DisplayName("an interrupt restores the flag — a cancelled turn must abort, not retry into more tool calls")
    void interruptRestoresFlagAndSilencesLateTokens() {
        var handlerRef = new AtomicReference<StreamingChatResponseHandler>();
        StreamingChatModel neverAnswers = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handlerRef.set(handler);
            }
        };
        var bridge = new ToolLoopStreamingChatModel(neverAnswers, eventSink, 30, "openai");

        Thread.currentThread().interrupt();
        try {
            var thrown = assertThrows(RuntimeException.class, () -> bridge.chat(request()));
            assertTrue(thrown.getMessage().contains("interrupted"), thrown.getMessage());
            // The restored flag is what makes the loop's retry backoff and its
            // loop-top Thread.interrupted() checks abort the cancelled turn.
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
        } finally {
            // Clear so the flag never leaks into the next test on this worker.
            Thread.interrupted();
        }

        handlerRef.get().onPartialResponse("late token");
        verify(eventSink, never()).onToken(anyString());
    }

    @Test
    @DisplayName("a sink that throws does not abort the stream — later tokens still forward and the round completes")
    void sinkErrorDoesNotAbortStream() {
        doThrow(new RuntimeException("client gone")).when(eventSink).onToken("Hello ");
        var bridge = new ToolLoopStreamingChatModel(streaming("Hello ", "world"), eventSink, 30, "openai");

        var response = bridge.chat(request());

        assertSame(COMPLETE, response);
        verify(eventSink).onToken("world");
        // The failed token still counts as forwarded — same salvage stance as
        // StreamingLegacyChatExecutor, which also swallows sink errors.
        assertEquals("Hello world", bridge.lastForwardedText());
    }

    @Test
    @DisplayName("a round that fails after forwarding text is not retryable: a retry would stream it again into the same sink")
    void failureAfterForwardedTextIsNotRetryable() {
        var boom = new RuntimeException("503 service unavailable");
        StreamingChatModel failsMidStream = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Partial ans");
                handler.onError(boom);
            }
        };
        var bridge = new ToolLoopStreamingChatModel(failsMidStream, eventSink, 30, "openai");

        var thrown = assertThrows(ToolLoopStreamingChatModel.PartiallyStreamedRoundException.class, () -> bridge.chat(request()));

        assertFalse(RetryConfiguration.isRetryableError(thrown), "the provider's 503 would otherwise be retried into the same sink");
        assertSame(boom, thrown.getSuppressed()[0], "the provider's failure is kept for diagnosis");
    }

    /**
     * The classifier's last-resort wording test reads a standalone 429/502/503/504
     * in a message as an HTTP status. The exception used to name the number of
     * characters forwarded, so a round that failed after exactly 503 of them was
     * classified transient and retried into the same sink. The provider failure
     * here is deliberately one the classifier calls non-retryable, so only the
     * exception's own message could make it retryable.
     */
    @ParameterizedTest(name = "{0} characters forwarded")
    @ValueSource(ints = {429, 502, 503, 504})
    @DisplayName("the forwarded count never reaches the message, where it would read as an HTTP status")
    void forwardedCountThatLooksLikeAStatusIsNotRetryable(int forwardedChars) {
        String fragment = "x".repeat(forwardedChars);
        StreamingChatModel failsMidStream = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(fragment);
                handler.onError(new IllegalStateException("invalid request"));
            }
        };
        var bridge = new ToolLoopStreamingChatModel(failsMidStream, eventSink, 30, "openai");

        var thrown = assertThrows(ToolLoopStreamingChatModel.PartiallyStreamedRoundException.class, () -> bridge.chat(request()));

        assertFalse(thrown.getMessage().contains(String.valueOf(forwardedChars)), thrown.getMessage());
        assertFalse(RetryConfiguration.isRetryableError(thrown),
                "a retry would stream the round again after the client already received " + forwardedChars + " characters of it");
    }

    @Test
    @DisplayName("a round that fails before forwarding anything stays retryable")
    void failureBeforeAnyTextStaysRetryable() {
        var boom = new RuntimeException("503 service unavailable");
        StreamingChatModel failsAtOnce = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onError(boom);
            }
        };
        var bridge = new ToolLoopStreamingChatModel(failsAtOnce, eventSink, 30, "openai");

        var thrown = assertThrows(RuntimeException.class, () -> bridge.chat(request()));

        assertSame(boom, thrown);
        assertTrue(RetryConfiguration.isRetryableError(thrown));
    }

    /**
     * Emits the given partials, then completes with {@code response} —
     * synchronously.
     */
    private static StreamingChatModel streamingThen(ChatResponse response, String... partials) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                for (String partial : partials) {
                    handler.onPartialResponse(partial);
                }
                handler.onCompleteResponse(response);
            }
        };
    }

    private static StreamPolicy policy(boolean requireFinishReason, List<String> partialSink) {
        return new StreamPolicy(30, 0, requireFinishReason, null, partialSink != null ? partialSink::add : null, false);
    }

    @Test
    @DisplayName("S-1: a stream that ended without a finish reason after text is a failure, not an answer — and not retried")
    void streamWithoutFinishReasonAfterTextFails() {
        var partials = new ArrayList<String>();
        var bridge = new ToolLoopStreamingChatModel(streamingThen(NO_FINISH, "Hel", "lo"), eventSink, policy(true, partials), "openai",
                ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        var thrown = assertThrows(ToolLoopStreamingChatModel.PartiallyStreamedRoundException.class, () -> bridge.chat(request()));

        assertInstanceOf(ToolLoopStreamingChatModel.IncompleteStreamException.class, thrown.getSuppressed()[0]);
        assertFalse(RetryConfiguration.isRetryableError(thrown), "the client already has the fragment");
        assertEquals("Hello", thrown.partialText());
        assertEquals(List.of("Hello"), partials, "the fragment is kept as error detail");
        assertEquals("", bridge.lastForwardedText(), "a failed call never counts as streamed live");
    }

    @Test
    @DisplayName("a stream that ended without a finish reason before any text is a retryable transport failure")
    void streamWithoutFinishReasonBeforeTextIsRetryable() {
        var bridge = new ToolLoopStreamingChatModel(streamingThen(NO_FINISH), eventSink, policy(true, null), "openai",
                ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        var thrown = assertThrows(ToolLoopStreamingChatModel.IncompleteStreamException.class, () -> bridge.chat(request()));

        assertTrue(RetryConfiguration.isRetryableError(thrown));
    }

    @Test
    @DisplayName("requireFinishReason=false accepts a server that never sends one")
    void finishReasonOptOut() {
        var bridge = new ToolLoopStreamingChatModel(streamingThen(NO_FINISH, "Hello"), eventSink, policy(false, null), "openai",
                ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        assertSame(NO_FINISH, bridge.chat(request()));
        assertEquals("Hello", bridge.lastForwardedText());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = FinishReason.class, names = {"LENGTH", "CONTENT_FILTER"})
    @DisplayName("an abnormal finish reason is an answer, and the bridge reports it for the turn's outcome")
    void abnormalFinishIsRecorded(FinishReason reason) {
        var response = ChatResponse.builder().aiMessage(AiMessage.from("cut"))
                .metadata(ChatResponseMetadata.builder().finishReason(reason).build()).build();
        var bridge = new ToolLoopStreamingChatModel(streamingThen(response, "cut"), eventSink, policy(true, null), "openai",
                ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        bridge.chat(request());
        Map<String, Object> metadata = new HashMap<>();
        bridge.recordLastFinish(metadata);

        assertEquals(reason, bridge.lastFinishReason());
        assertEquals(reason.toString(), metadata.get("finishReason"));
        assertEquals(reason == FinishReason.LENGTH ? "truncated" : "content_filter", metadata.get("warning"));
        assertEquals(reason == FinishReason.LENGTH ? "length" : "content_filter", LlmTask.abnormalFinishReason(metadata));
    }

    @Test
    @DisplayName("B9: a cancelled turn stops the call, cancels the provider stream and forwards nothing more")
    void cancelledTurnStopsTheStream() {
        var cancelled = new AtomicBoolean();
        var handleCancelled = new AtomicBoolean();
        var handlerRef = new AtomicReference<StreamingChatResponseHandler>();
        StreamingHandle handle = new StreamingHandle() {
            @Override
            public void cancel() {
                handleCancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return handleCancelled.get();
            }
        };
        StreamingChatModel streamsThenStalls = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handlerRef.set(handler);
                handler.onPartialResponse(new PartialResponse("first "), new PartialResponseContext(handle));
                // The user presses stop while the model is still writing.
                cancelled.set(true);
            }
        };
        var partials = new ArrayList<String>();
        var bridge = new ToolLoopStreamingChatModel(streamsThenStalls, eventSink,
                new StreamPolicy(30, 0, true, cancelled::get, partials::add, false), "openai", ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        var thrown = assertThrows(ToolLoopStreamingChatModel.StreamCancelledException.class, () -> bridge.chat(request()));

        assertFalse(RetryConfiguration.isRetryableError(thrown), "a cancel is never retried");
        assertTrue(handleCancelled.get(), "the provider is told to stop, so the spend stops too");
        handlerRef.get().onPartialResponse("second");
        verify(eventSink).onToken("first ");
        verify(eventSink, never()).onToken("second");
        assertEquals(List.of("first "), partials);
    }

    @Test
    @DisplayName("an idle gap after the first token ends the call as a timeout — partial text makes it non-retryable")
    void idleGapIsATimeout() {
        StreamingChatModel stalls = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("partial answer before the hang ");
            }
        };
        var bridge = new ToolLoopStreamingChatModel(stalls, eventSink, new StreamPolicy(30, 1, true, null, null, false), "openai",
                ToolLoopStreamingChatModel.PATH_TOOL_LOOP);

        var thrown = assertThrows(ToolLoopStreamingChatModel.PartiallyStreamedRoundException.class, () -> bridge.chat(request()));

        assertTrue(thrown.timedOut());
        assertInstanceOf(TimeoutException.class, thrown.getSuppressed()[0].getCause());
    }
}
