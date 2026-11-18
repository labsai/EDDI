/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link StreamingLegacyChatExecutor}.
 */
class StreamingLegacyChatExecutorTest {

    private StreamingLegacyChatExecutor executor;
    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        executor = new StreamingLegacyChatExecutor();
        eventSink = mock(ConversationEventSink.class);
    }

    private static ChatResponse done(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder().finishReason(FinishReason.STOP).build()).build();
    }

    @Test
    @DisplayName("Should emit tokens and return full response")
    void execute_emitsTokensAndReturnsFullResponse() throws Exception {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hello");
                handler.onPartialResponse(" ");
                handler.onPartialResponse("world");
                handler.onCompleteResponse(done("Hello world"));
            }
        };

        List<ChatMessage> messages = List.of(UserMessage.from("Hi"));

        String result = executor.execute(streamingModel, messages, eventSink);

        assertEquals("Hello world", result);
        verify(eventSink, times(3)).onToken(anyString());
        verify(eventSink).onToken("Hello");
        verify(eventSink).onToken(" ");
        verify(eventSink).onToken("world");
    }

    @Test
    @DisplayName("Should handle empty response")
    void execute_emptyResponse_returnsEmptyString() throws Exception {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(done(""));
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);

        assertEquals("", result);
        verify(eventSink, never()).onToken(anyString());
    }

    @Test
    @DisplayName("Should fail the call when the provider errors before any token")
    void execute_error_throws() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onError(new RuntimeException("LLM connection failed"));
            }
        };

        assertThrows(LifecycleException.class, () -> executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink));
        verify(eventSink, never()).onToken(anyString());
    }

    @Test
    @DisplayName("Should fail the call — not return the fragment — when the provider errors after tokens were emitted")
    void execute_errorAfterPartial_fails() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("partial");
                handler.onError(new RuntimeException("LLM connection failed"));
            }
        };

        assertThrows(LifecycleException.class, () -> executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink));
        verify(eventSink).onToken("partial");
    }

    @Test
    @DisplayName("Should handle single-token response")
    void execute_singleToken_emitsOneToken() throws Exception {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("OK");
                handler.onCompleteResponse(done("OK"));
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);

        assertEquals("OK", result);
        verify(eventSink, times(1)).onToken("OK");
    }

    @Test
    @DisplayName("Should continue even if eventSink.onToken throws")
    void execute_sinkThrows_continuesStreaming() throws Exception {
        doThrow(new RuntimeException("sink error")).when(eventSink).onToken(anyString());

        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hello");
                handler.onPartialResponse(" world");
                handler.onCompleteResponse(done("Hello world"));
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);
        assertEquals("Hello world", result);
    }
}
