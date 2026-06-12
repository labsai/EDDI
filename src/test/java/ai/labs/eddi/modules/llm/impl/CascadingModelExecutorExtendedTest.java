/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link CascadingModelExecutor} — covers branches not
 * exercised by CascadingModelExecutorTest and
 * CascadingModelExecutorExecuteTest: retryable error classification, timeout
 * escalation with bestSoFar, error escalation with event sink, step type
 * inheritance edge cases, and structured output message augmentation.
 */
class CascadingModelExecutorExtendedTest {

    private ChatModelRegistry createMockRegistry(ChatModel model) {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        try {
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
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

    // ==================== isRetryableError Tests ====================

    @Nested
    @DisplayName("Retryable error classification")
    class RetryableErrorTests {

        @Test
        @DisplayName("Timeout in last step with bestSoFar → returns best response")
        void timeoutLastStepWithBestSoFar() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("heuristic");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.99); // very high → will escalate

            var step2 = new CascadeStep();
            step2.setType("anthropic");
            step2.setTimeoutMs(100L); // very short timeout

            cascade.setSteps(List.of(step1, step2));

            // First model returns a decent response
            ChatModel cheapModel = mock(ChatModel.class);
            when(cheapModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("A reasonable answer covering the main aspects of the topic in detail")).build());

            // Second model takes too long (simulated via sleep)
            ChatModel slowModel = mock(ChatModel.class);
            when(slowModel.chat(anyList())).thenAnswer(inv -> {
                Thread.sleep(500);
                return ChatResponse.builder().aiMessage(AiMessage.from("Late")).build();
            });

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(cheapModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(slowModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            // Should return bestSoFar from step 0
            assertNotNull(result);
            assertTrue(result.response().contains("reasonable answer"));
        }

        @Test
        @DisplayName("SocketTimeoutException should be classified as retryable")
        void socketTimeoutIsRetryable() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            // First model throws SocketTimeoutException
            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(
                    new RuntimeException("request failed", new SocketTimeoutException("Read timed out")));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("Success")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertEquals(1, result.stepUsed());
            assertEquals("Success", result.response());
            // Verify trace contains retryable_error status for step 0
            assertTrue(result.trace().stream()
                    .anyMatch(t -> "retryable_error".equals(t.get("status"))));
        }

        @Test
        @DisplayName("ConnectException should be classified as retryable")
        void connectExceptionIsRetryable() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(
                    new RuntimeException("connection", new ConnectException("Connection refused")));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertEquals(1, result.stepUsed());
        }

        @Test
        @DisplayName("Non-retryable error (auth failure) should show 'error' status")
        void nonRetryableError() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(
                    new RuntimeException("Authentication failed"));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertEquals(1, result.stepUsed());
            // Verify first step was marked as "error" not "retryable_error"
            assertTrue(result.trace().stream()
                    .anyMatch(t -> "error".equals(t.get("status"))));
        }

        @Test
        @DisplayName("Rate limit message in error text should be retryable")
        void rateLimitMessageIsRetryable() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(
                    new RuntimeException("Rate limit exceeded (429)"));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            var result = CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertTrue(result.trace().stream()
                    .anyMatch(t -> "retryable_error".equals(t.get("status"))));
        }
    }

    // ==================== Event Sink Escalation Events ====================

    @Nested
    @DisplayName("Event sink escalation events")
    class EventSinkEscalationTests {

        @Test
        @DisplayName("Error escalation with event sink → sink receives escalation event")
        void errorWithEventSink() throws Exception {
            var sink = mock(ConversationEventSink.class);

            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step1 = new CascadeStep();
            step1.setType("openai");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("anthropic");

            cascade.setSteps(List.of(step1, step2));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(new RuntimeException("API error"));

            ChatModel goodModel = mock(ChatModel.class);
            when(goodModel.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("OK")).build());

            ChatModelRegistry registry = mock(ChatModelRegistry.class);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(failModel);
            when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(goodModel);

            CascadingModelExecutor.execute(
                    registry, cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(sink), mock(AgentOrchestrator.class));

            // Verify escalation event was sent
            verify(sink).onCascadeEscalation(eq(0), eq(1), eq(0.0), anyDouble(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Last step error — no escalation event sent to sink")
        void lastStepErrorNoEscalation() {
            var sink = mock(ConversationEventSink.class);

            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("none");

            var step = new CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(5000L);

            cascade.setSteps(List.of(step));

            ChatModel failModel = mock(ChatModel.class);
            when(failModel.chat(anyList())).thenThrow(new RuntimeException("Fail"));

            assertThrows(LifecycleException.class, () -> CascadingModelExecutor.execute(
                    createMockRegistry(failModel), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(sink), mock(AgentOrchestrator.class)));

            // No escalation event (it's the last step)
            verify(sink, never()).onCascadeEscalation(anyInt(), anyInt(), anyDouble(), anyDouble(), anyString(), anyLong());
        }
    }

    // ==================== Structured Output Augmentation ====================

    @Nested
    @DisplayName("Structured output augmentation")
    class StructuredOutputAugmentationTests {

        @Test
        @DisplayName("structured_output augments system message with confidence instruction")
        void augmentsSystemMessage() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("structured_output");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            // Return structured JSON with confidence
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("{\"response\": \"Answer\", \"confidence\": 0.85}")).build());

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, createMessages(), "system",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertNotNull(result);
            assertEquals(0.85, result.confidence(), 0.01);
            assertEquals("Answer", result.response());
        }

        @Test
        @DisplayName("structured_output with messages that have no system message")
        void augmentsWithMissingSystemMessage() throws Exception {
            var cascade = new ModelCascadeConfig();
            cascade.setEnabled(true);
            cascade.setEvaluationStrategy("structured_output");

            var step = new CascadeStep();
            step.setType("openai");
            cascade.setSteps(List.of(step));

            ChatModel model = mock(ChatModel.class);
            when(model.chat(anyList())).thenReturn(
                    ChatResponse.builder().aiMessage(
                            AiMessage.from("{\"response\": \"Answer\", \"confidence\": 0.9}")).build());

            // Messages without system message
            var messages = new ArrayList<ChatMessage>();
            messages.add(UserMessage.from("Hello"));

            var result = CascadingModelExecutor.execute(
                    createMockRegistry(model), cascade, messages, "You are helpful",
                    Map.of("apiKey", "key"), createTask(),
                    createMemory(null), mock(AgentOrchestrator.class));

            assertNotNull(result);
            assertEquals(0.9, result.confidence(), 0.01);
        }
    }

    // ==================== CascadeResult record ====================

    @Nested
    @DisplayName("CascadeResult record")
    class CascadeResultTests {

        @Test
        @DisplayName("CascadeResult stores all fields")
        void recordFields() {
            var trace = List.<Map<String, Object>>of(Map.of("step", 0, "status", "accepted"));
            var agentResult = new AgentOrchestrator.ExecutionResult("agent response", List.of());

            var result = new CascadingModelExecutor.CascadeResult(
                    "response", 0.95, 1, "openai", trace, agentResult);

            assertEquals("response", result.response());
            assertEquals(0.95, result.confidence(), 0.001);
            assertEquals(1, result.stepUsed());
            assertEquals("openai", result.modelType());
            assertEquals(1, result.trace().size());
            assertNotNull(result.agentResult());
        }

        @Test
        @DisplayName("CascadeResult with null agentResult")
        void nullAgentResult() {
            var result = new CascadingModelExecutor.CascadeResult(
                    "resp", 1.0, 0, "anthropic", List.of(), null);

            assertNull(result.agentResult());
        }
    }
}
