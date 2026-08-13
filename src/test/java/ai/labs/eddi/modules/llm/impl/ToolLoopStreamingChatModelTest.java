/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final ChatResponse COMPLETE = ChatResponse.builder().aiMessage(AiMessage.from("Hello world")).build();

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

        // The provider's callback thread cannot be cancelled — a token arriving
        // after abandonment must not reach the shared sink, where it would
        // interleave with a retry's stream.
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
}
