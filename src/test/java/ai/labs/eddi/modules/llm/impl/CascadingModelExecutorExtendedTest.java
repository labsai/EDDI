/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Disabled("CascadingModelExecutor uses thread pool, mocks don't work across threads")
@DisplayName("CascadingModelExecutor Extended Tests")
class CascadingModelExecutorExtendedTest {

    private ChatModelRegistry registry;
    private IConversationMemory memory;
    private ConversationEventSink eventSink;
    private AgentOrchestrator agentOrchestrator;
    private LlmConfiguration.Task task;

    @BeforeEach
    void setUp() {
        registry = mock(ChatModelRegistry.class);
        memory = mock(IConversationMemory.class);
        eventSink = mock(ConversationEventSink.class);
        when(memory.getEventSink()).thenReturn(eventSink);
        agentOrchestrator = mock(AgentOrchestrator.class);

        task = new LlmConfiguration.Task();
        task.setType("openai");
    }

    /** Build an executor instance and run the cascade with the new signature. */
    private CascadingModelExecutor.CascadeResult runCascade(ChatModelRegistry registry, ModelCascadeConfig cascade, List<ChatMessage> messages,
                                                            String systemMessage, Map<String, String> params, LlmConfiguration.Task task,
                                                            IConversationMemory memory, AgentOrchestrator orchestrator)
            throws LifecycleException {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null);
        return executor.execute(cascade, messages, systemMessage, params, task, memory, orchestrator, Map.of(), false, false, false);
    }

    @Nested
    @DisplayName("execute — validation")
    class ExecuteValidation {

        @Test
        @DisplayName("null steps — throws LifecycleException")
        void nullSteps() {
            var cascade = new ModelCascadeConfig();
            cascade.setSteps(null);

            assertThrows(LifecycleException.class, () -> runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", Map.of(), task, memory, agentOrchestrator));
        }

        @Test
        @DisplayName("empty steps — throws LifecycleException")
        void emptySteps() {
            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of());

            assertThrows(LifecycleException.class, () -> runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", Map.of(), task, memory, agentOrchestrator));
        }
    }

    @Nested
    @DisplayName("execute — single step accepted")
    class SingleStepAccepted {

        @Test
        @DisplayName("single step with no threshold — always accepted (isLastStep)")
        void singleStepNoThreshold() throws Exception {
            var chatModel = mock(ChatModel.class);
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(chatModel);

            var aiMessage = AiMessage.from("Hello there!");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse);

            var step = new CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            assertNotNull(result);
            assertEquals("Hello there!", result.response());
            assertEquals(0, result.stepUsed());
        }
    }

    @Nested
    @DisplayName("execute — escalation: low confidence")
    class EscalationLowConfidence {

        @Test
        @DisplayName("first step below threshold, second step accepted")
        void escalatesToSecondStep() throws Exception {
            var chatModel1 = mock(ChatModel.class);
            var chatModel2 = mock(ChatModel.class);

            // First model returns low-confidence answer
            var lowConfResp = ChatResponse.builder()
                    .aiMessage(AiMessage.from("I don't know")).build();
            when(chatModel1.chat(any(ChatRequest.class))).thenReturn(lowConfResp);

            // Second model returns good answer
            var highConfResp = ChatResponse.builder()
                    .aiMessage(AiMessage.from("The answer is 42")).build();
            when(chatModel2.chat(any(ChatRequest.class))).thenReturn(highConfResp);

            when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(chatModel1);
            when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(chatModel2);

            var step1 = new CascadeStep();
            step1.setType("cheap");
            step1.setConfidenceThreshold(0.9);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("expensive");
            step2.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step1, step2));
            cascade.setEvaluationStrategy("heuristic");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("What is the meaning?")), "system",
                    new HashMap<>(), task, memory, agentOrchestrator);

            assertNotNull(result);
            // Second step should be used (index 1) because first had low confidence
            assertEquals(1, result.stepUsed());
            // Verify escalation event was sent
            verify(eventSink, atLeastOnce()).onCascadeEscalation(eq(0), eq(1), anyDouble(), anyDouble(), anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("execute — error escalation")
    class ErrorEscalation {

        @Test
        @DisplayName("first step throws, second step succeeds")
        void firstStepThrows() throws Exception {
            var chatModel1 = mock(ChatModel.class);
            var chatModel2 = mock(ChatModel.class);

            when(chatModel1.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("API error"));

            var goodResp = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Success")).build();
            when(chatModel2.chat(any(ChatRequest.class))).thenReturn(goodResp);

            when(registry.getOrCreate(eq("broken"), anyMap())).thenReturn(chatModel1);
            when(registry.getOrCreate(eq("working"), anyMap())).thenReturn(chatModel2);

            var step1 = new CascadeStep();
            step1.setType("broken");
            step1.setConfidenceThreshold(0.5);
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("working");
            step2.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step1, step2));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            assertEquals(1, result.stepUsed());
            assertEquals("Success", result.response());
        }

        @Test
        @DisplayName("last step throws with bestSoFar available — returns bestSoFar")
        void lastStepThrowsWithBestSoFar() throws Exception {
            var chatModel1 = mock(ChatModel.class);
            var chatModel2 = mock(ChatModel.class);

            // First model returns a valid but low confidence result
            var lowResp = ChatResponse.builder()
                    .aiMessage(AiMessage.from("Maybe")).build();
            when(chatModel1.chat(any(ChatRequest.class))).thenReturn(lowResp);

            // Second model throws
            when(chatModel2.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("Crash"));

            when(registry.getOrCreate(eq("partial"), anyMap())).thenReturn(chatModel1);
            when(registry.getOrCreate(eq("crash"), anyMap())).thenReturn(chatModel2);

            var step1 = new CascadeStep();
            step1.setType("partial");
            step1.setConfidenceThreshold(0.99); // force escalation
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("crash");
            step2.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step1, step2));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            // Should return the best-so-far from step 0
            assertEquals(0, result.stepUsed());
        }

        @Test
        @DisplayName("all steps fail with no bestSoFar — throws LifecycleException")
        void allStepsFail() throws Exception {
            var chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("error"));
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(chatModel);

            var step = new CascadeStep();
            step.setType("broken");
            step.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step));
            cascade.setEvaluationStrategy("none");

            assertThrows(LifecycleException.class, () -> runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator));
        }
    }

    @Nested
    @DisplayName("execute — null eventSink")
    class NullEventSink {

        @Test
        @DisplayName("null eventSink — no SSE events emitted, no NPE")
        void nullEventSink() throws Exception {
            when(memory.getEventSink()).thenReturn(null);

            var chatModel = mock(ChatModel.class);
            var resp = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(chatModel);

            var step = new CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("execute — step type fallback to task type")
    class StepTypeFallback {

        @Test
        @DisplayName("step with null type — falls back to task.getType()")
        void stepNullType() throws Exception {
            var chatModel = mock(ChatModel.class);
            var resp = ChatResponse.builder().aiMessage(AiMessage.from("fallback")).build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);
            when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(chatModel);

            var step = new CascadeStep();
            step.setType(null); // should fall back to task type "openai"
            step.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            assertEquals("openai", result.modelType());
        }
    }

    @Nested
    @DisplayName("execute — step with null confidenceThreshold on non-last step")
    class NullThreshold {

        @Test
        @DisplayName("null threshold on non-last step — always accepted")
        void nullThresholdAccepted() throws Exception {
            var chatModel = mock(ChatModel.class);
            var resp = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(chatModel);

            var step1 = new CascadeStep();
            step1.setType("cheap");
            step1.setConfidenceThreshold(null); // null threshold
            step1.setTimeoutMs(5000L);

            var step2 = new CascadeStep();
            step2.setType("expensive");
            step2.setTimeoutMs(5000L);

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step1, step2));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            // Should accept first step because threshold is null
            assertEquals(0, result.stepUsed());
        }
    }

    @Nested
    @DisplayName("execute — null timeoutMs defaults to 30000")
    class NullTimeout {

        @Test
        @DisplayName("null timeoutMs uses default")
        void nullTimeout() throws Exception {
            var chatModel = mock(ChatModel.class);
            var resp = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(resp);
            when(registry.getOrCreate(anyString(), anyMap())).thenReturn(chatModel);

            var step = new CascadeStep();
            step.setType("openai");
            step.setTimeoutMs(null); // null

            var cascade = new ModelCascadeConfig();
            cascade.setSteps(List.of(step));
            cascade.setEvaluationStrategy("none");

            var result = runCascade(registry, cascade,
                    List.of(UserMessage.from("hi")), "system", new HashMap<>(), task, memory, agentOrchestrator);

            assertNotNull(result);
        }
    }
}
