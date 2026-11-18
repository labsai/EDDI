/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * M3: a cancelled streamed call in JSON mode is a cancellation, not a provider
 * that does not support the JSON format — so no retry and no fallback request
 * without the format.
 */
@DisplayName("LegacyChatExecutor — a cancel in JSON mode sends no further request")
class LegacyChatExecutorCancelTest {

    private static final JsonResponseFormatPolicy JSON = JsonResponseFormatPolicy.of(true, "openai", null);

    @Test
    @DisplayName("cancel during a JSON-mode stream: one provider call, no retry, no non-JSON fallback")
    void cancelInJsonModeStopsEverything() {
        var calls = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        StreamingChatModel provider = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                handler.onPartialResponse("{\"answer\":");
                cancelled.set(true);
            }
        };
        var task = new LlmConfiguration.Task();
        task.setId("json");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(3);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);
        var policy = new StreamPolicy(30, 0, true, cancelled::get, null, false);
        var bridge = new ToolLoopStreamingChatModel(provider, mock(ConversationEventSink.class), policy, "openai", "legacy")
                .forwardingJsonRounds();

        assertThrows(LifecycleException.LifecycleInterruptedException.class, () -> new LegacyChatExecutor().execute(bridge,
                List.of(UserMessage.from("hi")), task, JSON, null, null, cancelled::get));

        assertEquals(1, calls.get(), "no retry and no request without the JSON format after the cancel");
    }
}
