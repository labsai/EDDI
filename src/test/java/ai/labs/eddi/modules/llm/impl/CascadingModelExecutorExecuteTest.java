package ai.labs.eddi.modules.llm.impl;

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
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CascadingModelExecutor#execute} — the full cascade execution
 * flow including timeout, error escalation, agent mode, and confidence
 * evaluation paths.
 */
class CascadingModelExecutorExecuteTest {

    private ChatModelRegistry createMockRegistry(ChatModel model) {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        try {
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    private IConversationMemory createMemory() {
        return createMemory(null);
    }

    private IConversationMemory createMemory(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(sink);
        return memory;
    }

    private LlmConfiguration.Task createTask() {
        var task = new LlmConfiguration.Task();
        task.setId("test");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "key"));
        // Set retry to 1 attempt to avoid slow tests
        var retry = new LlmConfiguration.RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        return task;
    }

    private List<ChatMessage> createMessages() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(new SystemMessage("You are helpful"));
        messages.add(UserMessage.from("Hello"));
        return messages;
    }

    // ==================== Empty/Null Steps ====================

    @Nested
    @DisplayName("Empty/Null Steps Validation")
    class EmptyStepsTests {

        @Test
        @DisplayName("null steps — should throw LifecycleException")
        void testNullSteps() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(null);

            assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    mock(ChatModelRegistry.class), cascade,
                    createMessages(), "system", Map.of(),
                    createTask(), createMemory(), mock(AgentOrchestrator.class)));
        }

        @Test
        @DisplayName("empty steps — should throw LifecycleException")
        void testEmptySteps() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setSteps(List.of());

            assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    mock(ChatModelRegistry.class), cascade,
                    createMessages(), "system", Map.of(),
                    createTask(), createMemory(), mock(AgentOrchestrator.class)));
        }
    }

    // ==================== Single Step (Last Step Always Accepted)
    // ====================

    @Nested
    @DisplayName("Single Step Execution")
    class SingleStepTests {

        @Test
        @DisplayName("single step with 'none' strategy — returns confidence 1.0")
        void testSingleStepNoneStrategy() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            var aiMsg = AiMessage.from("The answer is 42");
            var chatResponse = ChatResponse.builder().aiMessage(aiMsg).build();
            when(model.chat(anyList())).thenReturn(chatResponse);

            var registry = createMockRegistry(model);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), createMemory(),
                    mock(AgentOrchestrator.class));

            assertNotNull(result);
            assertEquals("The answer is 42", result.response());
            assertEquals(1.0, result.confidence(), 0.01);
            assertEquals(0, result.stepUsed());
            assertFalse(result.trace().isEmpty());
        }

        @Test
        @DisplayName("single step with heuristic strategy — evaluates confidence")
        void testSingleStepHeuristicStrategy() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            var aiMsg = AiMessage.from("This is a comprehensive and detailed answer to your question about the topic.");
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(aiMsg).build());

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    createMemory(), mock(AgentOrchestrator.class));

            assertNotNull(result);
            assertTrue(result.confidence() > 0.0);
            assertEquals(0, result.stepUsed());
        }
    }

    // ==================== Multi-Step Escalation ====================

    @Nested
    @DisplayName("Multi-Step Escalation")
    class MultiStepTests {

        @Test
        @DisplayName("first step meets threshold — no escalation")
        void testFirstStepMeetsThreshold() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none"); // confidence=1.0

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("Good answer")).build());

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    createMemory(), mock(AgentOrchestrator.class));

            assertEquals(0, result.stepUsed(), "Should use first step");
            assertEquals(1.0, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("first step below threshold with heuristic hedging — escalates to second")
        void testEscalationOnLowConfidence() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.9); // High threshold

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            // Last step — always accepted

            cascade.setSteps(List.of(step1, step2));

            // First model returns hedging response (low confidence)
            ChatModel cheapModel = mock(ChatModel.class);
            when(cheapModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("I'm not sure about this topic")).build());

            // Second model returns confident response
            ChatModel expensiveModel = mock(ChatModel.class);
            when(expensiveModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("Here is a detailed and confident explanation of the topic with evidence.")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(cheapModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(expensiveModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), createMemory(),
                    mock(AgentOrchestrator.class));

            assertEquals(1, result.stepUsed(), "Should escalate to second step");
            assertEquals(2, result.trace().size(), "Should have trace for both steps");
        }
    }

    // ==================== Error Handling ====================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("first step throws, second succeeds — escalates gracefully")
        void testErrorEscalation() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(1000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(new RuntimeException("API error"));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("Success")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), createMemory(),
                    mock(AgentOrchestrator.class));

            assertEquals(1, result.stepUsed());
            assertEquals("Success", result.response());
        }

        @Test
        @DisplayName("all steps fail — throws LifecycleException with aggregated errors")
        void testAllStepsFail() {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(1000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(1000L);

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(new RuntimeException("Connection refused"));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            try {
                when(registry.getOrCreate(anyString(), anyMap())).thenReturn(failModel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            var ex = assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), createMemory(),
                    mock(AgentOrchestrator.class)));

            assertTrue(ex.getMessage().contains("all steps exhausted"));
        }

        @Test
        @DisplayName("last step fails but bestSoFar exists — returns best response")
        void testBestSoFarFallback() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.95); // Very high — will escalate

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            // First model returns a decent response
            ChatModel cheapModel = mock(ChatModel.class);
            when(cheapModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("A reasonably good answer that covers the main points thoroughly")).build());

            // Second model fails
            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(new RuntimeException("Rate limit exceeded"));

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(cheapModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(failModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(), createMemory(),
                    mock(AgentOrchestrator.class));

            // Should return bestSoFar from step 0
            assertNotNull(result);
            assertTrue(result.response().contains("reasonably good answer"));
        }
    }

    // ==================== SSE Event Sink ====================

    @Nested
    @DisplayName("SSE Event Sink Integration")
    class EventSinkTests {

        @Test
        @DisplayName("event sink present — receives cascade step events")
        void testEventSinkNotified() throws Exception {
            var sink = mock(ConversationEventSink.class);
            var memory = createMemory(sink);

            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    memory, mock(AgentOrchestrator.class));

            verify(sink).onCascadeStepStart(eq(0), eq("openai"), anyString(), eq(1));
        }

        @Test
        @DisplayName("escalation with event sink — receives escalation event")
        void testEscalationEventSink() throws Exception {
            var sink = mock(ConversationEventSink.class);
            var memory = createMemory(sink);

            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.99);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("I'm not sure, I don't know the answer to this")).build())
                    .thenReturn(ChatResponse.builder().aiMessage(
                            AiMessage.from("Here is the definitive answer")).build());

            CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    memory, mock(AgentOrchestrator.class));

            verify(sink).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(), anyString(), anyLong());
        }
    }

    // ==================== Structured Output Augmentation ====================

    @Nested
    @DisplayName("Structured Output Strategy")
    class StructuredOutputTests {

        @Test
        @DisplayName("structured_output — parses JSON confidence from response")
        void testStructuredOutputParsing() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("structured_output");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from(
                            "{\"response\": \"Paris is the capital\", \"confidence\": 0.95}")).build());

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    createMemory(), mock(AgentOrchestrator.class));

            assertNotNull(result);
            assertEquals(0.95, result.confidence(), 0.01);
            assertEquals("Paris is the capital", result.response());
        }
    }

    // ==================== Step Type Inheritance ====================

    @Nested
    @DisplayName("Step Configuration")
    class StepConfigTests {

        @Test
        @DisplayName("step with null type — inherits from task type")
        void testStepInheritsTaskType() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            // null type — should inherit from task
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            var registry = createMockRegistry(model);

            var task = createTask();
            task.setType("gemini");

            CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), task, createMemory(),
                    mock(AgentOrchestrator.class));

            // Should call registry with task type "gemini"
            verify(registry).getOrCreate(eq("gemini"), anyMap());
        }

        @Test
        @DisplayName("step with explicit type — overrides task type")
        void testStepOverridesTaskType() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("anthropic");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            var registry = createMockRegistry(model);

            var task = createTask();
            task.setType("openai");

            CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), task, createMemory(),
                    mock(AgentOrchestrator.class));

            verify(registry).getOrCreate(eq("anthropic"), anyMap());
        }
    }

    // ==================== Trace Verification ====================

    @Nested
    @DisplayName("Trace Output")
    class TraceTests {

        @Test
        @DisplayName("successful cascade — trace includes step, model, status")
        void testTraceContent() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("openai");
            step.setParameters(Map.of("model", "gpt-4o"));
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(),
                    "system", Map.of("apiKey", "key"), createTask(),
                    createMemory(), mock(AgentOrchestrator.class));

            assertEquals(1, result.trace().size());
            var trace = result.trace().get(0);
            assertEquals(0, trace.get("step"));
            assertEquals("openai", trace.get("modelType"));
            assertEquals("accepted", trace.get("status"));
            assertNotNull(trace.get("durationMs"));
        }
    }
}
