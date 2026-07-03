/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.JudgeModelConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
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
 * Tests for the enterprise-hardening behaviors of
 * {@link CascadingModelExecutor}: judge model reachability, cost computation,
 * Micrometer metrics, returnBestAcrossSteps, and cost/token trace enrichment.
 */
@DisplayName("CascadingModelExecutor — Enterprise")
class CascadingModelExecutorEnterpriseTest {

    private static IConversationMemory memory() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(null);
        return memory;
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(1);
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

    private CascadingModelExecutor.CascadeResult run(ChatModelRegistry registry, ModelCascadeConfig cascade, MeterRegistry meterRegistry)
            throws LifecycleException {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(),
                meterRegistry);
        return executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(), mock(AgentOrchestrator.class), Map.of(), false,
                false, false);
    }

    // ─── judge_model reachability ────────────────────────────────────

    @Test
    @DisplayName("judge_model strategy — judge model rates confidence")
    void judgeModel_reachable() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("judge_model");
        var judge = new JudgeModelConfig();
        judge.setType("judgeProvider");
        cascade.setJudgeModel(judge);

        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        ChatModel stepModel = modelReturning("The capital of France is Paris.");
        ChatModel judgeModel = modelReturning("{\"confidence\": 0.93}");

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(stepModel);
        when(registry.getOrCreate(eq("judgeProvider"), anyMap())).thenReturn(judgeModel);

        var result = run(registry, cascade, null);

        assertEquals(0.93, result.confidence(), 0.001, "confidence should come from the judge model");
        verify(judgeModel).chat(anyList());
    }

    @Test
    @DisplayName("judge_model strategy but judge build fails — falls back to heuristic")
    void judgeModel_buildFails_fallsBackToHeuristic() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("judge_model");
        var judge = new JudgeModelConfig();
        judge.setType("brokenJudge");
        cascade.setJudgeModel(judge);

        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        ChatModel stepModel = modelReturning("A full and confident answer with plenty of detail here.");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(stepModel);
        when(registry.getOrCreate(eq("brokenJudge"), anyMap())).thenThrow(new ChatModelRegistry.UnsupportedLlmTaskException("no such judge"));

        var result = run(registry, cascade, null);
        // Heuristic on a decent-length answer with no red flags → default 0.8.
        assertEquals(0.8, result.confidence(), 0.001);
    }

    // ─── returnBestAcrossSteps ───────────────────────────────────────

    @Test
    @DisplayName("returnBestAcrossSteps=true — earlier higher-scoring step wins over accepted last step")
    void returnBestAcrossSteps_returnsEarlierBetter() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setReturnBestAcrossSteps(true);

        var step1 = new CascadeStep();
        step1.setType("cheap");
        step1.setConfidenceThreshold(0.99); // force escalation despite good answer

        var step2 = new CascadeStep();
        step2.setType("expensive"); // last — always accepted
        cascade.setSteps(List.of(step1, step2));

        // step1: confident (heuristic 0.8), step2: hedging (heuristic 0.4)
        ChatModel cheap = modelReturning("A thorough, confident, well-structured answer to the question.");
        ChatModel expensive = modelReturning("I'm not sure, I don't know.");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheap);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(expensive);

        var result = run(registry, cascade, null);
        assertEquals(0, result.stepUsed(), "earlier step (higher confidence) should be returned");
    }

    @Test
    @DisplayName("returnBestAcrossSteps=false (default) — last accepted step wins")
    void returnBestAcrossSteps_defaultLastWins() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");

        var step1 = new CascadeStep();
        step1.setType("cheap");
        step1.setConfidenceThreshold(0.99);

        var step2 = new CascadeStep();
        step2.setType("expensive");
        cascade.setSteps(List.of(step1, step2));

        ChatModel cheap = modelReturning("A thorough, confident, well-structured answer to the question.");
        ChatModel expensive = modelReturning("I'm not sure, I don't know.");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheap);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(expensive);

        var result = run(registry, cascade, null);
        assertEquals(1, result.stepUsed(), "last step should be accepted by default");
    }

    // ─── Metrics ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Micrometer metrics — executions and accepted-step counters recorded")
    void metrics_recorded() throws Exception {
        var meterRegistry = new SimpleMeterRegistry();

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        ChatModel model = modelReturning("ok");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);

        run(registry, cascade, meterRegistry);

        assertEquals(1.0, meterRegistry.find("eddi.llm.cascade.executions").counter().count(), 0.001);
        assertEquals(1.0, meterRegistry.find("eddi.llm.cascade.accepted.step").tag("step", "0").counter().count(), 0.001);
    }

    // ─── Trace enrichment ────────────────────────────────────────────

    @Test
    @DisplayName("trace includes costUsd (0 when no pricing) and status")
    void trace_includesCostAndStatus() throws Exception {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        cascade.setSteps(List.of(step));

        ChatModel model = modelReturning("answer");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);

        var result = run(registry, cascade, null);
        var entry = result.trace().get(0);
        assertTrue(entry.containsKey("costUsd"), "trace should carry per-step costUsd");
        assertEquals(0.0, ((Number) entry.get("costUsd")).doubleValue(), 0.0001);
        assertEquals("accepted", entry.get("status"));
        assertEquals(0.0, result.runCostUsd(), 0.0001);
    }

    // ─── computeCost ─────────────────────────────────────────────────

    @Test
    @DisplayName("computeCost — step price applied to token usage")
    void computeCost_appliesPricing() {
        var step = new CascadeStep();
        step.setInputPricePer1M(1.0);
        step.setOutputPricePer1M(2.0);
        var cascade = new ModelCascadeConfig();

        double cost = CascadingModelExecutor.computeCost(step, cascade, Map.of("inputTokens", 1_000_000, "outputTokens", 500_000));
        // 1M input * $1/1M + 0.5M output * $2/1M = 1.0 + 1.0 = 2.0
        assertEquals(2.0, cost, 0.0001);
    }

    @Test
    @DisplayName("computeCost — cascade default used when step has no price")
    void computeCost_cascadeDefault() {
        var step = new CascadeStep();
        var cascade = new ModelCascadeConfig();
        cascade.setInputPricePer1M(3.0);
        cascade.setOutputPricePer1M(0.0);

        double cost = CascadingModelExecutor.computeCost(step, cascade, Map.of("inputTokens", 2_000_000, "outputTokens", 1_000_000));
        assertEquals(6.0, cost, 0.0001);
    }

    @Test
    @DisplayName("computeCost — zero when no pricing or no tokens")
    void computeCost_zeroWithoutPricingOrTokens() {
        var step = new CascadeStep();
        var cascade = new ModelCascadeConfig();
        assertEquals(0.0, CascadingModelExecutor.computeCost(step, cascade, Map.of("inputTokens", 100, "outputTokens", 100)), 0.0001);

        step.setInputPricePer1M(5.0);
        assertEquals(0.0, CascadingModelExecutor.computeCost(step, cascade, null), 0.0001);
    }

    @Test
    @DisplayName("computeCost — only input price set")
    void computeCost_onlyInputPrice() {
        var step = new CascadeStep();
        step.setInputPricePer1M(4.0);
        assertEquals(4.0, CascadingModelExecutor.computeCost(step, new ModelCascadeConfig(), Map.of("inputTokens", 1_000_000, "outputTokens", 999)),
                0.0001);
    }

    @Test
    @DisplayName("computeCost — only output price set")
    void computeCost_onlyOutputPrice() {
        var step = new CascadeStep();
        step.setOutputPricePer1M(4.0);
        assertEquals(4.0, CascadingModelExecutor.computeCost(step, new ModelCascadeConfig(), Map.of("inputTokens", 999, "outputTokens", 1_000_000)),
                0.0001);
    }
}
