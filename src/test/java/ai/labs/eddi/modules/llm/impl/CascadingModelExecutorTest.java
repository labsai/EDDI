/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for {@link CascadingModelExecutor}:
 * <ul>
 * <li>execute — null/empty steps validation</li>
 * <li>execute — single step meets threshold</li>
 * <li>execute — multi-step escalation on low confidence</li>
 * <li>execute — last step always accepts regardless of confidence</li>
 * <li>execute — step exception → escalation → next succeeds</li>
 * <li>execute — all steps fail → LifecycleException with aggregated errors</li>
 * <li>execute — all steps fail but bestSoFar exists → returns best</li>
 * <li>mergeParams — null base, null step, both set with override</li>
 * <li>augmentMessagesForStructuredOutput — via reflection</li>
 * </ul>
 */
@DisplayName("CascadingModelExecutor Tests")
class CascadingModelExecutorTest {

    // ─── Helpers ───────────────────────────────────────────────────

    private static ChatModelRegistry mockRegistry(ChatModel model) {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        try {
            doReturn(model).when(registry).getOrCreate(anyString(), anyMap());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    private static IConversationMemory mockMemory() {
        IConversationMemory memory = mock(IConversationMemory.class);
        doReturn(null).when(memory).getEventSink();
        return memory;
    }

    private static AgentOrchestrator mockOrchestrator() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        try {
            doReturn(null).when(orchestrator)
                    .executeIfToolsEnabled(any(), anyString(), anyList(), any(), any());
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
        // Single attempt to avoid retry delays in tests
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(10L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> createMessages() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from("You are a helpful assistant"));
        messages.add(UserMessage.from("Hello"));
        return messages;
    }

    private static ChatResponse chatResponseOf(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. execute — null/empty steps
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — null/empty steps")
    class ExecuteNullEmptySteps {

        @Test
        @DisplayName("null steps → throws LifecycleException with 'no steps configured'")
        void nullSteps_throwsLifecycleException() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(null);

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    mock(ChatModelRegistry.class), cascade,
                    createMessages(), "system", Map.of(),
                    createTask(), mockMemory(), mockOrchestrator()));

            assertTrue(ex.getMessage().contains("no steps configured"),
                    "Expected message to contain 'no steps configured', got: " + ex.getMessage());
        }

        @Test
        @DisplayName("empty steps → throws LifecycleException with 'no steps configured'")
        void emptySteps_throwsLifecycleException() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(List.of());

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    mock(ChatModelRegistry.class), cascade,
                    createMessages(), "system", Map.of(),
                    createTask(), mockMemory(), mockOrchestrator()));

            assertTrue(ex.getMessage().contains("no steps configured"),
                    "Expected message to contain 'no steps configured', got: " + ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. execute — single step meets threshold
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — single step meets threshold")
    class ExecuteSingleStepMeetsThreshold {

        @Test
        @DisplayName("single step with 'none' strategy → accepted with confidence 1.0, stepUsed=0, correct modelType")
        void singleStep_noneStrategy_accepted() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(5000L);
            step.setConfidenceThreshold(0.8);
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            doReturn(chatResponseOf("The answer is 42"))
                    .when(model).chat(anyList());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertNotNull(result);
            assertEquals("The answer is 42", result.response());
            assertEquals(1.0, result.confidence(), 0.01);
            assertEquals(0, result.stepUsed());
            assertEquals("openai", result.modelType());
            assertNotNull(result.trace());
            assertFalse(result.trace().isEmpty());
        }

        @Test
        @DisplayName("single step with structured_output — parses JSON confidence correctly")
        void singleStep_structuredOutput_parsesConfidence() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("structured_output");

            var step = new CascadeStep();
            step.setType("anthropic");
            step.setTimeoutMs(5000L);
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            doReturn(chatResponseOf("{\"response\": \"Paris is the capital\", \"confidence\": 0.92}"))
                    .when(model).chat(anyList());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals("Paris is the capital", result.response());
            assertEquals(0.92, result.confidence(), 0.01);
            assertEquals(0, result.stepUsed());
            assertEquals("anthropic", result.modelType());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. execute — two steps: first below threshold → escalates
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — escalation on low confidence")
    class ExecuteEscalation {

        @Test
        @DisplayName("first step below threshold → escalates to second step")
        void firstBelowThreshold_escalatesToSecond() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.9);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(5000L);
            // Last step — no threshold needed

            cascade.setSteps(List.of(step1, step2));

            // First model: hedging → heuristic gives ~0.4
            ChatModel cheapModel = mock(ChatModel.class);
            doReturn(chatResponseOf("I'm not sure about this topic, I don't know"))
                    .when(cheapModel).chat(anyList());

            // Second model: confident → heuristic gives ~0.8
            ChatModel expensiveModel = mock(ChatModel.class);
            doReturn(chatResponseOf("Here is a comprehensive and detailed answer to the question at hand."))
                    .when(expensiveModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(cheapModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(expensiveModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals(1, result.stepUsed(), "Should escalate to second step");
            assertEquals("anthropic", result.modelType());
            assertEquals(2, result.trace().size(), "Trace should have entries for both steps");
            assertEquals("escalated", result.trace().get(0).get("status"));
            assertEquals("accepted", result.trace().get(1).get("status"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. execute — last step always accepts regardless of confidence
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — last step always accepts")
    class ExecuteLastStepAlwaysAccepts {

        @Test
        @DisplayName("last step accepts even with very low confidence")
        void lastStep_acceptsEvenLowConfidence() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            // Only one step — it IS the last step
            var step = new CascadeStep();
            step.setType("openai");
            step.setConfidenceThreshold(0.99); // Normally would escalate
            step.setTimeoutMs(5000L);
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            // Hedging response → heuristic gives ~0.4 — well below 0.99
            doReturn(chatResponseOf("I'm not sure about this, could you clarify?"))
                    .when(model).chat(anyList());

            var result = CascadingModelExecutor.execute(
                    mockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            // Even though confidence < threshold, it's the last step → accepted
            assertNotNull(result);
            assertEquals(0, result.stepUsed());
            assertTrue(result.confidence() < 0.99,
                    "Confidence should be below threshold but still accepted");
            assertEquals("accepted", result.trace().get(0).get("status"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. execute — step exception → escalation → next succeeds
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — exception escalation")
    class ExecuteExceptionEscalation {

        @Test
        @DisplayName("first step throws exception → escalates → second succeeds")
        void firstStepException_escalatesToSecond() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(5000L);

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            doThrow(new RuntimeException("API connection refused"))
                    .when(failModel).chat(anyList());

            ChatModel goodModel = mock(ChatModel.class);
            doReturn(chatResponseOf("Successfully recovered"))
                    .when(goodModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(failModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(goodModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            assertEquals(1, result.stepUsed());
            assertEquals("Successfully recovered", result.response());
            assertEquals("anthropic", result.modelType());
            assertEquals(1.0, result.confidence(), 0.01); // "none" strategy
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. execute — all steps fail → throws LifecycleException
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — all steps fail")
    class ExecuteAllStepsFail {

        @Test
        @DisplayName("all steps throw exceptions → LifecycleException with aggregated errors")
        void allStepsFail_throwsAggregatedError() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(5000L);

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel1 = mock(ChatModel.class);
            doThrow(new RuntimeException("OpenAI down"))
                    .when(failModel1).chat(anyList());

            ChatModel failModel2 = mock(ChatModel.class);
            doThrow(new RuntimeException("Anthropic down"))
                    .when(failModel2).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            try {
                doReturn(failModel1).when(registry).getOrCreate(eq("openai"), anyMap());
                doReturn(failModel2).when(registry).getOrCreate(eq("anthropic"), anyMap());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator()));

            assertTrue(ex.getMessage().contains("all steps exhausted"),
                    "Expected aggregated error message, got: " + ex.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. execute — all steps fail but bestSoFar exists
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("execute — bestSoFar fallback")
    class ExecuteBestSoFarFallback {

        @Test
        @DisplayName("first step produces result below threshold, second fails → returns bestSoFar")
        void bestSoFar_returnedWhenLastFails() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.95); // Very high threshold → will escalate
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(5000L);

            cascade.setSteps(List.of(step1, step2));

            // First model: decent response but below 0.95 threshold
            ChatModel cheapModel = mock(ChatModel.class);
            doReturn(chatResponseOf("A reasonably good answer that covers the main points in detail."))
                    .when(cheapModel).chat(anyList());

            // Second model: fails completely
            ChatModel failModel = mock(ChatModel.class);
            doThrow(new RuntimeException("Rate limit exceeded"))
                    .when(failModel).chat(anyList());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            doReturn(cheapModel).when(registry).getOrCreate(eq("openai"), anyMap());
            doReturn(failModel).when(registry).getOrCreate(eq("anthropic"), anyMap());

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), mockMemory(), mockOrchestrator());

            // Should return bestSoFar from step 0
            assertNotNull(result);
            assertTrue(result.response().contains("reasonably good answer"));
            assertEquals(0, result.stepUsed(), "bestSoFar came from step 0");
            assertEquals("openai", result.modelType());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. mergeParams
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("mergeParams")
    class MergeParamsTests {

        @Test
        @DisplayName("both null — returns empty map")
        void bothNull() {
            var result = CascadingModelExecutor.mergeParams(null, null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("baseParams null — returns stepParams only")
        void baseNull() {
            var step = Map.of("model", "gpt-4");
            var result = CascadingModelExecutor.mergeParams(null, step);
            assertEquals("gpt-4", result.get("model"));
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("stepParams null — returns baseParams only")
        void stepNull() {
            var base = Map.of("temperature", "0.7");
            var result = CascadingModelExecutor.mergeParams(base, null);
            assertEquals("0.7", result.get("temperature"));
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("step params override base params on conflict")
        void stepOverridesBase() {
            var base = new HashMap<String, String>();
            base.put("model", "gpt-3.5");
            base.put("temperature", "0.7");

            var step = new HashMap<String, String>();
            step.put("model", "gpt-4");

            var result = CascadingModelExecutor.mergeParams(base, step);
            assertEquals("gpt-4", result.get("model"), "Step should override base");
            assertEquals("0.7", result.get("temperature"), "Non-conflicting key preserved");
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("non-overlapping keys are merged")
        void nonOverlapping() {
            var base = Map.of("key1", "val1");
            var step = Map.of("key2", "val2");

            var result = CascadingModelExecutor.mergeParams(base, step);
            assertEquals("val1", result.get("key1"));
            assertEquals("val2", result.get("key2"));
            assertEquals(2, result.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 9. augmentMessagesForStructuredOutput (private — via reflection)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("augmentMessagesForStructuredOutput")
    class AugmentMessagesTests {

        private Method getAugmentMethod() throws NoSuchMethodException {
            Method method = CascadingModelExecutor.class.getDeclaredMethod(
                    "augmentMessagesForStructuredOutput", List.class, String.class);
            method.setAccessible(true);
            return method;
        }

        @SuppressWarnings("unchecked")
        private List<ChatMessage> invokeAugment(List<ChatMessage> messages, String systemMessage)
                throws Exception {
            try {
                return (List<ChatMessage>) getAugmentMethod().invoke(null, messages, systemMessage);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception ex) {
                    throw ex;
                }
                throw e;
            }
        }

        @Test
        @DisplayName("augments existing system message with confidence instruction")
        void augmentsExistingSystemMessage() throws Exception {
            var messages = new ArrayList<ChatMessage>();
            messages.add(SystemMessage.from("You are helpful"));
            messages.add(UserMessage.from("What is 2+2?"));

            var result = invokeAugment(messages, "You are helpful");

            assertEquals(2, result.size(), "Same number of messages");
            assertInstanceOf(SystemMessage.class, result.get(0));
            String augmented = ((SystemMessage) result.get(0)).text();
            assertTrue(augmented.startsWith("You are helpful"),
                    "Should start with original system message");
            assertTrue(augmented.contains("confidence"),
                    "Should contain confidence instruction");
            assertTrue(augmented.contains("JSON"),
                    "Should mention JSON format");
            // User message should be unchanged
            assertInstanceOf(UserMessage.class, result.get(1));
        }

        @Test
        @DisplayName("adds new system message if none present")
        void addsNewSystemMessage() throws Exception {
            var messages = new ArrayList<ChatMessage>();
            messages.add(UserMessage.from("Hello"));

            var result = invokeAugment(messages, "Be concise");

            assertEquals(2, result.size(), "Should have added a system message");
            assertInstanceOf(SystemMessage.class, result.get(0));
            String sysText = ((SystemMessage) result.get(0)).text();
            assertTrue(sysText.startsWith("Be concise"),
                    "Should start with the provided systemMessage");
            assertTrue(sysText.contains("confidence"),
                    "Should contain confidence instruction");
        }

        @Test
        @DisplayName("no system message and null systemMessage param — no system message added")
        void noSystemMessage_nullParam() throws Exception {
            var messages = new ArrayList<ChatMessage>();
            messages.add(UserMessage.from("Hello"));

            var result = invokeAugment(messages, null);

            assertEquals(1, result.size(), "Should not add system message when param is null");
            assertInstanceOf(UserMessage.class, result.get(0));
        }

        @Test
        @DisplayName("only first system message is augmented (duplicates preserved)")
        void onlyFirstSystemAugmented() throws Exception {
            var messages = new ArrayList<ChatMessage>();
            messages.add(SystemMessage.from("First system"));
            messages.add(UserMessage.from("question"));
            messages.add(SystemMessage.from("Second system"));

            var result = invokeAugment(messages, "First system");

            assertEquals(3, result.size());
            // First system message should be augmented
            String first = ((SystemMessage) result.get(0)).text();
            assertTrue(first.contains("confidence"), "First system message should be augmented");
            // Second system message should be unchanged
            String second = ((SystemMessage) result.get(2)).text();
            assertEquals("Second system", second, "Second system message should be unchanged");
        }
    }
}
