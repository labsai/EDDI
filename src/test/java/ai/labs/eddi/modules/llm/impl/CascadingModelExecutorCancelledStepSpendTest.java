/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A cascade step that times out while one of its tools is running: the cancel
 * interrupts the step, {@code ToolExecutionService} charges the interrupted
 * tool on the step's thread as it unwinds, and the cascade snapshots the step's
 * tool spend. The snapshot used to be taken straight after the cancel, racing
 * the unwind, so the tool's price was missing from the failed step's spend and
 * from {@code maxCostPerRun}.
 */
@DisplayName("CascadingModelExecutor — a cancelled step's tool charge is in its spend")
class CascadingModelExecutorCancelledStepSpendTest {

    private static final String LONG_ANSWER = "Here is a thorough and complete answer that easily clears the heuristic's length floor.";
    private static final double TOOL_PRICE = 0.5;
    /**
     * How long the interrupted step takes to charge — well inside the settle bound.
     */
    private static final long UNWIND_MS = 200;

    private final DoubleAdder conversationToolCost = new DoubleAdder();

    /**
     * Step 0 waits on a "tool" until it is interrupted, then charges it after a
     * short unwind — what ToolExecutionService does on an interrupted wait. Step 1
     * has no tools, so the cascade answers it in plain mode.
     */
    private final class ChargingOnInterruptOrchestrator implements IAgentOrchestrator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages,
                                                                       LlmConfiguration.Task task, IConversationMemory memory,
                                                                       ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                                                       int transcriptMaxBytes, JsonResponseFormatPolicy jsonPolicy) {
            if (calls.getAndIncrement() > 0) {
                return null;
            }
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                try {
                    Thread.sleep(UNWIND_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                conversationToolCost.add(TOOL_PRICE);
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("step abandoned");
        }

        @Override
        public AgentOrchestrator.ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task, IConversationMemory memory,
                                                                PendingToolCallBatch batch, HitlDecision decision, boolean toolHitlEnabled,
                                                                JsonResponseFormatPolicy jsonPolicy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double conversationToolCost(String conversationId) {
            return conversationToolCost.sum();
        }
    }

    @Test
    @DisplayName("the tool a timed-out step was waiting on is counted in the run's tool cost")
    void interruptedToolChargeIsInTheRunSpend() throws Exception {
        // A real implementation rather than a mock: the plain-mode step reaches the
        // model through ChatModel's default overloads, which a mock would not route.
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(LONG_ANSWER)).build();
            }
        };
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), anyMap())).thenReturn(model);
        IConversationMemory memory = mock(IConversationMemory.class);
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null,
                new CallerIdentityContext(null, null));

        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("sys"), UserMessage.from("search for it")));
        var result = executor.execute(twoStepCascade(), messages, "sys", Map.of(), agentTask(), memory, new ChargingOnInterruptOrchestrator(),
                Map.of(), false, false, false);

        assertEquals(1, result.stepUsed());
        assertEquals("timeout", result.trace().getFirst().get("status"), result.trace().toString());
        assertEquals(TOOL_PRICE, result.runToolCostUsd(), 1e-9,
                "the step's tool was charged as it unwound; the snapshot must not be taken before that");
    }

    private static LlmConfiguration.Task agentTask() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        task.setEnableBuiltInTools(true);
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
        cheap.setTimeoutMs(300L);
        var strong = new CascadeStep();
        strong.setType("openai");
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        cascade.setSteps(List.of(cheap, strong));
        return cascade;
    }
}
