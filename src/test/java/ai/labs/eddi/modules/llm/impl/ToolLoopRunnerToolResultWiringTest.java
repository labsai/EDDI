/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
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
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live wiring of the {@code tool_result} stream event: a real tool loop run
 * ({@link ToolLoopRunner#executeWithTools}) must tell the conversation's event
 * sink {@code onToolCall} when a call starts and {@code onToolResult} when it
 * finishes. {@code ToolLoopRunnerToolResultEventTest} covers the payload built
 * by the static helper; this covers that the loop actually calls it.
 */
@DisplayName("ToolLoopRunner — tool_result is emitted by the live loop")
class ToolLoopRunnerToolResultWiringTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private ToolExecutionService toolExecutionService;
    private ToolCostTracker tracker;
    private ToolResponseTruncator truncator;
    private ConversationEventSink sink;
    private LlmConfiguration.Task task;
    private AgentOrchestrator.ToolSetup setup;
    private final AtomicInteger toolRuns = new AtomicInteger();

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
        sink = mock(ConversationEventSink.class);

        task = new LlmConfiguration.Task();
        task.setId("wiring-task");
        task.setMaxToolContextTokens(-1);
        task.setEnableToolCaching(false);
        task.setEnableCostTracking(false);
        task.setMaxToolIterations(5);

        ToolExecutor lookup = (request, memoryId) -> {
            toolRuns.incrementAndGet();
            return "found it";
        };
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
        lenient().when(memory.getEventSink()).thenReturn(sink);
        lenient().when(memory.getConversationCostCeilingUsd()).thenReturn(OptionalDouble.empty());
        IConversationStepStack steps = mock(IConversationStepStack.class);
        lenient().when(steps.getExactDataPerStep(MemoryKeys.AUDIT_COST)).thenReturn(List.of());
        lenient().when(memory.getAllSteps()).thenReturn(steps);
        return memory;
    }

    private void runLoop() throws Exception {
        var runner = new ToolLoopRunner(toolExecutionService, truncator, null, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
        runner.executeWithTools(oneToolCall(), null, List.of(UserMessage.from("go")), setup, task, memory(), null, -1, 0, null);
    }

    @Test
    @DisplayName("a successful call is announced, then reported ok with its result")
    void successfulCall() throws Exception {
        runLoop();

        assertEquals(1, toolRuns.get());
        InOrder order = inOrder(sink);
        order.verify(sink).onToolCall("lookup");
        order.verify(sink).onToolResult(eq("lookup"), any(), eq("ok"), anyLong(), eq("found it"));
    }

    @Test
    @DisplayName("a call the budget refuses is still announced, then reported not ok without running the tool")
    void budgetRefusedCall() throws Exception {
        task.setMaxBudgetPerConversation(1.0);
        task.setEnforceBudget(true);
        when(tracker.isWithinBudget(eq(CONVERSATION_ID), anyDouble())).thenReturn(false);

        runLoop();

        assertEquals(0, toolRuns.get());
        InOrder order = inOrder(sink);
        order.verify(sink).onToolCall("lookup");
        order.verify(sink).onToolResult(eq("lookup"), any(), not(eq("ok")), anyLong(), contains("Budget exceeded"));
        verify(sink, never()).onToolResult(eq("lookup"), any(), eq("ok"), anyLong(), anyString());
    }
}
