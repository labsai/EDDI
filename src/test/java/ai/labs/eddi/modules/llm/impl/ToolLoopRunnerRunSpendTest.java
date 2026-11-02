/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool loop's one spend figure — its rounds at the prices of the model it
 * runs on, plus its tools — at every exit and check: a failure carries it, a
 * cascade step's prices apply to it, and the tenant check before a tool call
 * counts it.
 */
@DisplayName("ToolLoopRunner — the run's unrecorded spend")
class ToolLoopRunnerRunSpendTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private ToolExecutionService toolExecutionService;
    private ToolResponseTruncator truncator;
    private LlmConfiguration.Task task;
    private AgentOrchestrator.ToolSetup setup;
    private int modelCalls;

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class),
                        nullable(String.class), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));

        task = new LlmConfiguration.Task();
        task.setId("spend-task");
        task.setMaxToolContextTokens(-1);
        task.setEnableToolCaching(false);
        task.setEnableCostTracking(false);
        task.setMaxToolIterations(10);

        ToolExecutor lookup = (request, memoryId) -> "ok";
        var spec = ToolSpecification.builder().name("lookup").description("d").build();
        setup = new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("lookup", lookup), Map.of("lookup", "builtin"), List.of(spec),
                Map.of(), Map.of(), Map.of());
    }

    private ToolLoopRunner runner(TenantQuotaService quota) {
        return new ToolLoopRunner(toolExecutionService, truncator, quota, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
    }

    /**
     * Calls a tool on each of its first {@code toolRounds} rounds (1M input tokens
     * each), then fails non-retryably.
     */
    private ChatModel failingAfter(int toolRounds) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                modelCalls++;
                if (modelCalls > toolRounds) {
                    throw new IllegalArgumentException("context length exceeded");
                }
                var call = ToolExecutionRequest.builder().id("call_" + modelCalls).name("lookup").arguments("{}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).tokenUsage(new TokenUsage(1_000_000, 0)).build();
            }
        };
    }

    private IConversationMemory memory(Double ceiling) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        lenient().when(memory.getAgentId()).thenReturn("agent");
        lenient().when(memory.getConversationCostCeilingUsd())
                .thenReturn(ceiling != null ? OptionalDouble.of(ceiling) : OptionalDouble.empty());
        IConversationStepStack steps = mock(IConversationStepStack.class);
        lenient().when(steps.getExactDataPerStep(MemoryKeys.AUDIT_COST)).thenReturn(List.of());
        lenient().when(memory.getAllSteps()).thenReturn(steps);
        return memory;
    }

    @Test
    @DisplayName("a run that fails after priced rounds carries their spend on the failure")
    void failureCarriesTheRoundsSpend() {
        task.setInputPricePer1M(0.01);

        var failure = assertThrows(LifecycleException.class, () -> runner(null).executeWithTools(failingAfter(2), null,
                List.of(UserMessage.from("go")), setup, task, memory(null), null, -1, 0, null));

        var spend = UnrecordedSpend.drain(failure);
        assertEquals(0.02, spend.llmCostUsd(), 1e-9, "two answered rounds at $0.01 each");
        assertEquals(2_000_000L, ((Number) spend.tokenUsage().get("inputTokens")).longValue());
    }

    @Test
    @DisplayName("a cascade step's prices, not the task's, price the run — the mid-loop ceiling sees them")
    void recorderPricesApply() throws Exception {
        // The task is unpriced; the step costs $0.01 per round. Priced at the task's
        // rates the loop would never reach the $0.025 ceiling.
        var recorder = new ToolExchangeRecorder(new ModelPrices(0.01, null));
        var neverStops = failingAfter(Integer.MAX_VALUE);

        var result = runner(null).executeWithTools(neverStops, null, List.of(UserMessage.from("go")), setup, task, memory(0.025), null, -1, 0,
                null, recorder);

        assertEquals(ToolLoopRunner.costCeilingReachedMessage(), result.response());
        assertEquals(3, modelCalls);
        assertEquals(3_000_000L, ((Number) recorder.tokenUsage().get("inputTokens")).longValue(),
                "the recorder sees every answered round, for a caller that abandons the run");
    }

    @Test
    @DisplayName("the tenant check before a tool call counts the run's unsettled spend")
    void tenantCheckCountsTheRun() {
        task.setInputPricePer1M(0.01);
        TenantQuotaService quota = mock(TenantQuotaService.class);
        when(quota.getDefaultTenantId()).thenReturn("default");
        when(quota.checkCostBudget(eq("default"), anyDouble())).thenReturn(QuotaCheckResult.OK);

        assertThrows(LifecycleException.class, () -> runner(quota).executeWithTools(failingAfter(2), null, List.of(UserMessage.from("go")),
                setup, task, memory(null), null, -1, 0, null));

        var pending = ArgumentCaptor.forClass(Double.class);
        verify(quota, atLeastOnce()).checkCostBudget(eq("default"), pending.capture());
        // The first tool call comes after one $0.01 round, the second after two.
        assertEquals(List.of(0.01, 0.02), pending.getAllValues().stream().map(v -> Math.round(v * 1000) / 1000.0).toList());
        assertTrue(pending.getAllValues().stream().allMatch(v -> v > 0.0));
    }
}
