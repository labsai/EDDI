/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.JudgeModelConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H11b and M-L1 on the cascade: an escalation continues from the tools the
 * earlier step already ran instead of re-running them, {@code maxCostPerRun}
 * sees tool and judge spend, and a tool pause records which step raised it.
 */
@DisplayName("CascadingModelExecutor — no tool replay on escalation, complete cost ceiling")
class CascadingModelExecutorToolReplayTest {

    private static final String LONG_ANSWER = "Here is a thorough and complete answer that easily clears the heuristic's length floor.";

    private ChatModel model;
    private ChatModelRegistry registry;
    private IAgentOrchestrator orchestrator;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        model = mock(ChatModel.class);
        registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        orchestrator = mock(IAgentOrchestrator.class);
        memory = mock(IConversationMemory.class);
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
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        return task;
    }

    /**
     * Step 0 escalates (heuristic scores a short answer 0.3 < 0.9); step 1 is last.
     */
    private static ModelCascadeConfig twoStepCascade() {
        var cheap = new CascadeStep();
        cheap.setType("openai");
        cheap.setConfidenceThreshold(0.9);
        var strong = new CascadeStep();
        strong.setType("openai");
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(cheap, strong));
        return cascade;
    }

    private static List<ChatMessage> messages() {
        return new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("order a pizza")));
    }

    private static List<ChatMessage> placedOrderExchange() {
        var request = ToolExecutionRequest.builder().id("c1").name("placeOrder").arguments("{\"item\":\"pizza\"}").build();
        return List.of(AiMessage.from(request), ToolExecutionResultMessage.from(request, "order #42 placed"));
    }

    private static AgentOrchestrator.ExecutionResult stepResult(String response, List<ChatMessage> exchange, double toolCost) {
        List<Map<String, Object>> trace = new ArrayList<>();
        if (!exchange.isEmpty()) {
            trace.add(Map.of("type", "tool_call", "tool", "placeOrder"));
        }
        return new AgentOrchestrator.ExecutionResult(response, trace, Map.of("toolCostUsd", toolCost), exchange);
    }

    @SuppressWarnings("unchecked")
    private List<List<ChatMessage>> capturedStepMessages(int calls) throws Exception {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(orchestrator, times(calls)).executeIfToolsEnabled(any(), any(), captor.capture(), any(), any(), any(), anyInt(), anyInt(), any());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("the next step receives the escalated step's tool calls and results, so they are not re-run")
    void escalationCarriesToolExchange() throws Exception {
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(stepResult("ok", placedOrderExchange(), 0.0))
                .thenReturn(stepResult(LONG_ANSWER, List.of(), 0.0));

        var result = executor().execute(twoStepCascade(), messages(), "sys", Map.of(), agentTask(), memory, orchestrator, Map.of(), false, false,
                false);

        assertEquals(1, result.stepUsed());
        var calls = capturedStepMessages(2);
        assertEquals(List.of(UserMessage.from("order a pizza")), calls.get(0), "step 0 starts from the conversation alone");
        assertEquals(3, calls.get(1).size());
        assertEquals(placedOrderExchange(), calls.get(1).subList(1, 3),
                "step 1 must see the order that was already placed, or it places it again");
        assertEquals(1, result.agentResult().trace().size(),
                "the returned trace covers every step's tool calls, including the escalated step's");
    }

    @Test
    @DisplayName("carryToolResultsOnEscalation: false restores the old start-from-scratch behaviour")
    void carryCanBeTurnedOff() throws Exception {
        var cascade = twoStepCascade();
        cascade.setCarryToolResultsOnEscalation(false);
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(stepResult("ok", placedOrderExchange(), 0.0))
                .thenReturn(stepResult(LONG_ANSWER, List.of(), 0.0));

        executor().execute(cascade, messages(), "sys", Map.of(), agentTask(), memory, orchestrator, Map.of(), false, false, false);

        assertEquals(List.of(UserMessage.from("order a pizza")), capturedStepMessages(2).get(1));
    }

    @Test
    @DisplayName("maxCostPerRun counts tool spend: a step whose tools spent the budget stops the escalation")
    void toolSpendCountsTowardCeiling() throws Exception {
        var cascade = twoStepCascade();
        cascade.setMaxCostPerRun(0.01);
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(stepResult("ok", placedOrderExchange(), 0.05))
                .thenReturn(stepResult(LONG_ANSWER, List.of(), 0.0));

        var result = executor().execute(cascade, messages(), "sys", Map.of(), agentTask(), memory, orchestrator, Map.of(), false, false,
                false);

        assertEquals(0, result.stepUsed(), "the ceiling was already exceeded by tool spend — no second step");
        capturedStepMessages(1);
        assertEquals(0.05, result.runToolCostUsd(), 1e-9);
    }

    @Test
    @DisplayName("maxCostPerRun counts the judge's tokens when the judge is priced")
    void judgeSpendCountsTowardCeiling() throws Exception {
        var cascade = twoStepCascade();
        cascade.setEvaluationStrategy("judge_model");
        var judge = new JudgeModelConfig();
        judge.setType("openai");
        judge.setParameters(Map.of("modelName", "judge"));
        judge.setInputPricePer1M(1.0);
        cascade.setJudgeModel(judge);
        cascade.setMaxCostPerRun(0.5);
        var legacyTask = agentTask();
        legacyTask.setEnableBuiltInTools(false);

        when(model.chat(anyList())).thenAnswer(inv -> {
            List<ChatMessage> sent = inv.getArgument(0);
            boolean isJudge = sent.getFirst() instanceof SystemMessage sm && sm.text().contains("response quality evaluator");
            if (isJudge) {
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"confidence\": 0.1}"))
                        .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(1_000_000, 10)).build()).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("an answer")).build();
        });

        var result = executor().execute(cascade, messages(), "sys", Map.of(), legacyTask, memory, orchestrator, Map.of(), false, false, false);

        assertEquals(0, result.stepUsed(), "one judge call spent $1.00 of a $0.50 ceiling — escalation must stop");
        assertEquals(1.0, result.runCostUsd(), 1e-9, "the judge's spend is part of the run's model cost");
        assertTrue(result.trace().getFirst().containsKey("judgeCostUsd"));
    }

    @Test
    @DisplayName("a tool pause inside a cascade step records the step index on the batch (M-L1)")
    void pauseRecordsStepIndex() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setTraceSoFar(new ArrayList<>(List.of(Map.of("type", "tool_call", "tool", "askApproval"))));
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(stepResult("ok", placedOrderExchange(), 0.0))
                .thenThrow(new ToolApprovalRequiredException("needs approval", batch));

        var thrown = assertThrows(ToolApprovalRequiredException.class, () -> executor().execute(twoStepCascade(), messages(), "sys", Map.of(),
                agentTask(), memory, orchestrator, Map.of(), false, false, false));

        assertSame(batch, thrown.getBatch());
        assertEquals(1, batch.getCascadeStepIndex());
        assertEquals(List.of("placeOrder", "askApproval"), batch.getTraceSoFar().stream().map(e -> e.get("tool")).toList(),
                "the resumed turn's trace must include the escalated step's tools, ahead of the pausing step's");
    }

    /**
     * M2: a carried exchange crosses providers. Ollama/Gemini bindings can hand
     * over tool calls with null ids, which OpenAI/Anthropic reject with a 400.
     * Failing there used to stop the escalation for good; the step is now retried
     * once from the conversation alone.
     */
    @Test
    @DisplayName("a step that rejects the carried exchange (cross-provider 400) is retried once without it")
    void rejectedCarriedExchangeIsRetriedWithoutIt() throws Exception {
        var nullIdRequest = ToolExecutionRequest.builder().name("placeOrder").arguments("{}").build();
        List<ChatMessage> ollamaExchange = List.of(AiMessage.from(nullIdRequest), ToolExecutionResultMessage.from(null, "placeOrder", "placed"));
        var cascade = twoStepCascade();
        cascade.getSteps().get(0).setType("ollama");
        List<List<ChatMessage>> seen = new ArrayList<>();
        int[] calls = {0};
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            List<ChatMessage> sent = inv.getArgument(2);
            seen.add(List.copyOf(sent));
            if (calls[0]++ == 0) {
                return stepResult("ok", ollamaExchange, 0.0);
            }
            // The OpenAI step: a tool call without an id is a 400, a client error.
            boolean nullId = sent.stream().anyMatch(m -> m instanceof AiMessage ai && ai.hasToolExecutionRequests()
                    && ai.toolExecutionRequests().stream().anyMatch(r -> r.id() == null));
            if (nullId) {
                throw new IllegalArgumentException("400 Bad Request: messages[1].tool_calls[0].id is required");
            }
            return stepResult(LONG_ANSWER, List.of(), 0.0);
        });

        var result = executor().execute(cascade, messages(), "sys", Map.of(), agentTask(), memory, orchestrator, Map.of(), false, false, false);

        assertEquals(1, result.stepUsed(), "the escalation must still reach the strong model");
        assertEquals(3, seen.size());
        assertEquals(List.of(UserMessage.from("order a pizza")), seen.get(2), "the retry starts from the conversation alone");
        assertTrue(result.trace().stream().anyMatch(e -> e.containsKey("carriedToolExchangeRejected")), result.trace().toString());
    }

    @Test
    @DisplayName("a transient failure with a carried exchange is not retried without it")
    void transientFailureIsNotTreatedAsRejection() {
        assertFalse(CascadingModelExecutor.rejectedCarriedExchange(new RuntimeException("503 service unavailable"), placedOrderExchange()));
        assertFalse(CascadingModelExecutor.rejectedCarriedExchange(new IllegalArgumentException("400"), List.of()));
        assertTrue(CascadingModelExecutor.rejectedCarriedExchange(new IllegalArgumentException("400"), placedOrderExchange()));
    }

    /**
     * m5: a step that times out after running tools still spent that money. The
     * ceiling now sees it (tracked cost delta around the step), so the next step
     * does not start on a budget that is already gone.
     */
    @Test
    @DisplayName("tool spend of a timed-out step counts toward maxCostPerRun")
    void timedOutStepToolSpendCounts() throws Exception {
        var cascade = twoStepCascade();
        cascade.getSteps().get(0).setTimeoutMs(200L);
        cascade.setMaxCostPerRun(0.01);
        when(orchestrator.conversationToolCost(any())).thenReturn(0.0, 0.05);
        when(orchestrator.executeIfToolsEnabled(any(), any(), anyList(), any(), any(), any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            Thread.sleep(5_000);
            return stepResult("late", List.of(), 0.0);
        });

        assertThrows(LifecycleException.class, () -> executor().execute(cascade, messages(), "sys", Map.of(), agentTask(), memory, orchestrator,
                Map.of(), false, false, false));

        capturedStepMessages(1);
    }

    @Test
    @DisplayName("a legacy (non-agent) cascade is unaffected: no tools, nothing carried")
    void legacyCascadeUnaffected() throws LifecycleException {
        var legacyTask = agentTask();
        legacyTask.setEnableBuiltInTools(false);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(LONG_ANSWER)).build());

        var result = executor().execute(twoStepCascade(), messages(), "sys", Map.of(), legacyTask, memory, orchestrator, Map.of(), false, false,
                false);

        assertEquals(1, result.stepUsed());
        assertFalse(result.trace().getFirst().containsKey("carriedToolMessages"));
    }
}
