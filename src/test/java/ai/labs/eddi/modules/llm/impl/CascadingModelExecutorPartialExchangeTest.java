/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cascade step that dies after some of its tools completed — a timeout on the
 * next model request, or a thrown failure — hands those tools' calls and
 * results to the next step, which therefore does not run them again.
 * <p>
 * Runs the REAL tool loop ({@link ToolLoopRunner}) under the cascade, with a
 * tool that counts its executions: the property is "the side effect happened
 * once", and a stubbed orchestrator could only assert what it was told to
 * record.
 */
@DisplayName("CascadingModelExecutor — a failed step's completed tools are carried, not replayed")
class CascadingModelExecutorPartialExchangeTest {

    private static final String LONG_ANSWER = "Here is a thorough and complete answer that easily clears the heuristic's length floor.";

    private final AtomicInteger orderExecutions = new AtomicInteger();
    /** The messages of every model request, across both steps, in order. */
    private final List<List<ChatMessage>> requests = Collections.synchronizedList(new ArrayList<>());

    private ChatModel model;
    private ChatModelRegistry registry;
    private IConversationMemory memory;
    private IAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        model = mock(ChatModel.class);
        registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        memory = mock(IConversationMemory.class);
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        orchestrator = new LoopBackedOrchestrator(toolLoopRunner(), toolSetup());
    }

    /** What the model does on the step-0 request that follows the executed tool. */
    private enum SecondRequest {
        HANGS, FAILS
    }

    /**
     * Request 0 (step 0) asks for {@code placeOrder}. Request 1 (step 0) hangs or
     * fails. Every later request (step 1) asks for {@code placeOrder} unless the
     * transcript already holds its result — then it answers.
     */
    private void scriptModel(SecondRequest second, boolean failFirst) {
        AtomicInteger call = new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest request = inv.getArgument(0);
            requests.add(List.copyOf(request.messages()));
            int n = call.getAndIncrement();
            if (n == 0 && failFirst) {
                throw new IllegalStateException("provider unavailable before any tool");
            }
            if (n == 1 && !failFirst) {
                if (second == SecondRequest.HANGS) {
                    Thread.sleep(10_000);
                }
                throw new IllegalStateException("provider failed after the tool ran");
            }
            boolean orderPlaced = request.messages().stream()
                    .anyMatch(m -> m instanceof ToolExecutionResultMessage r && "placeOrder".equals(r.toolName()));
            if (orderPlaced) {
                return ChatResponse.builder().aiMessage(AiMessage.from(LONG_ANSWER)).build();
            }
            return ChatResponse.builder()
                    .aiMessage(
                            AiMessage.from(ToolExecutionRequest.builder().id("c" + n).name("placeOrder").arguments("{\"item\":\"pizza\"}").build()))
                    .build();
        });
    }

    private ToolLoopRunner toolLoopRunner() {
        var toolExecutionService = mock(ToolExecutionService.class);
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), nullable(String.class),
                        any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        var truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        return new ToolLoopRunner(toolExecutionService, truncator, null, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
    }

    private AgentOrchestrator.ToolSetup toolSetup() {
        ToolExecutor placeOrder = (request, memoryId) -> "order #" + orderExecutions.incrementAndGet() + " placed";
        var spec = ToolSpecification.builder().name("placeOrder").description("places an order").build();
        return new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("placeOrder", placeOrder), Map.of("placeOrder", "http"), List.of(), Map.of(),
                Map.of(), Map.of());
    }

    /**
     * The orchestrator's tool assembly is not under test — only the loop it hands
     * over to, and the recorder the cascade passes through it.
     */
    private record LoopBackedOrchestrator(ToolLoopRunner runner, AgentOrchestrator.ToolSetup setup) implements IAgentOrchestrator {

        @Override
        public AgentOrchestrator.ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                                       LlmConfiguration.Task task, IConversationMemory memory,
                                                                       ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                                                       int transcriptMaxBytes, JsonResponseFormatPolicy jsonPolicy)
                throws LifecycleException {
            return executeIfToolsEnabled(chatModel, systemMessage, chatMessages, task, memory, effectiveToolApprovals, llmTaskIndex,
                    transcriptMaxBytes, jsonPolicy, null);
        }

        @Override
        public AgentOrchestrator.ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                                       LlmConfiguration.Task task, IConversationMemory memory,
                                                                       ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                                                       int transcriptMaxBytes, JsonResponseFormatPolicy jsonPolicy,
                                                                       ToolExchangeRecorder exchangeRecorder)
                throws LifecycleException {
            return runner.executeWithTools(chatModel, systemMessage, chatMessages, setup, task, memory, effectiveToolApprovals, llmTaskIndex,
                    transcriptMaxBytes, jsonPolicy, exchangeRecorder);
        }

        @Override
        public AgentOrchestrator.ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory,
                                                                PendingToolCallBatch batch, HitlDecision decision, boolean toolHitlEnabled,
                                                                JsonResponseFormatPolicy jsonPolicy) {
            throw new UnsupportedOperationException();
        }
    }

    private CascadingModelExecutor executor() {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null,
                new CallerIdentityContext(null, null));
    }

    private static LlmConfiguration.Task agentTask() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        task.setEnableBuiltInTools(true);
        // No token estimator in this harness; the context ceiling is not under test.
        task.setMaxToolContextTokens(0);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        return task;
    }

    private static ModelCascadeConfig twoStepCascade() {
        var cheap = new CascadeStep();
        cheap.setType("openai");
        cheap.setConfidenceThreshold(0.9);
        cheap.setTimeoutMs(1_000L);
        var strong = new CascadeStep();
        strong.setType("openai");
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(cheap, strong));
        return cascade;
    }

    private CascadingModelExecutor.CascadeResult run(ModelCascadeConfig cascade) throws LifecycleException {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("order a pizza")));
        return executor().execute(cascade, messages, "sys", Map.of(), agentTask(), memory, orchestrator, Map.of(), false, false, false);
    }

    private static long toolResults(List<ChatMessage> messages) {
        return messages.stream().filter(m -> m instanceof ToolExecutionResultMessage).count();
    }

    /** The first request of step 1 — the one after step 0's two. */
    private List<ChatMessage> firstRequestOfStepOne(int step0Requests) {
        return requests.get(step0Requests);
    }

    @Test
    @DisplayName("a step that times out after one tool: the next step receives that tool's exchange and the tool runs once")
    void timeoutAfterToolCarriesCompletedExchange() throws Exception {
        scriptModel(SecondRequest.HANGS, false);

        var result = run(twoStepCascade());

        assertEquals(1, result.stepUsed());
        assertEquals(LONG_ANSWER, result.response());
        assertEquals(1, orderExecutions.get(), "the order the timed-out step placed must not be placed again");
        List<ChatMessage> stepOne = firstRequestOfStepOne(2);
        assertEquals(1, toolResults(stepOne), "step 1 starts from the completed call and its result");
        assertTrue(stepOne.stream().anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()
                && "placeOrder".equals(ai.toolExecutionRequests().getFirst().name())), stepOne.toString());
        assertEquals("timeout", result.trace().getFirst().get("status"));
        assertEquals(2, result.trace().getFirst().get("completedToolMessages"), result.trace().toString());
        assertEquals(2, result.trace().get(1).get("carriedToolMessages"), result.trace().toString());
    }

    @Test
    @DisplayName("a step that fails after one tool: the next step receives that tool's exchange and the tool runs once")
    void failureAfterToolCarriesCompletedExchange() throws Exception {
        scriptModel(SecondRequest.FAILS, false);

        var result = run(twoStepCascade());

        assertEquals(1, result.stepUsed());
        assertEquals(1, orderExecutions.get(), "the order the failed step placed must not be placed again");
        assertEquals(1, toolResults(firstRequestOfStepOne(2)));
        assertEquals("error", result.trace().getFirst().get("status"));
        assertEquals(2, result.trace().getFirst().get("completedToolMessages"), result.trace().toString());
    }

    @Test
    @DisplayName("a step that fails before any tool ran carries nothing")
    void failureBeforeToolsCarriesNothing() throws Exception {
        scriptModel(SecondRequest.FAILS, true);

        var result = run(twoStepCascade());

        assertEquals(1, result.stepUsed());
        assertEquals(List.of(UserMessage.from("order a pizza")), firstRequestOfStepOne(1).subList(1, firstRequestOfStepOne(1).size()),
                "step 1 starts from the conversation alone");
        assertFalse(result.trace().getFirst().containsKey("completedToolMessages"), result.trace().toString());
        assertFalse(result.trace().get(1).containsKey("carriedToolMessages"), result.trace().toString());
        assertEquals(1, orderExecutions.get(), "only step 1 placed the order");
    }

    @Test
    @DisplayName("carryToolResultsOnEscalation: false carries nothing from a failed step either")
    void carryOffCarriesNothingFromFailedStep() throws Exception {
        scriptModel(SecondRequest.FAILS, false);
        var cascade = twoStepCascade();
        cascade.setCarryToolResultsOnEscalation(false);

        var result = run(cascade);

        assertEquals(0, toolResults(firstRequestOfStepOne(2)));
        assertEquals(2, orderExecutions.get(), "with carrying off the next step starts from scratch, as documented");
        assertFalse(result.trace().getFirst().containsKey("completedToolMessages"));
    }
}
