/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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

    @Test
    @DisplayName("Should emit tokens and return full response")
    void execute_emitsTokensAndReturnsFullResponse() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hello");
                handler.onPartialResponse(" ");
                handler.onPartialResponse("world");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello world")).build());
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
    void execute_emptyResponse_returnsEmptyString() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("")).build());
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);

        assertEquals("", result);
        verify(eventSink, never()).onToken(anyString());
    }

    @Test
    @DisplayName("Should propagate streaming errors as RuntimeException")
    void execute_error_throwsRuntimeException() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("partial");
                handler.onError(new RuntimeException("LLM connection failed"));
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink));

        assertTrue(ex.getMessage().contains("Streaming chat failed"));
        verify(eventSink).onToken("partial");
    }

    @Test
    @DisplayName("Should handle single-token response")
    void execute_singleToken_emitsOneToken() {
        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("OK");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);

        assertEquals("OK", result);
        verify(eventSink, times(1)).onToken("OK");
    }

    @Test
    @DisplayName("Should continue even if eventSink.onToken throws")
    void execute_sinkThrows_continuesStreaming() {
        doThrow(new RuntimeException("sink error")).when(eventSink).onToken(anyString());

        StreamingChatModel streamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hello");
                handler.onPartialResponse(" world");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello world")).build());
            }
        };

        String result = executor.execute(streamingModel, List.of(UserMessage.from("Hi")), eventSink);
        assertEquals("Hello world", result);
    }
}
