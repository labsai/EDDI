/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage-focused tests for the newer {@link CascadingModelExecutor} paths:
 * agent mode, live streaming of the final step, cost/duration ceilings, timeout
 * escalation, and the convertToObject/jsonMode downgrade.
 */
@DisplayName("CascadingModelExecutor — Coverage")
class CascadingModelExecutorCoverageTest {

    private static IConversationMemory memory(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(sink);
        return memory;
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> messages() {
        var m = new ArrayList<ChatMessage>();
        m.add(SystemMessage.from("sys"));
        m.add(UserMessage.from("hi"));
        return m;
    }

    private static ChatModel modelReturning(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
        return model;
    }

    private static ChatModel modelWithTokens(String text, int in, int out) {
        ChatModel model = mock(ChatModel.class);
        ChatResponseMetadata md = mock(ChatResponseMetadata.class);
        when(md.finishReason()).thenReturn(null);
        when(md.tokenUsage()).thenReturn(new TokenUsage(in, out, in + out));
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.aiMessage()).thenReturn(AiMessage.from(text));
        when(resp.metadata()).thenReturn(md);
        when(model.chat(anyList())).thenReturn(resp);
        return model;
    }

    private CascadingModelExecutor executor(ChatModelRegistry registry, MeterRegistry mr) {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), mr);
    }

    // ─── Agent mode ──────────────────────────────────────────────────

    @Test
    @DisplayName("agent mode — uses orchestrator result and propagates agentResult")
    void agentMode_usesOrchestrator() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        var task = task();
        task.setEnableBuiltInTools(true); // → isAgentMode() == true

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(mock(ChatModel.class));

        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent answer", List.of(Map.of("type", "tool_call"))));

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task, memory(null), orchestrator,
                Map.of(), false, false, false);

        assertEquals("agent answer", result.response());
        assertNotNull(result.agentResult());
    }

    @Test
    @DisplayName("agent mode + structured_output — downgraded (no wrapper), evaluated heuristically")
    void agentMode_structuredOutputDowngraded() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("structured_output");
        var step1 = new CascadeStep();
        step1.setType("openai");
        step1.setConfidenceThreshold(0.9);
        var step2 = new CascadeStep();
        step2.setType("openai");
        cascade.setSteps(List.of(step1, step2));

        var task = task();
        task.setEnableBuiltInTools(true);

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(mock(ChatModel.class));

        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        // Hedging → heuristic ~0.4 < 0.9 → escalate to step 2 (last, accepted).
        when(orchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("I'm not sure, I don't know.", List.of()));

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task, memory(null), orchestrator,
                Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
    }

    @Test
    @DisplayName("agent mode with no tools active — falls back to legacy execution")
    void agentMode_noToolsFallsBackToLegacy() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        var task = task();
        task.setEnableBuiltInTools(true);

        ChatModel model = modelReturning("legacy answer");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);

        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt())).thenReturn(null); // no tools

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task, memory(null), orchestrator,
                Map.of(), false, false, false);

        assertEquals("legacy answer", result.response());
        assertNull(result.agentResult());
    }

    @Test
    @DisplayName("agent-mode step throws ToolApprovalRequiredException — rethrown, never demoted to escalation")
    void agentMode_toolApprovalRequired_rethrown() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(true);
        cascade.setEvaluationStrategy("none");
        // Two steps: a pause on the FIRST must NOT be swallowed into escalation.
        var step1 = new CascadeStep();
        step1.setType("openai");
        step1.setConfidenceThreshold(0.5);
        var step2 = new CascadeStep();
        step2.setType("anthropic");
        cascade.setSteps(List.of(step1, step2));

        var task = task();
        task.setEnableBuiltInTools(true); // → isAgentMode() == true

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(mock(ChatModel.class));

        var pause = new ToolApprovalRequiredException("needs human approval", null);
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        doThrow(pause).when(orchestrator)
                .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());

        var thrown = assertThrows(ToolApprovalRequiredException.class,
                () -> executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task, memory(null),
                        orchestrator, Map.of(), false, false, false));

        assertSame(pause, thrown, "the exact pause signal must propagate unchanged (not demoted to a failed step)");
    }

    // ─── Live streaming of the final step ────────────────────────────

    @Test
    @DisplayName("final step streamed live — tokens emitted, streamedLive=true, no double emit")
    void streaming_finalStepLive() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        var sink = mock(ConversationEventSink.class);

        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("Hello ");
            h.onPartialResponse("world");
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello world")).build());
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(mock(ChatModel.class));
        when(registry.getOrCreateStreaming(anyString(), anyMap())).thenReturn(streaming);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, /* allowLiveStreaming */ true);

        assertEquals("Hello world", result.response());
        assertTrue(result.streamedLive(), "final step should be streamed live");
        verify(sink, times(2)).onToken(anyString());
    }

    @Test
    @DisplayName("returnBestAcrossSteps does NOT supersede a live-streamed final step (avoids stream mismatch)")
    void returnBestAcrossSteps_keepsStreamedFinalStep() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setReturnBestAcrossSteps(true);

        var step1 = new CascadeStep();
        step1.setType("cheap");
        step1.setConfidenceThreshold(0.99); // confident (0.8) but forced to escalate
        var step2 = new CascadeStep();
        step2.setType("expensive"); // last — streamed live
        cascade.setSteps(List.of(step1, step2));

        var sink = mock(ConversationEventSink.class);

        ChatModel cheap = modelReturning("A thorough, confident, well-structured answer to the question.");
        StreamingChatModel expensiveStream = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse("I'm not sure, I don't know."); // hedging → heuristic ~0.4
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("I'm not sure, I don't know.")).build());
            return null;
        }).when(expensiveStream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheap);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(mock(ChatModel.class));
        when(registry.getOrCreateStreaming(eq("expensive"), anyMap())).thenReturn(expensiveStream);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, /* allowLiveStreaming */ true);

        assertEquals(1, result.stepUsed(), "the streamed final step must be kept, not superseded by the earlier better step");
        assertTrue(result.streamedLive());
    }

    @Test
    @DisplayName("live-streamed step is NOT cancelled by a tiny per-step timeout (runs to completion)")
    void streaming_ignoresPerStepTimeout() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5L); // tiny — would cancel a buffered call, must NOT cancel the stream
        cascade.setSteps(List.of(step));

        var sink = mock(ConversationEventSink.class);
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            Thread.sleep(120); // slower than the 5ms per-step timeout
            h.onPartialResponse("streamed answer");
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("streamed answer")).build());
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(mock(ChatModel.class));
        when(registry.getOrCreateStreaming(anyString(), anyMap())).thenReturn(streaming);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, /* allowLiveStreaming */ true);

        assertEquals("streamed answer", result.response(), "the tiny per-step timeout must not cancel the live stream");
        assertTrue(result.streamedLive());
    }

    @Test
    @DisplayName("streaming allowed but provider has no streaming model — falls back to buffered")
    void streaming_noStreamingModel_buffered() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        var sink = mock(ConversationEventSink.class);
        ChatModel model = modelReturning("buffered answer");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        when(registry.getOrCreateStreaming(anyString(), anyMap())).thenReturn(null); // no streaming support

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, true);

        assertEquals("buffered answer", result.response());
        assertFalse(result.streamedLive());
    }

    // ─── Cost ceiling ────────────────────────────────────────────────

    @Test
    @DisplayName("cost ceiling — stops escalating and returns best; metric recorded")
    void costCeiling_stopsEscalation() throws Exception {
        var meter = new SimpleMeterRegistry();

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setMaxCostPerRun(1.0);

        var step1 = new CascadeStep();
        step1.setType("openai");
        step1.setConfidenceThreshold(0.99); // force escalation
        step1.setInputPricePer1M(1.0);
        step1.setOutputPricePer1M(1.0);

        var step2 = new CascadeStep();
        step2.setType("anthropic");
        cascade.setSteps(List.of(step1, step2));

        // step0: 1M+1M tokens @ $1/1M each = $2 > $1 ceiling.
        ChatModel model0 = modelWithTokens("A confident and complete first answer to the question.", 1_000_000, 1_000_000);
        ChatModel model1 = modelReturning("second");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(model0);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(model1);

        var result = executor(registry, meter).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(0, result.stepUsed(), "cost ceiling should stop before step 2");
        assertEquals(2.0, result.runCostUsd(), 0.001);
        assertEquals(1.0, meter.find("eddi.llm.cascade.ceiling.exceeded").tag("kind", "cost").counter().count(), 0.001);
        verify(model1, never()).chat(anyList());
    }

    // ─── Timeout escalation + duration ceiling ───────────────────────

    @Test
    @DisplayName("step timeout — escalates to next step")
    void timeout_escalates() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");

        var step1 = new CascadeStep();
        step1.setType("slow");
        step1.setConfidenceThreshold(0.5);
        step1.setTimeoutMs(30L);

        var step2 = new CascadeStep();
        step2.setType("fast");
        step2.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step1, step2));

        ChatModel slow = mock(ChatModel.class);
        when(slow.chat(anyList())).thenAnswer(inv -> {
            Thread.sleep(500);
            return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
        });
        ChatModel fast = modelReturning("on time");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("slow"), anyMap())).thenReturn(slow);
        when(registry.getOrCreate(eq("fast"), anyMap())).thenReturn(fast);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(),
                memory(mock(ConversationEventSink.class)),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
        assertEquals("on time", result.response());
        assertEquals("timeout", result.trace().get(0).get("status"));
    }

    @Test
    @DisplayName("duration ceiling — a slow first step exhausts the budget, best is returned")
    void durationCeiling_returnsBest() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setMaxTotalDurationMs(60L);

        var step1 = new CascadeStep();
        step1.setType("a");
        step1.setConfidenceThreshold(0.99); // escalate
        step1.setTimeoutMs(5000L);

        var step2 = new CascadeStep();
        step2.setType("b");
        step2.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step1, step2));

        // step0 completes but takes ~40ms; step1's remaining budget is tiny → its
        // model sleeps and times out, so the best (step0) is returned either via the
        // duration ceiling or the last-step timeout fallback → stepUsed stays 0.
        ChatModel a = mock(ChatModel.class);
        when(a.chat(anyList())).thenAnswer(inv -> {
            Thread.sleep(40);
            return ChatResponse.builder().aiMessage(AiMessage.from("A decent first answer with enough length to score well.")).build();
        });
        ChatModel b = mock(ChatModel.class);
        when(b.chat(anyList())).thenAnswer(inv -> {
            Thread.sleep(500);
            return ChatResponse.builder().aiMessage(AiMessage.from("late")).build();
        });
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("a"), anyMap())).thenReturn(a);
        when(registry.getOrCreate(eq("b"), anyMap())).thenReturn(b);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(0, result.stepUsed(), "best (step 0) is returned once the duration budget is spent");
    }

    // ─── convertToObject downgrade + jsonMode ────────────────────────

    @Test
    @DisplayName("convertToObject=true — structured_output downgraded, jsonMode honored")
    void convertToObject_downgradesAndUsesJsonMode() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("structured_output");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        // Stub both the JSON-mode (ChatRequest) and plain (List) calls.
        ChatModel model = mock(ChatModel.class);
        var resp = ChatResponse.builder().aiMessage(AiMessage.from("{\"answer\":\"ok\"}")).build();
        when(model.chat(any(ChatRequest.class))).thenReturn(resp);
        when(model.chat(anyList())).thenReturn(resp);
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), /* jsonMode */ true, /* convertToObject */ true, false);

        // Response is NOT unwrapped by the confidence wrapper (structured_output was
        // downgraded).
        assertEquals("{\"answer\":\"ok\"}", result.response());
        verify(model).chat(any(ChatRequest.class));
    }

    // ─── unknown strategy runtime path ───────────────────────────────

    @Test
    @DisplayName("strategy 'parallel' at runtime — runs sequentially without error")
    void parallelStrategy_runsSequentially() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setStrategy("parallel");
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        ChatModel model = modelReturning("ok");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);
        assertEquals("ok", result.response());
    }

    @Test
    @DisplayName("step error (not last) with event sink — 'error' escalation event emitted")
    void error_escalatesWithSink() throws Exception {
        var sink = mock(ConversationEventSink.class);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");

        var step1 = new CascadeStep();
        step1.setType("bad");
        step1.setConfidenceThreshold(0.5);
        step1.setTimeoutMs(5000L);
        var step2 = new CascadeStep();
        step2.setType("good");
        step2.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step1, step2));

        ChatModel bad = mock(ChatModel.class);
        when(bad.chat(anyList())).thenThrow(new RuntimeException("boom")); // non-retryable → 'error'
        ChatModel good = modelReturning("recovered");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("bad"), anyMap())).thenReturn(bad);
        when(registry.getOrCreate(eq("good"), anyMap())).thenReturn(good);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
        assertEquals("recovered", result.response());
        verify(sink).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(), eq("error"), anyLong());
    }

    @Test
    @DisplayName("retryable error (rate limit) not last — classified 'retryable_error' and escalates")
    void retryableError_escalates() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");

        var step1 = new CascadeStep();
        step1.setType("limited");
        step1.setConfidenceThreshold(0.5);
        step1.setTimeoutMs(5000L);
        var step2 = new CascadeStep();
        step2.setType("ok");
        step2.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step1, step2));

        ChatModel limited = mock(ChatModel.class);
        when(limited.chat(anyList())).thenThrow(new RuntimeException("429 rate limit exceeded"));
        ChatModel ok = modelReturning("recovered");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("limited"), anyMap())).thenReturn(limited);
        when(registry.getOrCreate(eq("ok"), anyMap())).thenReturn(ok);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
        assertEquals("retryable_error", result.trace().get(0).get("status"));
    }

    @Test
    @DisplayName("typed transient exception (ConnectException) not last — classified retryable and escalates")
    void typedRetryableError_escalates() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");

        var step1 = new CascadeStep();
        step1.setType("down");
        step1.setConfidenceThreshold(0.5);
        step1.setTimeoutMs(5000L);
        var step2 = new CascadeStep();
        step2.setType("up");
        step2.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step1, step2));

        ChatModel down = mock(ChatModel.class);
        // Wrap the checked ConnectException as a cause (message itself does not match).
        when(down.chat(anyList())).thenThrow(new RuntimeException("network fault", new java.net.ConnectException("refused")));
        ChatModel up = modelReturning("recovered");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("down"), anyMap())).thenReturn(down);
        when(registry.getOrCreate(eq("up"), anyMap())).thenReturn(up);

        var result = executor(registry, null).execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(null),
                mock(AgentOrchestrator.class), Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
        assertEquals("retryable_error", result.trace().get(0).get("status"));
    }
}
