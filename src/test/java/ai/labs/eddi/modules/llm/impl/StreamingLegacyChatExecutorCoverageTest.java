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
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for {@link StreamingLegacyChatExecutor#executeCapturing} —
 * token usage / finish-reason capture and the error path.
 */
@DisplayName("StreamingLegacyChatExecutor — Coverage")
class StreamingLegacyChatExecutorCoverageTest {

    @Test
    @DisplayName("executeCapturing — streams tokens and captures token usage + finish reason")
    void capturesMetadata() {
        var sink = mock(ConversationEventSink.class);

        ChatResponseMetadata md = mock(ChatResponseMetadata.class);
        when(md.finishReason()).thenReturn(FinishReason.STOP);
        when(md.tokenUsage()).thenReturn(new TokenUsage(10, 20, 30));
        ChatResponse complete = mock(ChatResponse.class);
        when(complete.metadata()).thenReturn(md);

        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("Hel");
            h.onPartialResponse("lo");
            h.onCompleteResponse(complete);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var result = new StreamingLegacyChatExecutor().executeCapturing(model, List.of(UserMessage.from("hi")), sink);

        assertEquals("Hello", result.response());
        assertEquals("STOP", result.responseMetadata().get("finishReason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tu = (Map<String, Object>) result.responseMetadata().get("tokenUsage");
        assertEquals(10, tu.get("inputTokens"));
        assertEquals(20, tu.get("outputTokens"));
        assertEquals(30, tu.get("totalTokens"));
        verify(sink, times(2)).onToken(anyString());
    }

    @Test
    @DisplayName("execute (delegating) — no metadata still returns the accumulated text")
    void executeDelegates_noMetadata() {
        var sink = mock(ConversationEventSink.class);
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("plain");
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("plain")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        String text = new StreamingLegacyChatExecutor().execute(model, List.of(UserMessage.from("hi")), sink);
        assertEquals("plain", text);
    }

    @Test
    @DisplayName("executeCapturing — metadata present but finishReason/tokenUsage null")
    void metadataWithNullFields() {
        var sink = mock(ConversationEventSink.class);
        ChatResponseMetadata md = mock(ChatResponseMetadata.class);
        when(md.finishReason()).thenReturn(null);
        when(md.tokenUsage()).thenReturn(null);
        ChatResponse complete = mock(ChatResponse.class);
        when(complete.metadata()).thenReturn(md);

        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("x");
            h.onCompleteResponse(complete);
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var result = new StreamingLegacyChatExecutor().executeCapturing(model, List.of(UserMessage.from("hi")), sink);
        assertEquals("x", result.response());
        assertTrue(result.responseMetadata().isEmpty());
    }

    @Test
    @DisplayName("executeCapturing — onError propagates as RuntimeException")
    void errorPropagates() {
        var sink = mock(ConversationEventSink.class);
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onError(new RuntimeException("boom"));
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        var ex = assertThrows(RuntimeException.class,
                () -> new StreamingLegacyChatExecutor().executeCapturing(model, List.of(UserMessage.from("hi")), sink));
        assertTrue(ex.getMessage().contains("Streaming chat failed"));
    }
}
