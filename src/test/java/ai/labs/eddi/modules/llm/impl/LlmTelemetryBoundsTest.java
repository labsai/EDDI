/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * L1 (bounded model tag), L2 (provider named when langchain4j cannot), L3
 * (stream abandonment counted when EDDI gives up) and L4 (no raw provider text
 * on spans).
 */
@DisplayName("LLM telemetry — bounded tags, provider naming, stream timeouts, span error text")
class LlmTelemetryBoundsTest {

    private SimpleMeterRegistry registry;
    private LlmTelemetryListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new LlmTelemetryListener(registry);
    }

    private static ChatRequest request(String modelName) {
        return ChatRequest.builder().messages(UserMessage.from("hi"))
                .parameters(ChatRequestParameters.builder().modelName(modelName).build()).build();
    }

    private void call(ChatModelListener l, ModelProvider provider, String model) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        var req = request(model);
        l.onRequest(new ChatModelRequestContext(req, provider, attributes));
        l.onResponse(new ChatModelResponseContext(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(), req, provider, attributes));
    }

    @Test
    @DisplayName("L1: the model tag keeps its own series only up to the cap; the rest share 'other'")
    void modelTagIsBounded() {
        for (int i = 0; i < LlmTelemetryListener.MAX_MODEL_TAGS + 25; i++) {
            call(listener, ModelProvider.OPEN_AI, "user-supplied-model-" + i);
        }

        long distinct = registry.find("eddi.llm.request.duration").timers().stream().map(t -> t.getId().getTag("model")).distinct().count();
        assertEquals(LlmTelemetryListener.MAX_MODEL_TAGS + 1, distinct, "the cap plus the shared overflow tag, not one series per value");
        assertEquals(25, registry.find("eddi.llm.request.duration").tag("model", LlmTelemetryListener.OTHER_MODEL).timer().count());

        // A model seen before the cap filled keeps its own series afterwards.
        call(listener, ModelProvider.OPEN_AI, "user-supplied-model-0");
        assertEquals(2, registry.find("eddi.llm.request.duration").tag("model", "user-supplied-model-0").timer().count());
    }

    @Test
    @DisplayName("L1: an absurdly long model name is cut before it becomes a tag")
    void longModelNameIsCut() {
        call(listener, ModelProvider.OPEN_AI, "m".repeat(1000));

        String tag = registry.find("eddi.llm.request.duration").timer().getId().getTag("model");
        assertEquals(LlmTelemetryListener.MAX_MODEL_NAME_LENGTH, tag.length());
    }

    @Test
    @DisplayName("L2: a model langchain4j reports as OTHER is tagged with EDDI's model type")
    void otherProviderIsNamedByModelType() {
        call(LlmTelemetryListener.forModelType(listener, "jlama"), ModelProvider.OTHER, "tjake/Llama");
        call(LlmTelemetryListener.forModelType(listener, "openai"), ModelProvider.OPEN_AI, "gpt-4o");

        assertNotNull(registry.find("eddi.llm.request.duration").tag("provider", "jlama").timer(),
                "Jlama must not disappear into provider=OTHER");
        assertNotNull(registry.find("eddi.llm.request.duration").tag("provider", "OPEN_AI").timer(),
                "a provider langchain4j does name keeps langchain4j's name");
    }

    @Test
    @DisplayName("L2: only EDDI's own listener is wrapped; any other listener passes through unchanged")
    void foreignListenerIsNotWrapped() {
        ChatModelListener foreign = new ChatModelListener() {
        };
        assertSame(foreign, LlmTelemetryListener.forModelType(foreign, "jlama"));
        assertSame(listener, LlmTelemetryListener.forModelType(listener, null));
    }

    @Test
    @DisplayName("L2: the decorator registers the model-type-aware listener")
    void decoratorTagsItsModelType() {
        var wrapped = ObservableChatModel.wrap(mock(ChatModel.class), "huggingface", null, null, null, listener);
        var registered = wrapped.listeners().getFirst();
        call(registered, ModelProvider.OTHER, "mistral-7b");
        assertNotNull(registry.find("eddi.llm.request.duration").tag("provider", "huggingface").timer());
    }

    @Test
    @DisplayName("L4: span error text is secret-redacted and capped")
    void spanErrorTextIsSafe() {
        String leaked = "401 Unauthorized for https://api.example.com/v1?api_key=sk-abcdefghijklmnopqrstuvwxyz0123456789 " + "x".repeat(2000);

        String safe = LlmTelemetryListener.safeErrorText(leaked);

        assertFalse(safe.contains("sk-abcdefghijklmnopqrstuvwxyz0123456789"), safe);
        assertTrue(safe.length() <= LlmTelemetryListener.MAX_ERROR_TEXT_LENGTH + 1, "capped: " + safe.length());
        assertEquals("unknown", LlmTelemetryListener.safeErrorText(null));
    }

    @Test
    @DisplayName("L3: a stream EDDI abandons at its backstop is counted at that moment")
    void streamTimeoutIsCounted() throws Exception {
        StreamingChatModel stalls = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("partial");
                // never completes
            }
        };
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setStreamingTimeoutSeconds(1);

        var result = new StreamingLegacyChatExecutor(registry).execute(stalls, List.of(UserMessage.from("hi")), mock(ConversationEventSink.class),
                task);

        assertEquals(Boolean.TRUE, result.metadata().get("streamingTimeout"));
        assertEquals(1.0, registry.find("eddi.llm.stream.timeouts").tag("path", "legacy").counter().count());
    }
}
