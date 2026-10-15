/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolLoopRunner — name-only tool-call observer")
class ToolLoopRunnerToolCallObserverTest {

    private String run(IConversationMemory memory) {
        var service = mock(ToolExecutionService.class);
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        var runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put("lookup", (req, id) -> "ok");
        var request = ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{\"secret\":\"hunter2\"}").build();
        var task = new LlmConfiguration.Task();
        task.setId("t");
        return runner.executeSingleToolCallResult(request, memory, new ArrayList<>(), executors, Map.of(), Map.of(), Map.of(), 100, null,
                "conv-1", false, false, false, task, false, List.of(), new ArrayList<>());
    }

    @Test
    void observerReceivesTheToolName_andNothingElse() {
        var seen = new ArrayList<String>();
        IConversationMemory memory = mock(IConversationMemory.class);
        Consumer<String> observer = seen::add;
        when(memory.getToolCallObserver()).thenReturn(observer);

        run(memory);

        assertEquals(List.of("lookup"), seen);
    }

    @Test
    void nullObserver_isSafe() {
        IConversationMemory memory = mock(IConversationMemory.class);
        assertDoesNotThrow(() -> run(memory));
    }

    @Test
    void throwingObserver_doesNotBreakTheToolLoop() {
        IConversationMemory memory = mock(IConversationMemory.class);
        Consumer<String> observer = n -> {
            throw new IllegalStateException("boom");
        };
        when(memory.getToolCallObserver()).thenReturn(observer);
        assertDoesNotThrow(() -> run(memory));
    }
}
