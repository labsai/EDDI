/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.*;
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
 * Additional branch-coverage tests for {@link CascadingModelExecutor},
 * targeting paths not exercised by {@link CascadingModelExecutorTest}:
 * <ul>
 * <li>step timeout → {@code future.cancel}, escalation, and last-step timeout
 * with and without a {@code bestSoFar} fallback</li>
 * <li>{@code ExecutionException} unwrapping — {@code LifecycleException} cause
 * vs. generic {@code Exception} cause</li>
 * <li>agent-mode step: orchestrator returns a result, and returns null → legacy
 * fallback</li>
 * <li>{@code enableInAgentMode} false → legacy mode even for an agent-mode
 * task</li>
 * <li>{@code structured_output} augmentation applied in the legacy step</li>
 * <li>{@code ToolApprovalRequiredException} rethrow (never demoted)</li>
 * <li>event-sink escalation callbacks (non-null sink branch)</li>
 * </ul>
 *
 * <p>
 * This file is strictly additive — it does not modify the class under test or
 * the existing test.
 */
@DisplayName("CascadingModelExecutor Coverage Tests")
class CascadingModelExecutorCoverageTest {

    // ─── Helpers (mirrors CascadingModelExecutorTest) ──────────────────

    private static IConversationMemory mockMemory() {
        IConversationMemory memory = mock(IConversationMemory.class);
        lenient().doReturn(null).when(memory).getEventSink();
        return memory;
    }

    private static IConversationMemory mockMemoryWithSink(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        lenient().doReturn(sink).when(memory).getEventSink();
        return memory;
    }

    private static AgentOrchestrator mockOrchestrator() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        try {
            lenient().doReturn(null).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any());
            lenient().doReturn(null).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt());
            lenient().doReturn(null).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return orchestrator;
    }

    private static LlmConfiguration.Task createTask() {
        var task = new LlmConfiguration.Task();
        task.setId("test-task");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "test-key"));
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(10L);
        task.setRetry(retry);
        return task;
    }

    /** A task that triggers agent mode via built-in tools. */
    private static LlmConfiguration.Task createAgentTask() {
        var task = createTask();
        task.setEnableBuiltInTools(true);
        return task;
    }

    private static List<ChatMessage> createMessages() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("You are a helpful assistant"));
        messages.add(UserMessage.from("Hello"));
        return messages;
    }

    /** Messages with NO system message — exercises the augment add-new branch. */
    private static List<ChatMessage> messagesNoSystem() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("Hello"));
        return messages;
    }

    private static ChatResponse chatResponseOf(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    private static ChatModelRegistry mockRegistry(ChatModel model) {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        try {
            lenient().doReturn(model).when(registry).getOrCreate(anyString(), anyMap());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    private static CascadeStep step(String type, Double threshold, Long timeoutMs) {
        var s = new CascadeStep();
        s.setType(type);
        if (threshold != null) {
            s.setConfidenceThreshold(threshold);
        }
        if (timeoutMs != null) {
            s.setTimeoutMs(timeoutMs);
        }
        return s;
    }

    /** A model whose chat() blocks longer than the step timeout. */
    private static ChatModel blockingModel(long blockMs) {
        ChatModel model = mock(ChatModel.class);
        doAnswer(inv -> {
            Thread.sleep(blockMs);
            return chatResponseOf("slow answer");
        }).when(model).chat(anyList());
        return model;
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. Step timeout → future.cancel + escalation
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("timeout handling")
    class TimeoutHandling {

        @Test
        @DisplayName("first step times out → escalates to second which succeeds")
        void firstStepTimesOut_escalates() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 30L),
                    step("anthropic", null, 5000L)));

            ChatModel slow = blockingModel(2000L);
            ChatModel fast = mock(ChatModel.class);
            doReturn(chatResponseOf("recovered")).when(fast).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(slow).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(fast).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals(1, result.stepUsed());
            assertEquals("recovered", result.response());
            assertEquals("timeout", result.trace().get(0).get("status"));
        }

        @Test
        @DisplayName("first step times out with event sink → onCascadeEscalation('timeout') fired")
        void timeoutEscalation_firesEventSink() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 30L),
                    step("anthropic", null, 5000L)));

            ChatModel slow = blockingModel(2000L);
            ChatModel fast = mock(ChatModel.class);
            doReturn(chatResponseOf("ok")).when(fast).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(slow).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(fast).when(registry).getOrCreate(eq("anthropic"), anyMap());

            ConversationEventSink sink = mock(ConversationEventSink.class);

            CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemoryWithSink(sink), mockOrchestrator());

            verify(sink).onCascadeEscalation(eq(0), eq(1), eq(0.0), anyDouble(), eq("timeout"), anyLong());
        }

        @Test
        @DisplayName("single step (last) times out with no bestSoFar → LifecycleException")
        void lastStepTimesOut_noBest_throws() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(step("openai", null, 30L)));

            ChatModel slow = blockingModel(2000L);

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    mockRegistry(slow), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator()));

            assertTrue(ex.getMessage().contains("all steps exhausted"),
                    "Expected aggregated timeout error, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("second (last) step times out but bestSoFar exists → returns bestSoFar")
        void lastStepTimesOut_withBest_returnsBest() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");
            cascade.setSteps(List.of(
                    step("openai", 0.99, 5000L), // decent but below 0.99 → escalate, becomes bestSoFar
                    step("anthropic", null, 30L))); // last step times out

            ChatModel good = mock(ChatModel.class);
            doReturn(chatResponseOf("A reasonably good answer that covers the main points in detail."))
                    .when(good).chat(anyList());
            ChatModel slow = blockingModel(2000L);

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(good).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(slow).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertNotNull(result);
            assertEquals(0, result.stepUsed(), "bestSoFar came from step 0");
            assertTrue(result.response().contains("reasonably good answer"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. ExecutionException unwrapping (cause type discrimination)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ExecutionException unwrapping")
    class ExecutionExceptionUnwrapping {

        @Test
        @DisplayName("model throws LifecycleException (unwrapped as LifecycleException) → escalates")
        void lifecycleExceptionCause_escalates() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            // executeChatWithRetry ultimately surfaces this; ExecutionException.getCause()
            // is a LifecycleException → the `cause instanceof LifecycleException` branch.
            ChatModel failModel = mock(ChatModel.class);
            doThrow(new RuntimeException(new LifecycleException("wrapped lifecycle failure")))
                    .when(failModel).chat(anyList());
            ChatModel goodModel = mock(ChatModel.class);
            doReturn(chatResponseOf("recovered")).when(goodModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(goodModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals(1, result.stepUsed());
            assertEquals("recovered", result.response());
        }

        @Test
        @DisplayName("model throws generic Exception → 'error' status, escalates")
        void genericExceptionCause_escalates() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel failModel = mock(ChatModel.class);
            doThrow(new IllegalStateException("boom")).when(failModel).chat(anyList());
            ChatModel goodModel = mock(ChatModel.class);
            doReturn(chatResponseOf("recovered")).when(goodModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(goodModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals(1, result.stepUsed());
            assertEquals("error", result.trace().get(0).get("status"));
        }

        @Test
        @DisplayName("retryable error message → 'retryable_error' status in trace")
        void retryableError_statusClassified() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel failModel = mock(ChatModel.class);
            doThrow(new RuntimeException("rate limit exceeded (429)")).when(failModel).chat(anyList());
            ChatModel goodModel = mock(ChatModel.class);
            doReturn(chatResponseOf("recovered")).when(goodModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(goodModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals("retryable_error", result.trace().get(0).get("status"));
        }

        @Test
        @DisplayName("error escalation with event sink → onCascadeEscalation(errorType) fired")
        void errorEscalation_firesEventSink() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel failModel = mock(ChatModel.class);
            doThrow(new IllegalStateException("boom")).when(failModel).chat(anyList());
            ChatModel goodModel = mock(ChatModel.class);
            doReturn(chatResponseOf("ok")).when(goodModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(goodModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            ConversationEventSink sink = mock(ConversationEventSink.class);

            CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemoryWithSink(sink), mockOrchestrator());

            verify(sink).onCascadeEscalation(eq(0), eq(1), eq(0.0), anyDouble(), eq("error"), anyLong());
        }

        @Test
        @DisplayName("agent-mode orchestrator throws plain RuntimeException → non-LifecycleException cause branch, escalates")
        void agentModePlainException_escalates() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            // Second (last) step falls back to legacy chat after orchestrator returns null.
            lenient().doReturn(chatResponseOf("recovered")).when(model).chat(anyList());

            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            // First step: orchestrator throws a plain (non-Lifecycle) RuntimeException.
            // The ExecutionException cause is therefore NOT a LifecycleException, hitting
            // the `cause instanceof Exception ex` unwrap branch in executeStepWithTimeout.
            // Second step: returns null → legacy fallback succeeds.
            when(orchestrator.executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt()))
                    .thenThrow(new IllegalStateException("plain agent failure"))
                    .thenReturn(null);

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator);

            assertEquals(1, result.stepUsed());
            assertEquals("recovered", result.response());
            assertEquals("error", result.trace().get(0).get("status"));
        }

        @Test
        @DisplayName("all steps fail with an error cause → LifecycleException wraps the last cause")
        void allStepsFail_wrapsCause() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel failModel = mock(ChatModel.class);
            doThrow(new IllegalStateException("boom")).when(failModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(anyString(), anyMap());

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator()));

            assertTrue(ex.getMessage().contains("all steps exhausted"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. Agent mode step execution
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("agent mode")
    class AgentMode {

        @Test
        @DisplayName("enableInAgentMode + agent task → orchestrator result used, agentResult populated")
        void agentMode_usesOrchestratorResult() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(step("openai", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            // In agent mode chat() is NOT called directly (orchestrator handles it).
            lenient().doReturn(chatResponseOf("unused")).when(model).chat(anyList());

            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            var execResult = new AgentOrchestrator.ExecutionResult("agent tool answer", new ArrayList<>());
            doReturn(execResult).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator);

            assertEquals("agent tool answer", result.response());
            assertNotNull(result.agentResult());
            assertEquals("agent tool answer", result.agentResult().response());
        }

        @Test
        @DisplayName("agent mode but orchestrator returns null → legacy fallback (chat called)")
        void agentMode_nullResult_fallsBackToLegacy() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(true);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(step("openai", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            doReturn(chatResponseOf("legacy fallback answer")).when(model).chat(anyList());

            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            doReturn(null).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator);

            assertEquals("legacy fallback answer", result.response());
            assertNull(result.agentResult(), "legacy fallback has no agentResult");
            verify(model).chat(anyList());
        }

        @Test
        @DisplayName("enableInAgentMode=false with agent task → legacy mode (orchestrator NOT called)")
        void enableInAgentModeFalse_skipsAgentMode() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(false);
            cascade.setEvaluationStrategy("none");
            cascade.setSteps(List.of(step("openai", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            doReturn(chatResponseOf("legacy answer")).when(model).chat(anyList());

            AgentOrchestrator orchestrator = mockOrchestrator();

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator);

            assertEquals("legacy answer", result.response());
            verify(model).chat(anyList());
            verify(orchestrator, never())
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("agent mode + structured_output → orchestrator confidence parsed from JSON")
        void agentMode_structuredOutput_parsesConfidence() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(true);
            cascade.setEvaluationStrategy("structured_output");
            cascade.setSteps(List.of(step("openai", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            lenient().doReturn(chatResponseOf("unused")).when(model).chat(anyList());

            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            var execResult = new AgentOrchestrator.ExecutionResult(
                    "{\"response\": \"agent said this\", \"confidence\": 0.77}", new ArrayList<>());
            doReturn(execResult).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator);

            assertEquals("agent said this", result.response());
            assertEquals(0.77, result.confidence(), 0.01);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. ToolApprovalRequiredException rethrow (HITL tool pause)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("tool-approval pause rethrow")
    class ToolApprovalRethrow {

        @Test
        @DisplayName("orchestrator throws ToolApprovalRequiredException → rethrown, never demoted")
        void toolApprovalRequired_rethrown() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEnableInAgentMode(true);
            cascade.setEvaluationStrategy("none");
            // Two steps: the pause on the FIRST must NOT be swallowed into escalation.
            cascade.setSteps(List.of(
                    step("openai", 0.5, 5000L),
                    step("anthropic", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            lenient().doReturn(chatResponseOf("unused")).when(model).chat(anyList());

            var pause = new ToolApprovalRequiredException("needs human approval", null);
            AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
            doThrow(pause).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any(), any(), anyInt(), anyInt());

            var thrown = assertThrows(ToolApprovalRequiredException.class, () -> CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createAgentTask(), mockMemory(), orchestrator));

            assertSame(pause, thrown, "The exact pause signal must propagate unchanged");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. structured_output augmentation via the legacy step (add-new-system branch)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("structured_output augmentation (legacy step)")
    class StructuredOutputLegacy {

        @Test
        @DisplayName("structured_output with no system message in list → augmentation adds one, still parses")
        void structuredOutput_noSystemMessage_augments() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("structured_output");
            cascade.setSteps(List.of(step("openai", null, 5000L)));

            ChatModel model = mock(ChatModel.class);
            doReturn(chatResponseOf("{\"response\": \"answer\", \"confidence\": 0.61}"))
                    .when(model).chat(anyList());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, messagesNoSystem(), "You are terse",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals("answer", result.response());
            assertEquals(0.61, result.confidence(), 0.01);
        }
    }
}
