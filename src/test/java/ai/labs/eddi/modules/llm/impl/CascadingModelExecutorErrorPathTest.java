/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
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
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Error- and timeout-path coverage for {@link CascadingModelExecutor}.
 * <p>
 * This class restores behaviour that lost its tests when
 * {@code CascadingModelExecutorExtendedTest} was deleted and
 * {@code CascadingModelExecutorCoverageTest} was rewritten: exception-cause
 * unwrapping, the per-step timeout paths with and without a {@code bestSoFar},
 * and the agent-mode opt-out. Written against the current implementation rather
 * than restored verbatim.
 */
@DisplayName("CascadingModelExecutor — error and timeout paths")
class CascadingModelExecutorErrorPathTest {

    // ==================== Per-step timeout ====================

    @Nested
    @DisplayName("per-step timeout")
    class StepTimeoutTests {

        @Test
        @DisplayName("last step times out with no bestSoFar — throws LifecycleException listing the errors")
        void lastStepTimesOutNoBest_throws() throws Exception {
            var cascade = enabledCascade(step("only", 1L));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            ChatModel slow = sleepingModel(500);
            when(registry.getOrCreate(eq("only"), anyMap())).thenReturn(slow);

            var executor = executor(registry);

            var ex = assertThrows(LifecycleException.class, () -> executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(),
                    mock(IConversationMemory.class), mock(AgentOrchestrator.class), Map.of(), false, false, false));

            assertTrue(ex.getMessage().contains("all steps exhausted"), "message should explain the cascade ran out of steps: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("timeout"), "message should name the timeout cause: " + ex.getMessage());
        }

        @Test
        @DisplayName("last step times out but bestSoFar exists — returns bestSoFar instead of failing")
        void lastStepTimesOutWithBest_returnsBest() throws Exception {
            var step0 = step("cheap", 30000L);
            step0.setConfidenceThreshold(0.9); // hedging answer escalates but is retained
            var cascade = enabledCascade(step0, step("expensive", 1L));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            ChatModel hedging = modelReturning("I'm not sure, I don't know.");
            ChatModel slow = sleepingModel(500);
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(hedging);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(slow);

            var executor = executor(registry);
            var result = executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), mock(IConversationMemory.class),
                    mock(AgentOrchestrator.class), Map.of(), false, false, false);

            assertEquals(0, result.stepUsed(), "the surviving earlier step must be returned");
            assertEquals("I'm not sure, I don't know.", result.response());
        }

        @Test
        @DisplayName("non-last step times out — fires onCascadeEscalation with reason 'timeout' and escalates")
        void timeoutEscalation_firesEventSink() throws Exception {
            var cascade = enabledCascade(step("cheap", 1L), step("expensive", 30000L));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            ChatModel slow = sleepingModel(500);
            ChatModel good = modelReturning("A confident, complete answer.");
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(slow);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(good);

            var sink = mock(ConversationEventSink.class);
            var executor = executor(registry);
            var result = executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                    mock(AgentOrchestrator.class), Map.of(), false, false, false);

            assertEquals(1, result.stepUsed(), "should have escalated past the timed-out step");
            verify(sink).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(), eq("timeout"), anyLong());
        }
    }

    // ==================== Exception cause unwrapping ====================

    @Nested
    @DisplayName("exception cause unwrapping")
    class ExceptionUnwrappingTests {

        @Test
        @DisplayName("step throws LifecycleException — escalates rather than aborting the cascade")
        void lifecycleExceptionCause_escalates() throws Exception {
            var cascade = enabledCascade(step("cheap", 30000L), step("expensive", 30000L));

            ChatModel failing = mock(ChatModel.class);
            when(failing.chat(anyList())).thenThrow(new RuntimeException(new LifecycleException("downstream lifecycle failure")));

            ChatModel good = modelReturning("A confident, complete answer.");
            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(failing);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(good);

            var executor = executor(registry);
            var result = executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), mock(IConversationMemory.class),
                    mock(AgentOrchestrator.class), Map.of(), false, false, false);

            assertEquals(1, result.stepUsed());
            assertTrue(result.trace().stream().anyMatch(t -> "error".equals(t.get("status")) || "retryable_error".equals(t.get("status"))),
                    "the failed step should be recorded in the trace with an error status");
        }

        @Test
        @DisplayName("step throws a plain RuntimeException — recorded as 'error' and escalates")
        void genericExceptionCause_escalates() throws Exception {
            var cascade = enabledCascade(step("cheap", 30000L), step("expensive", 30000L));

            ChatModel failing = mock(ChatModel.class);
            when(failing.chat(anyList())).thenThrow(new RuntimeException("provider exploded"));

            ChatModel good = modelReturning("A confident, complete answer.");
            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(failing);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(good);

            var executor = executor(registry);
            var result = executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(), mock(IConversationMemory.class),
                    mock(AgentOrchestrator.class), Map.of(), false, false, false);

            assertEquals(1, result.stepUsed());
            var failedStep = result.trace().stream().filter(t -> Integer.valueOf(0).equals(t.get("step"))).findFirst().orElseThrow();
            assertEquals("error", failedStep.get("status"));
            assertTrue(String.valueOf(failedStep.get("error")).contains("provider exploded"),
                    "the trace should preserve the provider's message for diagnosis");
        }

        @Test
        @DisplayName("every step fails — LifecycleException aggregates each step's error")
        void allStepsFail_aggregatesErrors() throws Exception {
            var cascade = enabledCascade(step("cheap", 30000L), step("expensive", 30000L));

            ChatModel failingCheap = mock(ChatModel.class);
            when(failingCheap.chat(anyList())).thenThrow(new RuntimeException("cheap exploded"));
            ChatModel failingExpensive = mock(ChatModel.class);
            when(failingExpensive.chat(anyList())).thenThrow(new RuntimeException("expensive exploded"));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(failingCheap);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(failingExpensive);

            var executor = executor(registry);

            var ex = assertThrows(LifecycleException.class, () -> executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task(),
                    mock(IConversationMemory.class), mock(AgentOrchestrator.class), Map.of(), false, false, false));

            // Both failures must survive into the message — diagnosing a fully failed
            // cascade from only the last provider's error is guesswork.
            assertTrue(ex.getMessage().contains("cheap exploded"), "should retain step 0's error: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("expensive exploded"), "should retain step 1's error: " + ex.getMessage());
        }
    }

    // ==================== Agent-mode opt-out ====================

    @Nested
    @DisplayName("agent-mode opt-out")
    class AgentModeOptOutTests {

        @Test
        @DisplayName("enableInAgentMode=false with an agent task — orchestrator is never consulted")
        void enableInAgentModeFalse_skipsOrchestrator() throws Exception {
            var cascade = enabledCascade(step("cheap", 30000L));
            cascade.setEnableInAgentMode(false);

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            ChatModel legacy = modelReturning("legacy answer");
            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(legacy);

            // isAgentMode() is derived — enabling built-in tools is what makes this an
            // agent task.
            var task = task();
            task.setEnableBuiltInTools(true);
            assertTrue(task.isAgentMode(), "precondition: the task must be in agent mode for this test to mean anything");

            var orchestrator = mock(AgentOrchestrator.class);
            var executor = executor(registry);
            var result = executor.execute(cascade, messages(), "sys", Map.of("apiKey", "k"), task, mock(IConversationMemory.class),
                    orchestrator, Map.of(), false, false, false);

            assertEquals("legacy answer", result.response());
            verify(orchestrator, never()).executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any());
        }
    }

    // ==================== Helpers ====================

    /**
     * Executor with a pass-through variable resolver and no templating engine or
     * metrics.
     */
    private static CascadingModelExecutor executor(ChatModelRegistry registry) {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null);
    }

    private static ModelCascadeConfig enabledCascade(CascadeStep... steps) {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(steps));
        return cascade;
    }

    private static CascadeStep step(String type, long timeoutMs) {
        var step = new CascadeStep();
        step.setType(type);
        step.setTimeoutMs(timeoutMs);
        return step;
    }

    private static IConversationMemory memory(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(sink);
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

    /**
     * A model that blocks longer than the step timeout, forcing a TimeoutException.
     */
    private static ChatModel sleepingModel(long sleepMs) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenAnswer(inv -> {
            Thread.sleep(sleepMs);
            return ChatResponse.builder().aiMessage(AiMessage.from("too late")).build();
        });
        return model;
    }
}
