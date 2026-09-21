/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grades the meters {@link LlmTelemetryListener} emits.
 * <p>
 * The span side is not asserted here: with no SDK installed
 * {@code GlobalOpenTelemetry} hands back a no-op tracer, so a span assertion
 * would grade the no-op rather than the attributes. What these tests do cover
 * is that the callbacks run to completion against that no-op tracer — which is
 * the shape every deployment with {@code quarkus.otel.sdk.disabled=true} runs
 * in, i.e. the default.
 */
@DisplayName("LlmTelemetryListener")
class LlmTelemetryListenerTest {

    private MeterRegistry registry;
    private LlmTelemetryListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new LlmTelemetryListener(registry);
    }

    private static ChatRequest request(String modelName) {
        return ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .parameters(ChatRequestParameters.builder().modelName(modelName).build())
                .build();
    }

    private static ChatResponse response(Integer in, Integer out) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from("hello"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("gpt-4o-2024-11-20")
                        .tokenUsage(new TokenUsage(in, out))
                        .build())
                .build();
    }

    private Map<Object, Object> attributes() {
        return new ConcurrentHashMap<>();
    }

    @Test
    @DisplayName("a successful call records duration and both token counters")
    void successRecordsDurationAndTokens() {
        Map<Object, Object> attrs = attributes();
        ChatRequest req = request("gpt-4o");

        listener.onRequest(new ChatModelRequestContext(req, ModelProvider.OPEN_AI, attrs));
        listener.onResponse(new ChatModelResponseContext(response(11, 22), req, ModelProvider.OPEN_AI, attrs));

        var timer = registry.find("eddi.llm.request.duration")
                .tag("provider", "OPEN_AI").tag("model", "gpt-4o").tag("outcome", "success").timer();
        assertNotNull(timer, "the default single-model path must produce a latency sample");
        assertEquals(1, timer.count());

        assertEquals(11.0, registry.find("eddi.llm.tokens").tag("type", "input").counter().count());
        assertEquals(22.0, registry.find("eddi.llm.tokens").tag("type", "output").counter().count());
    }

    /**
     * The error path is the one an alert fires on, and it must record both a
     * duration sample tagged {@code error} and an error counter tagged with the
     * exception class. The class matters: it is how an operator distinguishes a
     * provider 429 from a timeout.
     */
    @Test
    @DisplayName("a failed call records an error-tagged duration and names the exception class")
    void errorRecordsDurationAndErrorClass() {
        Map<Object, Object> attrs = attributes();
        ChatRequest req = request("gpt-4o");

        listener.onRequest(new ChatModelRequestContext(req, ModelProvider.OPEN_AI, attrs));
        listener.onError(new ChatModelErrorContext(
                new IllegalStateException("boom"), req, ModelProvider.OPEN_AI, attrs));

        var timer = registry.find("eddi.llm.request.duration").tag("outcome", "error").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        var errors = registry.find("eddi.llm.request.errors").tag("error", "IllegalStateException").counter();
        assertNotNull(errors, "the exception class is what separates a rate limit from a timeout on a dashboard");
        assertEquals(1.0, errors.count());
    }

    /**
     * A provider that reports no token usage must not produce a zero-valued
     * counter: a zero is indistinguishable from "this model is free", and it would
     * drag any average down.
     */
    @Test
    @DisplayName("absent token usage records no token counter at all")
    void absentTokenUsageRecordsNothing() {
        Map<Object, Object> attrs = attributes();
        ChatRequest req = request("local-model");

        listener.onRequest(new ChatModelRequestContext(req, ModelProvider.OTHER, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("hi")).build(), req, ModelProvider.OTHER, attrs));

        assertNull(registry.find("eddi.llm.tokens").counter(),
                "a model that reports no usage must not be recorded as having used zero tokens");
        assertNotNull(registry.find("eddi.llm.request.duration").timer(), "but the latency is still worth having");
    }

    /**
     * {@code onResponse} without a preceding {@code onRequest} must not blow up. It
     * is reachable: a listener registered ahead of this one can throw out of
     * {@code onRequest}, and langchain4j carries on to the next listener.
     */
    @Test
    @DisplayName("a response with no recorded start is skipped rather than throwing")
    void responseWithoutStartIsSkipped() {
        ChatRequest req = request("gpt-4o");

        listener.onResponse(new ChatModelResponseContext(response(1, 2), req, ModelProvider.OPEN_AI, attributes()));

        assertNull(registry.find("eddi.llm.request.duration").timer(),
                "losing one sample is the right trade against an exception on the response path");
    }

    /**
     * Nulls arrive from real providers: several report no model name on the
     * response, and {@code modelProvider()} is null for a model that does not
     * declare one. Tags must never be null, or the registry rejects the meter.
     */
    @Test
    @DisplayName("missing provider and model degrade to 'unknown' rather than failing")
    void missingProviderAndModelDegradeToUnknown() {
        Map<Object, Object> attrs = attributes();
        ChatRequest bare = ChatRequest.builder().messages(UserMessage.from("hi")).build();

        listener.onRequest(new ChatModelRequestContext(bare, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("x")).build(), bare, null, attrs));

        var timer = registry.find("eddi.llm.request.duration")
                .tag("provider", "unknown").tag("model", "unknown").timer();
        assertNotNull(timer, "a null provider or model must become a tag value, not a dropped meter");
        assertEquals(1, timer.count());
    }

    /**
     * Telemetry must never fail a turn. langchain4j already contains a throwing
     * listener, but this asserts the listener does not throw in the first place
     * when the registry itself is hostile.
     */
    @Test
    @DisplayName("a failing meter registry does not propagate out of the callbacks")
    void failingRegistryDoesNotPropagate() {
        var hostile = new SimpleMeterRegistry() {
            @Override
            public Counter counter(String name, String... tags) {
                throw new IllegalStateException("registry is down");
            }
        };
        var hostileListener = new LlmTelemetryListener(hostile);
        Map<Object, Object> attrs = attributes();
        ChatRequest req = request("gpt-4o");

        hostileListener.onRequest(new ChatModelRequestContext(req, ModelProvider.OPEN_AI, attrs));
        // Must not throw.
        hostileListener.onResponse(new ChatModelResponseContext(response(1, 1), req, ModelProvider.OPEN_AI, attrs));
        hostileListener.onError(new ChatModelErrorContext(new RuntimeException("x"), req, ModelProvider.OPEN_AI, attrs));
    }

    /**
     * The schema version is what makes the next {@code gen_ai.*} rename a diff
     * against a stated baseline. Pinned so it cannot drift silently from the
     * revision the attribute names were actually taken from.
     */
    @Test
    @DisplayName("the recorded semconv revision is the one the attribute names came from")
    void semconvRevisionIsPinned() {
        assertEquals("1.37.0", LlmTelemetryListener.SEMCONV_SCHEMA_VERSION,
                "gen_ai.provider.name and gen_ai.usage.input_tokens are the v1.37.0 spellings;"
                        + " change this constant in the same commit that changes the attribute names");
    }

    @Test
    @DisplayName("repeated calls accumulate rather than replace")
    void repeatedCallsAccumulate() {
        ChatRequest req = request("gpt-4o");
        for (int i = 0; i < 3; i++) {
            Map<Object, Object> attrs = new HashMap<>();
            listener.onRequest(new ChatModelRequestContext(req, ModelProvider.OPEN_AI, attrs));
            listener.onResponse(new ChatModelResponseContext(response(5, 7), req, ModelProvider.OPEN_AI, attrs));
        }

        assertEquals(3, registry.find("eddi.llm.request.duration").timer().count());
        assertEquals(15.0, registry.find("eddi.llm.tokens").tag("type", "input").counter().count());
        assertTrue(registry.find("eddi.llm.tokens").tag("type", "output").counter().count() == 21.0);
    }
}
