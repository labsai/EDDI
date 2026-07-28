/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Finding F8 — a provider that never calls {@code onPartialResponse} produced a
 * SILENT EMPTY answer: the accumulated partial buffer was the sole text source,
 * and {@code onCompleteResponse}'s {@code aiMessage().text()} was never read.
 * No error, warning or metric was raised.
 */
@DisplayName("StreamingLegacyChatExecutor — provider without partial responses (F8)")
class StreamingLegacyChatExecutorNoPartialsTest {

    private MeterRegistry meterRegistry;
    private StreamingLegacyChatExecutor executor;
    private ConversationEventSink eventSink;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        executor = new StreamingLegacyChatExecutor(meterRegistry);
        eventSink = mock(ConversationEventSink.class);
    }

    private static StreamingChatModel completeOnly(String text) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                // No onPartialResponse at all — the shape of a provider whose binding
                // does not support incremental streaming.
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            }
        };
    }

    @Test
    @DisplayName("the complete response is used when no partials arrived")
    void fallsBackToCompleteResponse() {
        var result = executor.execute(completeOnly("The full answer."), List.of(UserMessage.from("Hi")),
                eventSink, null, JsonResponseFormatPolicy.DISABLED);

        assertEquals("The full answer.", result.response(),
                "an answer that only arrived via onCompleteResponse must not be dropped");
    }

    @Test
    @DisplayName("the fallback text still reaches the SSE client")
    void emitsFallbackToken() {
        executor.execute(completeOnly("The full answer."), List.of(UserMessage.from("Hi")),
                eventSink, null, JsonResponseFormatPolicy.DISABLED);

        verify(eventSink).onToken("The full answer.");
    }

    @Test
    @DisplayName("the degradation is observable: metadata warning plus a metric")
    void recordsWarningAndMetric() {
        var result = executor.execute(completeOnly("The full answer."), List.of(UserMessage.from("Hi")),
                eventSink, null, JsonResponseFormatPolicy.DISABLED);

        assertEquals(Boolean.TRUE, result.metadata().get("streamingNoPartials"));
        assertEquals("streaming_no_partials", result.metadata().get("warning"));
        assertTrue(meterRegistry.find("eddi.llm.streaming.no_partials").counter().count() >= 1.0,
                "a silent downgrade must be counted");
    }

    @Test
    @DisplayName("a genuinely empty completion stays empty and raises no false alarm")
    void trulyEmptyResponseIsNotFlagged() {
        var result = executor.execute(completeOnly(""), List.of(UserMessage.from("Hi")),
                eventSink, null, JsonResponseFormatPolicy.DISABLED);

        assertEquals("", result.response());
        assertNull(result.metadata().get("streamingNoPartials"));
    }

    @Test
    @DisplayName("a normal streaming provider is unaffected — no fallback, no warning")
    void normalStreamingIsUnaffected() {
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hel");
                handler.onPartialResponse("lo");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build());
            }
        };

        var result = executor.execute(model, List.of(UserMessage.from("Hi")), eventSink, null, JsonResponseFormatPolicy.DISABLED);

        assertEquals("Hello", result.response());
        assertNull(result.metadata().get("streamingNoPartials"));
        verify(eventSink).onToken("Hel");
        verify(eventSink).onToken("lo");
    }

    @Test
    @DisplayName("completeResponseText reads the value that used to be discarded")
    void completeResponseTextReadsAiMessage() {
        assertEquals("x", StreamingLegacyChatExecutor.completeResponseText(
                ChatResponse.builder().aiMessage(AiMessage.from("x")).build()));
        assertNull(StreamingLegacyChatExecutor.completeResponseText(null));
    }
}
