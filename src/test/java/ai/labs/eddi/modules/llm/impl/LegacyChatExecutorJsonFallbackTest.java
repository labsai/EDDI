/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M-L5: the JSON-mode fallback fires only for a failure that could mean "this
 * provider does not support JSON mode" — never for a transient one, which it
 * used to double into a second paid request.
 */
@DisplayName("LegacyChatExecutor — JSON-mode fallback")
class LegacyChatExecutorJsonFallbackTest {

    private static final JsonResponseFormatPolicy JSON = JsonResponseFormatPolicy.of(true, "openai", null);

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(2);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("give me JSON"));
    }

    @Test
    @DisplayName("a rate limit that outlives its retries is rethrown, not re-sent without JSON mode")
    void transientFailureIsNotAFormatProblem() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("429 Too Many Requests"));

        assertThrows(LifecycleException.class, () -> new LegacyChatExecutor().execute(model, messages(), task(), JSON));

        verify(model, times(2)).chat(any(ChatRequest.class));
        verify(model, never()).chat(anyList());
    }

    @Test
    @DisplayName("a request the provider rejects still falls back to a plain request")
    void unsupportedFormatStillFallsBack() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalArgumentException("response_format json_object is not supported"));
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ok\":true}")).build());

        var result = new LegacyChatExecutor().execute(model, messages(), task(), JSON);

        assertEquals("{\"ok\":true}", result.response());
        verify(model, times(1)).chat(any(ChatRequest.class));
    }
}
