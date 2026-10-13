/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.MutableTestClock;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ToolLoopRunner — expired turn deadline")
class ToolLoopRunnerExpiredDeadlineTest {

    @Test
    @DisplayName("a tool is not run once the deadline has passed; the model gets an error result and the stop is counted")
    void expiredDeadlineSkipsTheTool() {
        var service = mock(ToolExecutionService.class);
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        var runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));

        var clock = new MutableTestClock();
        var deadline = TurnDeadline.of(clock, clock.millis(), 1_000, 0L);
        clock.advance(1_000);
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getTurnDeadline()).thenReturn(deadline);

        AtomicInteger executed = new AtomicInteger();
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put("lookup", (req, id) -> "result " + executed.incrementAndGet());
        var request = ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{}").build();
        var task = new LlmConfiguration.Task();
        task.setId("t");

        var meters = new SimpleMeterRegistry();
        Metrics.addRegistry(meters);
        try {
            String result = runner.executeSingleToolCallResult(request, memory, new ArrayList<>(), executors, Map.of(), Map.of(), Map.of(), 100,
                    null, "conv-1", false, false, false, task, false, List.of(), new ArrayList<>());

            assertEquals(0, executed.get(), "the tool must not run");
            verifyNoInteractions(service);
            assertTrue(result.contains("turn deadline has passed"), result);
            assertEquals(1.0, meters.counter("eddi.llm.turn.deadline.exceeded", "stage", "tool").count());
        } finally {
            Metrics.removeRegistry(meters);
        }
    }
}
