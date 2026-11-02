/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.hitl.tools.ClearedToolCalls;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent's {@code maxCostPerConversationUsd} between the rounds of a tool
 * loop: the task-level gate only sees a loop's cost once the loop has finished,
 * so the loop checks its own running spend before every further model call.
 */
@DisplayName("ToolLoopRunner — per-conversation cost ceiling")
class ToolLoopRunnerCostCeilingTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private ToolLoopRunner runner;
    private LlmConfiguration.Task task;
    private AgentOrchestrator.ToolSetup setup;
    private int modelCalls;
    private ChatModel model;

    private ToolExecutionService toolExecutionService;

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class),
                        nullable(String.class), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        var truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        runner = new ToolLoopRunner(toolExecutionService, truncator, null, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));

        task = new LlmConfiguration.Task();
        task.setId("ceiling-task");
        task.setMaxToolContextTokens(-1);
        task.setEnableToolCaching(false);
        task.setEnableCostTracking(false);
        task.setMaxToolIterations(10);
        // 1M input tokens per round at $0.01/1M: each round costs exactly $0.01.
        task.setInputPricePer1M(0.01);

        ToolExecutor lookup = (request, memoryId) -> "ok";
        var spec = ToolSpecification.builder().name("lookup").description("d").build();
        setup = new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("lookup", lookup), Map.of("lookup", "builtin"), List.of(spec),
                Map.of(), Map.of(), Map.of());

        // A model that never stops calling tools.
        model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                modelCalls++;
                var call = ToolExecutionRequest.builder().id("call_" + modelCalls).name("lookup").arguments("{}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).tokenUsage(new TokenUsage(1_000_000, 0)).build();
            }
        };
    }

    private IConversationMemory memory(Double ceiling, double... priorStepCosts) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        when(memory.getAgentId()).thenReturn("agent");
        when(memory.getUserId()).thenReturn("user");
        when(memory.getConversationCostCeilingUsd()).thenReturn(ceiling != null ? OptionalDouble.of(ceiling) : OptionalDouble.empty());
        IConversationStepStack steps = mock(IConversationStepStack.class);
        List<IData<Object>> costs = new ArrayList<>();
        for (double cost : priorStepCosts) {
            IData<Object> data = mock(IData.class);
            when(data.getResult()).thenReturn(cost);
            costs.add(data);
        }
        lenient().when(steps.getExactDataPerStep(MemoryKeys.AUDIT_COST)).thenReturn(costs);
        lenient().when(memory.getAllSteps()).thenReturn(steps);
        return memory;
    }

    private AgentOrchestrator.ExecutionResult run(IConversationMemory memory) throws Exception {
        return runner.executeWithTools(model, null, List.of(UserMessage.from("go")), setup, task, memory, null, -1, 0, null);
    }

    @Test
    @DisplayName("the loop stops before the model call that would start past the ceiling")
    void loopStopsAtTheCeiling() throws Exception {
        var result = run(memory(0.025));

        // $0.01, $0.02 are under $0.025; after the third round $0.03 is not.
        assertEquals(3, modelCalls);
        assertEquals(ToolLoopRunner.costCeilingReachedMessage(), result.response());
        assertTrue(result.trace().stream().anyMatch(t -> "cost_ceiling".equals(t.get("type"))), String.valueOf(result.trace()));
    }

    @Test
    @DisplayName("what earlier steps spent counts towards the ceiling")
    void priorSpendCounts() throws Exception {
        run(memory(0.025, 0.01, 0.01));

        assertEquals(1, modelCalls);
    }

    @Test
    @DisplayName("a scheduled fire's maxCostPerFire stops the loop like the agent's own ceiling")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void maxCostPerFireStopsTheLoop() throws Exception {
        // No agent ceiling; $0.05 spent by earlier steps; the fire may spend $0.025.
        IConversationMemory memory = memory(null, 0.05);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        IData<Object> allowance = mock(IData.class);
        when(allowance.getResult()).thenReturn(new Context(Context.ContextType.object, 0.025));
        when(step.getData("context:" + ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE)).thenReturn((IData) allowance);
        when(memory.getCurrentStep()).thenReturn(step);

        var result = run(memory);

        assertEquals(3, modelCalls);
        assertEquals(ToolLoopRunner.costCeilingReachedMessage(), result.response());
    }

    /**
     * A HITL resume's continuation: starts past iteration 0, after the approved
     * calls ran.
     */
    private String resume(IConversationMemory memory, double toolCostBefore) throws Exception {
        return runner.runToolCallLoop(model, List.of(UserMessage.from("go")), setup.builtInSpecs(), new ArrayList<>(), 1, toolCostBefore, setup,
                false,
                task, memory, null, -1, ClearedToolCalls.none(), 0, new TokenUsage[1], null, null, null);
    }

    @Test
    @DisplayName("a resume whose conversation is already spent does not send its first request")
    void resumeChecksBeforeItsFirstRequest() throws Exception {
        String response = resume(memory(0.025, 0.03), 0.0);

        assertEquals(0, modelCalls);
        assertEquals(ToolLoopRunner.costCeilingReachedMessage(), response);
    }

    @Test
    @DisplayName("what the approved calls of a resume charged counts towards the ceiling")
    void approvedCallCostCounts() throws Exception {
        // Nothing spent before the pause; the approved calls charged $0.03.
        var tracker = mock(ToolCostTracker.class);
        var metrics = mock(ToolCostTracker.ConversationCostMetrics.class);
        when(metrics.getTotalCost()).thenReturn(0.03);
        when(tracker.getConversationCosts(CONVERSATION_ID)).thenReturn(metrics);
        when(toolExecutionService.getCostTracker()).thenReturn(tracker);

        String response = resume(memory(0.025), 0.0);

        assertEquals(0, modelCalls);
        assertEquals(ToolLoopRunner.costCeilingReachedMessage(), response);
    }

    @Test
    @DisplayName("a resume under the ceiling runs as before")
    void resumeUnderTheCeilingRuns() throws Exception {
        resume(memory(0.025, 0.01), 0.0);

        // $0.01 before, then $0.02 after the first round; $0.03 after the second is not
        // under.
        assertEquals(2, modelCalls);
    }

    @Test
    @DisplayName("no ceiling: the loop runs to its iteration limit exactly as before")
    void noCeilingLeavesTheLoopAlone() throws Exception {
        var result = run(memory(null));

        assertEquals(10, modelCalls);
        assertTrue(result.trace().stream().noneMatch(t -> "cost_ceiling".equals(t.get("type"))));
    }
}
