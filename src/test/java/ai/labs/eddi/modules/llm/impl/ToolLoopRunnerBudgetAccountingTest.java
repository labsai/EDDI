/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.MemoryKeys;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An enforced {@code maxBudgetPerConversation} is checked against the cost
 * tracker, so the tracker has to be charged for every call — also when
 * {@code enableCostTracking} is off, or the ceiling never moves.
 */
@DisplayName("ToolLoopRunner — an enforced tool budget is charged whatever enableCostTracking says")
class ToolLoopRunnerBudgetAccountingTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private ToolExecutionService toolExecutionService;
    private ToolResponseTruncator truncator;
    private LlmConfiguration.Task task;
    private AgentOrchestrator.ToolSetup setup;
    private ToolCostTracker tracker;

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(ToolExecutionService.class);
        lenient()
                .when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class),
                        nullable(String.class), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        tracker = mock(ToolCostTracker.class);
        lenient().when(tracker.isWithinBudget(anyString(), anyDouble())).thenReturn(true);
        lenient().when(toolExecutionService.getCostTracker()).thenReturn(tracker);
        truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));

        task = new LlmConfiguration.Task();
        task.setId("budget-task");
        task.setMaxToolContextTokens(-1);
        task.setEnableToolCaching(false);
        task.setEnableCostTracking(false);
        task.setMaxToolIterations(5);

        ToolExecutor lookup = (request, memoryId) -> "ok";
        var spec = ToolSpecification.builder().name("lookup").description("d").build();
        setup = new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("lookup", lookup), Map.of("lookup", "builtin"), List.of(spec),
                Map.of(), Map.of(), Map.of());
    }

    /** Calls the tool once, then answers. */
    private static ChatModel oneToolCall() {
        return new ChatModel() {
            private int calls;

            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (calls++ == 0) {
                    var call = ToolExecutionRequest.builder().id("call_1").name("lookup").arguments("{}").build();
                    return ChatResponse.builder().aiMessage(AiMessage.from(List.of(call))).build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            }
        };
    }

    private IConversationMemory memory() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn(CONVERSATION_ID);
        lenient().when(memory.getAgentId()).thenReturn("agent");
        lenient().when(memory.getConversationCostCeilingUsd()).thenReturn(OptionalDouble.empty());
        IConversationStepStack steps = mock(IConversationStepStack.class);
        lenient().when(steps.getExactDataPerStep(MemoryKeys.AUDIT_COST)).thenReturn(List.of());
        lenient().when(memory.getAllSteps()).thenReturn(steps);
        return memory;
    }

    private boolean trackCostPassed() throws Exception {
        var runner = new ToolLoopRunner(toolExecutionService, truncator, null, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
        runner.executeWithTools(oneToolCall(), null, List.of(UserMessage.from("go")), setup, task, memory(), null, -1, 0, null);
        var trackCost = ArgumentCaptor.forClass(Boolean.class);
        verify(toolExecutionService).executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), nullable(String.class),
                any(), anyBoolean(), anyBoolean(), trackCost.capture(), anyInt(), anyInt());
        return trackCost.getValue();
    }

    @Test
    @DisplayName("enableCostTracking=false with an enforced ceiling still charges the call")
    void enforcedCeilingIsCharged() throws Exception {
        task.setMaxBudgetPerConversation(1.0);
        task.setEnforceBudget(true);

        assertEquals(true, trackCostPassed());
    }

    @Test
    @DisplayName("enableCostTracking=false with no ceiling, or an unenforced one, charges nothing — as before")
    void noEnforcedCeilingNoCharge() throws Exception {
        task.setMaxBudgetPerConversation(1.0);
        task.setEnforceBudget(false);

        assertEquals(false, trackCostPassed());
    }

    @Test
    @DisplayName("a summarize truncation's model call is charged to an enforced ceiling too")
    void enforcedCeilingChargesTheSummarizer() throws Exception {
        task.setMaxBudgetPerConversation(1.0);
        task.setEnforceBudget(true);
        trackCostPassed();

        var spend = ArgumentCaptor.forClass(ToolResponseTruncator.SummarizerSpend.class);
        verify(truncator).truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), spend.capture());
        spend.getValue().charge(Map.of("inputTokens", 10, "outputTokens", 5));

        verify(tracker).trackToolCall(any(ToolInvocation.class), eq(CONVERSATION_ID));
    }
}
