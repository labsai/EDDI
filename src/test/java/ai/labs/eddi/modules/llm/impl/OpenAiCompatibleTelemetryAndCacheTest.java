/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleEndpoint;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WS10: a self-hosted endpoint is reported under its own bounded
 * {@code serverName} (the value telemetry uses for both the
 * {@code gen_ai.provider.name} span attribute and the {@code provider} meter
 * tag), and its capability flags do not fragment the model cache.
 */
@DisplayName("openai-compatible — telemetry name and model cache key")
class OpenAiCompatibleTelemetryAndCacheTest {

    private SimpleMeterRegistry registry;
    private LlmTelemetryListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new LlmTelemetryListener(registry);
    }

    private static void call(ChatModelListener l, ModelProvider provider) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        var req = ChatRequest.builder().messages(UserMessage.from("hi"))
                .parameters(ChatRequestParameters.builder().modelName("granite").build()).build();
        l.onRequest(new ChatModelRequestContext(req, provider, attributes));
        l.onResponse(new ChatModelResponseContext(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build(), req, provider, attributes));
    }

    private boolean taggedAs(String provider) {
        return registry.find("eddi.llm.request.duration").tag("provider", provider).timer() != null;
    }

    @Test
    @DisplayName("the serverName wins over langchain4j's OPEN_AI for traffic that never left the cluster")
    void serverNameWins() {
        call(LlmTelemetryListener.forModelType(listener, OpenAiCompatibleEndpoint.TYPE, "vllm"), ModelProvider.OPEN_AI);
        assertTrue(taggedAs("vllm"));
        assertFalse(taggedAs("OPEN_AI"));
    }

    @Test
    @DisplayName("the decorators carry the provider name, sync and streaming")
    void decoratorsCarryIt() {
        var sync = ObservableChatModel.wrap(mock(ChatModel.class), OpenAiCompatibleEndpoint.TYPE, null, null, null, listener, "llama-server");
        call(sync.listeners().getFirst(), ModelProvider.OPEN_AI);
        assertTrue(taggedAs("llama-server"));

        var streaming = (ObservableStreamingChatModel) ObservableStreamingChatModel.wrap(mock(StreamingChatModel.class),
                OpenAiCompatibleEndpoint.TYPE, null, null, listener, "tgi");
        call(streaming.listeners().getFirst(), ModelProvider.OPEN_AI);
        assertTrue(taggedAs("tgi"));
    }

    @Test
    @DisplayName("other types keep the old resolution: OPEN_AI stays OPEN_AI")
    void openAiUnchanged() {
        assertNull(ChatModelRegistry.telemetryProvider("openai", Map.of("serverName", "vllm")));
        call(LlmTelemetryListener.forModelType(listener, "openai", null), ModelProvider.OPEN_AI);
        assertTrue(taggedAs("OPEN_AI"));
    }

    @Test
    @DisplayName("the label is bounded: lower-cased, [a-z0-9._-], at most 32 characters, else the type name")
    void labelIsBounded() {
        assertEquals("vllm", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", " VLLM ")));
        assertEquals("llama-server_2.1", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", "llama-server_2.1")));
        assertEquals("openai-compatible", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of()));
        assertEquals("openai-compatible", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", "my server")));
        assertEquals("openai-compatible", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", "{properties.x}")));
        assertEquals("openai-compatible", ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", "a".repeat(33))));
        assertEquals("a".repeat(32), ChatModelRegistry.telemetryProvider("openai-compatible", Map.of("serverName", "a".repeat(32))));
    }

    @Test
    @DisplayName("capability flags stay out of the cache key; serverName and everything else stay in")
    void cacheKey() {
        Map<String, String> a = Map.of("baseUrl", "http://vllm:8000/v1", "modelName", "m", "serverName", "vllm", "jsonMode", "true",
                "jsonModeWithTools", "false", "vision", "true");
        Map<String, String> b = Map.of("baseUrl", "http://vllm:8000/v1", "modelName", "m", "serverName", "vllm");

        assertEquals(new ChatModelRegistry.ModelCacheKey("openai-compatible", ChatModelRegistry.cacheKeyParams("openai-compatible", a)),
                new ChatModelRegistry.ModelCacheKey("openai-compatible", ChatModelRegistry.cacheKeyParams("openai-compatible", b)));
        assertEquals("vllm", ChatModelRegistry.cacheKeyParams("openai-compatible", a).get("serverName"));
        // Another type is keyed on its full parameter map, as before.
        assertSame(a, ChatModelRegistry.cacheKeyParams("openai", a));
        assertNotNull(ChatModelRegistry.cacheKeyParams("openai", a).get("jsonMode"));
    }
}
