/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The tool-mode half of R5: {@link AgentOrchestrator#reaskFinalAnswer} is one
 * model call over a transcript that already holds the tool results. It never
 * executes a tool — not even one the re-asked model requests.
 */
@DisplayName("AgentOrchestrator.reaskFinalAnswer (R5)")
class AgentOrchestratorReaskFinalAnswerTest {

    @Test
    @DisplayName("no tool executor is invoked; the tool specs travel with the transcript; the cap override and token usage are honoured")
    void reaskIsASingleModelCallWithoutToolExecution() throws Exception {
        var toolRuns = new AtomicInteger();
        ToolExecutor executor = (request, memoryId) -> {
            toolRuns.incrementAndGet();
            return "ran";
        };
        var spec = ToolSpecification.builder().name("placeOrder").description("orders").build();
        var setup = new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("placeOrder", executor), Map.of(), List.of(spec), Map.of(), Map.of(),
                Map.of());
        var orchestrator = mock(AgentOrchestrator.class, CALLS_REAL_METHODS);
        doReturn(setup).when(orchestrator).buildToolSetup(any(), any());
        var task = new LlmConfiguration.Task();
        task.setId("t");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        var call = ToolExecutionRequest.builder().id("c1").name("placeOrder").arguments("{}").build();
        List<ChatMessage> transcript = List.of(UserMessage.from("order a pizza"), AiMessage.from(call), ToolExecutionResultMessage.from(call, "done"),
                UserMessage.from("Your previous reply could not be used: not JSON."));
        var model = FaultInjectingChatModel.script(Step.text("{\"ok\":true}"));

        var result = orchestrator.reaskFinalAnswer(model, transcript, 4096, task, mock(IConversationMemory.class), JsonResponseFormatPolicy.DISABLED);

        assertEquals("{\"ok\":true}", result.response());
        assertEquals(0, toolRuns.get());
        assertEquals(1, model.callCount());
        var request = model.requests().get(0);
        assertEquals(transcript, request.messages());
        assertEquals(List.of(spec), request.toolSpecifications());
        assertEquals(4096, request.parameters().maxOutputTokens());
        assertTrue(result.trace().isEmpty());
    }
}
