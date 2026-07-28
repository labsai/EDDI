/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding F13 — the summarizer built its model parameters with
 * {@code modelName} and nothing else. With no {@code apiKey} or {@code baseUrl}
 * it could never authenticate, the failure was swallowed as a WARN, and the
 * rolling summary silently never materialised.
 */
@DisplayName("SummarizationService — credentials are inherited (F13)")
class SummarizationServiceInheritanceTest {

    private ChatModelRegistry registry;
    private SummarizationService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(ChatModelRegistry.class);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("a summary")).build());
        when(registry.getOrCreate(anyString(), any())).thenReturn(model);

        service = new SummarizationService(registry, new SimpleMeterRegistry());
        service.initMetrics();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedParams() throws Exception {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(registry).getOrCreate(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the parent task's apiKey and baseUrl reach the summarizer model")
    void inheritsCredentials() throws Exception {
        Map<String, String> parentParams = new HashMap<>(Map.of(
                "apiKey", "sk-parent-key",
                "baseUrl", "https://llm.internal/v1",
                "modelName", "gpt-4o"));

        service.summarize("content", "instructions", "openai", "gpt-4o-mini", parentParams);

        Map<String, String> params = capturedParams();
        assertEquals("sk-parent-key", params.get("apiKey"), "without the key the summarizer can never authenticate");
        assertEquals("https://llm.internal/v1", params.get("baseUrl"));
    }

    @Test
    @DisplayName("modelName is overridden with the (cheaper) summarizer model")
    void overridesModelName() throws Exception {
        Map<String, String> parentParams = new HashMap<>(Map.of("apiKey", "k", "modelName", "gpt-4o"));

        service.summarize("content", "instructions", "openai", "gpt-4o-mini", parentParams);

        assertEquals("gpt-4o-mini", capturedParams().get("modelName"));
    }

    @Test
    @DisplayName("responseFormat is stripped — a summary is plain text, never JSON")
    void stripsResponseFormat() throws Exception {
        Map<String, String> parentParams = new HashMap<>(Map.of("apiKey", "k", "responseFormat", "json"));

        service.summarize("content", "instructions", "openai", "gpt-4o-mini", parentParams);

        assertFalse(capturedParams().containsKey("responseFormat"));
    }

    @Test
    @DisplayName("the caller's parameter map is not mutated")
    void doesNotMutateCallerMap() throws Exception {
        Map<String, String> parentParams = new HashMap<>(Map.of("apiKey", "k", "modelName", "gpt-4o"));

        service.summarize("content", "instructions", "openai", "gpt-4o-mini", parentParams);

        assertEquals("gpt-4o", parentParams.get("modelName"), "the parent task's params must survive intact");
    }

    @Test
    @DisplayName("the legacy 4-arg call still works and passes only the model name")
    void legacyCallPassesModelNameOnly() throws Exception {
        service.summarize("content", "instructions", "openai", "gpt-4o-mini");

        Map<String, String> params = capturedParams();
        assertEquals("gpt-4o-mini", params.get("modelName"));
        assertNull(params.get("apiKey"));
    }
}
