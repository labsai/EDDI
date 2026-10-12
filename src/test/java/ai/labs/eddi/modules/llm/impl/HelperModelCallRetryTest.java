/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The provider clients are built with {@code maxRetries(0)}, so every caller of
 * a model needs exactly one retry owner. These helper callers have no task
 * {@code retry} block and call the model directly; each must retry one
 * transient failure on its own.
 */
@DisplayName("Helper model calls retry a transient failure once")
class HelperModelCallRetryTest {

    private static ChatResponse reply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    @Test
    @DisplayName("SummarizationService")
    void summarizationService() throws Exception {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenThrow(new InternalServerException("upstream")).thenReturn(reply("the summary"));
        var service = new SummarizationService(registry, new SimpleMeterRegistry());
        service.initMetrics();

        assertEquals("the summary", service.summarize("text", "instructions", "anthropic", "claude-x"));

        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("SummarizationService does not retry a permanent failure and rethrows it unwrapped")
    void summarizationServicePermanentFailure() throws Exception {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenThrow(new AuthenticationException("bad key"));
        var service = new SummarizationService(registry, new SimpleMeterRegistry());
        service.initMetrics();

        assertThrows(AuthenticationException.class, () -> service.summarizeWithUsage("text", "instructions", "anthropic", "claude-x", null));

        verify(model, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("ToolResponseTruncator summarize strategy")
    void toolResponseTruncator() throws Exception {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        ChatModel model = mock(ChatModel.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenThrow(new InternalServerException("upstream")).thenReturn(reply("short summary"));
        var truncator = new ToolResponseTruncator(new SimpleMeterRegistry(), registry);
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(100);
        limits.setTruncationStrategy("summarize");
        limits.setSummarizerModel("gpt-4o-mini");

        String result = truncator.truncateIfNeeded("webScraper", "A".repeat(500), limits, "openai", new HashMap<>(Map.of("apiKey", "k")));

        assertTrue(result.contains("short summary"), result);
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("cascade judge model: a provider wait above the judge's short cap is not slept; falls back to the heuristic")
    void judgeModelRespectsItsShortBudget() {
        ChatModel judge = mock(ChatModel.class);
        String body = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"30s\"}]}}";
        when(judge.chat(anyList())).thenThrow(new RateLimitException(new HttpException(429, body)));
        long start = System.nanoTime();

        var result = ConfidenceEvaluator.evaluateWithJudge("A confident, complete answer with plenty of detail.", judge, null);

        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "must not sleep the requested 30s");
        verify(judge, times(1)).chat(anyList());
        assertTrue(result.confidence() >= 0.0);
    }

    @Test
    @DisplayName("cascade judge model")
    void judgeModel() {
        ChatModel judge = mock(ChatModel.class);
        when(judge.chat(anyList())).thenThrow(new InternalServerException("upstream")).thenReturn(reply("{\"confidence\": 0.9}"));

        var result = ConfidenceEvaluator.evaluateWithJudge("some answer", judge, null);

        assertEquals(0.9, result.confidence(), 0.001);
        verify(judge, times(2)).chat(anyList());
    }
}
