/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SummarizationServiceTest {

    private ChatModelRegistry chatModelRegistry;
    private MeterRegistry meterRegistry;
    private SummarizationService service;

    @BeforeEach
    void setUp() {
        chatModelRegistry = mock(ChatModelRegistry.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new SummarizationService(chatModelRegistry, meterRegistry);
        service.initMetrics();
    }

    @Test
    void summarize_success_returnsSummaryText() throws Exception {
        // Given
        var chatModel = mock(ChatModel.class);
        when(chatModelRegistry.getOrCreate(eq("anthropic"), any())).thenReturn(chatModel);

        var aiMessage = AiMessage.from("Concise summary of the conversation");
        var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        // When
        String result = service.summarize("Long conversation text", "Summarize this", "anthropic", "claude-sonnet-4-6");

        // Then
        assertEquals("Concise summary of the conversation", result);
        assertEquals(1.0, meterRegistry.counter("summarization.calls").count());
    }

    @Test
    void summarize_passesCorrectModelParams() throws Exception {
        // Given
        var chatModel = mock(ChatModel.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        when(chatModelRegistry.getOrCreate(eq("openai"), paramsCaptor.capture())).thenReturn(chatModel);

        var aiMessage = AiMessage.from("summary");
        var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        // When
        service.summarize("text", "instructions", "openai", "gpt-4o-mini");

        // Then
        Map<String, String> params = paramsCaptor.getValue();
        assertEquals("gpt-4o-mini", params.get("modelName"));
    }

    @Test
    void summarize_passesSystemAndUserMessages() throws Exception {
        // Given
        var chatModel = mock(ChatModel.class);
        when(chatModelRegistry.getOrCreate(any(), any())).thenReturn(chatModel);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        var aiMessage = AiMessage.from("summary");
        var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
        when(chatModel.chat(requestCaptor.capture())).thenReturn(chatResponse);

        // When
        service.summarize("conversation content", "System instructions here", "anthropic", "model");

        // Then
        ChatRequest request = requestCaptor.getValue();
        var messages = request.messages();
        assertEquals(2, messages.size());
        // First message is system message with instructions
        assertTrue(messages.get(0).toString().contains("System instructions here"));
        // Second message is user message with content
        assertTrue(messages.get(1).toString().contains("conversation content"));
    }

    @Test
    void summarize_llmError_returnsEmptyString() throws Exception {
        // Given
        var chatModel = mock(ChatModel.class);
        when(chatModelRegistry.getOrCreate(any(), any())).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM API error"));

        // When
        String result = service.summarize("text", "instructions", "anthropic", "model");

        // Then
        assertEquals("", result);
        // Metrics should still record the duration (finally block)
        assertTrue(meterRegistry.timer("summarization.duration").count() > 0);
    }

    @Test
    void summarize_emptyResponse_returnsEmptyString() throws Exception {
        // Given
        var chatModel = mock(ChatModel.class);
        when(chatModelRegistry.getOrCreate(any(), any())).thenReturn(chatModel);

        var aiMessage = AiMessage.from("  ");
        var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        // When
        String result = service.summarize("text", "instructions", "anthropic", "model");

        // Then — blank/whitespace-only responses should be treated as empty
        assertTrue(result.isBlank());
    }
}
