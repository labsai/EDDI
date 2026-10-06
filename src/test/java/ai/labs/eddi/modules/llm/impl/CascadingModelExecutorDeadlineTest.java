/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.shared.MutableTestClock;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The cascade inside a turn deadline, and the request-timeout clamp that makes
 * the provider call — not only the future waiting on it — end with the step.
 */
@DisplayName("CascadingModelExecutor — turn deadline and timeout clamp")
class CascadingModelExecutorDeadlineTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SimpleMeterRegistry globalMeters = new SimpleMeterRegistry();

    @BeforeEach
    void attachGlobal() {
        Metrics.addRegistry(globalMeters);
    }

    @AfterEach
    void detachGlobal() {
        Metrics.removeRegistry(globalMeters);
    }

    private static IConversationMemory memory(TurnDeadline deadline) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getTurnDeadline()).thenReturn(deadline);
        return memory;
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        return task;
    }

    private static ChatModel modelAnswering(Callable<String> answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenAnswer(inv -> ChatResponse.builder().aiMessage(AiMessage.from(answer.call())).build());
        return model;
    }

    private static CascadeStep step(String type, double threshold, long timeoutMs) {
        var step = new CascadeStep();
        step.setType(type);
        step.setConfidenceThreshold(threshold);
        step.setTimeoutMs(timeoutMs);
        return step;
    }

    private CascadingModelExecutor.CascadeResult run(ChatModelRegistry registry, ModelCascadeConfig cascade, IConversationMemory memory,
                                                     Map<String, String> baseParams)
            throws LifecycleException {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), meters,
                new CallerIdentityContext(null, null));
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("hi")));
        return executor.execute(cascade, messages, "sys", baseParams, task(), memory, mock(AgentOrchestrator.class), Map.of(), false, false,
                false);
    }

    private static ModelCascadeConfig twoStepCascade() {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(step("openai", 0.5, 30_000), step("gemini", 0.5, 30_000)));
        return cascade;
    }

    // ─── turn deadline ─────────────────────────────────────────────

    @Test
    @DisplayName("time spent in step 0 shrinks the cascade: no step 1 when too little of the turn is left")
    void budgetSpentByEarlierStepStopsTheCascade() throws Exception {
        var clock = new MutableTestClock();
        var deadline = TurnDeadline.of(clock, clock.millis(), 10_000, 1_500L);
        // Step 0 answers with something the heuristic rejects, and "takes" 6 s doing
        // so.
        ChatModel first = modelAnswering(() -> {
            clock.advance(6_000);
            return "";
        });
        ChatModel second = modelAnswering(() -> "a fine and detailed answer from the second model");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("gemini"), anyMap())).thenReturn(second);

        var result = run(registry, twoStepCascade(), memory(deadline), Map.of("apiKey", "k"));

        verify(registry, never()).getOrCreate(eq("gemini"), anyMap());
        verify(second, never()).chat(anyList());
        assertEquals(0, result.stepUsed(), "the best answer so far is served instead of starting a step that cannot finish");
        assertEquals(1.0, meters.counter("eddi.llm.turn.deadline.exceeded", "stage", "cascade").count(), "the stop is counted");
    }

    @Test
    @DisplayName("with enough budget the cascade still escalates")
    void enoughBudgetStillEscalates() throws Exception {
        var clock = new MutableTestClock();
        var deadline = TurnDeadline.of(clock, clock.millis(), 60_000, 1_500L);
        ChatModel first = modelAnswering(() -> "");
        ChatModel second = modelAnswering(() -> "a fine and detailed answer from the second model");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("gemini"), anyMap())).thenReturn(second);

        var result = run(registry, twoStepCascade(), memory(deadline), Map.of("apiKey", "k"));

        assertEquals(1, result.stepUsed());
    }

    @Test
    @DisplayName("a step is cut at the remaining budget even when its own timeoutMs is longer; the cancellation is counted")
    void stepTimeoutIsClampedToTheRemainingBudget() throws Exception {
        // Real time matters here: 1 s of budget after the reserve, a 30 s step timeout,
        // a model that hangs.
        var realClock = Clock.systemUTC();
        var deadline = TurnDeadline.of(realClock, realClock.millis(), 2_000, 1_000L);
        ChatModel hanging = modelAnswering(() -> {
            Thread.sleep(30_000);
            return "late";
        });
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(hanging);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step("openai", 0.5, 30_000)));
        long start = System.nanoTime();

        assertThrows(LifecycleException.class, () -> run(registry, cascade, memory(deadline), Map.of("apiKey", "k")));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000, "the step must end near the 1 s budget, not the 30 s step timeout; took " + elapsedMs + "ms");
        // Either layer may fire first at the same budget: the attempt's own bound
        // (global
        // registry) or the step future (this executor's registry). One must have
        // cancelled the hang.
        double cancelled = meters.counter("eddi.llm.cancelled", "scope", "cascade_step").count()
                + globalMeters.counter("eddi.llm.cancelled", "scope", "attempt").count();
        assertTrue(cancelled >= 1.0, "the hanging call must be cancelled, was " + cancelled);
    }

    @Test
    @DisplayName("with no deadline on the memory the cascade behaves as before")
    void noDeadlineNoChange() throws Exception {
        ChatModel first = modelAnswering(() -> "");
        ChatModel second = modelAnswering(() -> "a fine and detailed answer from the second model");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("gemini"), anyMap())).thenReturn(second);

        var result = run(registry, twoStepCascade(), memory(null), Map.of("apiKey", "k"));

        assertEquals(1, result.stepUsed());
        assertEquals(0.0, meters.counter("eddi.llm.turn.deadline.exceeded", "stage", "cascade").count());
    }

    // ─── request timeout clamp ─────────────────────────────────────

    @Test
    @DisplayName("a model timeout longer than the step's is clamped before the model is built")
    void modelTimeoutIsClampedToTheStep() throws Exception {
        ChatModel model = modelAnswering(() -> "a fine and detailed answer from the model");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(model);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step("openai", 0.0, 25_000)));
        Map<String, String> base = new HashMap<>(Map.of("apiKey", "k", "timeout", "120000"));

        run(registry, cascade, memory(null), base);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(registry).getOrCreate(eq("openai"), params.capture());
        assertEquals("25000", params.getValue().get("timeout"));
        assertEquals("120000", base.get("timeout"), "the task's own map is not modified");
    }

    @Test
    @DisplayName("a model timeout already within the step is left alone, so its cache entry is shared")
    void shorterModelTimeoutIsUntouched() throws Exception {
        ChatModel model = modelAnswering(() -> "a fine and detailed answer from the model");
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(model);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step("openai", 0.0, 25_000)));

        run(registry, cascade, memory(null), new HashMap<>(Map.of("apiKey", "k", "timeout", "20000")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(registry).getOrCreate(eq("openai"), params.capture());
        assertEquals("20000", params.getValue().get("timeout"));
    }

    @Test
    @DisplayName("clampModelTimeout: only changes what exceeds the step, and says so in the trace")
    void clampHelper() {
        Map<String, Object> trace = new HashMap<>();
        var longer = CascadingModelExecutor.clampModelTimeout(Map.of("timeout", "120000", "apiKey", "k"), 25_000, trace);
        assertEquals("25000", longer.get("timeout"));
        assertEquals("k", longer.get("apiKey"));
        assertEquals(25_000L, trace.get("modelTimeoutClampedMs"));

        Map<String, String> within = Map.of("timeout", "10000");
        assertSame(within, CascadingModelExecutor.clampModelTimeout(within, 25_000, null));

        assertEquals("25000", CascadingModelExecutor.clampModelTimeout(Map.of("apiKey", "k"), 25_000, null).get("timeout"),
                "no timeout at all gets the step's");
        assertEquals("25000", CascadingModelExecutor.clampModelTimeout(Map.of("timeout", "soon"), 25_000, null).get("timeout"));
    }

    @Test
    @DisplayName("clamped timeouts round up into coarse buckets so a shrinking deadline cannot mint a model per millisecond")
    void bucketsAreCoarse() {
        assertEquals(1_000, CascadingModelExecutor.timeoutBucketMs(1));
        assertEquals(4_000, CascadingModelExecutor.timeoutBucketMs(3_001));
        assertEquals(10_000, CascadingModelExecutor.timeoutBucketMs(10_000));
        assertEquals(15_000, CascadingModelExecutor.timeoutBucketMs(10_001));
        assertEquals(25_000, CascadingModelExecutor.timeoutBucketMs(24_100));
        assertEquals(CascadingModelExecutor.timeoutBucketMs(24_100), CascadingModelExecutor.timeoutBucketMs(21_000));
    }
}
