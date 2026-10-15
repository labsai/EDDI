/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The LLM tool path of an agent-test conversation: every tool source is
 * answered by the suite before any executor runs, and the trace still records
 * the call and its (mocked) result.
 */
@DisplayName("ToolLoopRunner — agent-test mocks")
class ToolLoopRunnerAgentTestMockTest {

    private static final String TEST_USER = AgentTestConversations.userIdFor("0123456789abcdef01234567", 0, 1);

    private ToolExecutionService service;
    private ToolLoopRunner runner;
    private final AtomicInteger executed = new AtomicInteger();
    private final Map<String, ToolExecutor> executors = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = mock(ToolExecutionService.class);
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
        executors.put("getAvailability", (req, id) -> "real " + executed.incrementAndGet());
        executors.put("createBooking", (req, id) -> "real " + executed.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        ToolMockRegistry.unregister(TEST_USER);
    }

    private String call(String user, String tool, String args, List<Map<String, Object>> trace) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(user);
        var request = ToolExecutionRequest.builder().id("c1").name(tool).arguments(args).build();
        var task = new LlmConfiguration.Task();
        task.setId("t");
        return runner.executeSingleToolCallResult(request, memory, trace, executors, Map.of(), Map.of(), Map.of(tool, "http"), 100, null,
                "conv-1", false, false, false, task, false, List.of(), new ArrayList<>());
    }

    @Test
    @DisplayName("a mocked call never reaches the executor; the trace shows the mocked result")
    void mockedCallSkipsTheExecutor() {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("getAvailability", Map.of("city", "Vienna"), new MockResponse(200, Map.of("rooms", 3)))), null)));
        var trace = new ArrayList<Map<String, Object>>();

        String result = call(TEST_USER, "getAvailability", "{\"city\":\"Vienna\"}", trace);

        assertEquals("{\"httpCode\":200,\"body\":{\"rooms\":3}}", result);
        assertEquals(0, executed.get());
        verifyNoInteractions(service);
        assertEquals("tool_call", trace.get(0).get("type"));
        assertEquals(Boolean.TRUE, trace.get(1).get("mocked"));
    }

    @Test
    @DisplayName("an unmocked call in a test conversation is NOT_EXECUTED — never run")
    void unmockedCallIsNotExecuted() {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null)));

        String result = call(TEST_USER, "createBooking", "{}", new ArrayList<>());

        assertEquals(ToolMockRegistry.NOT_EXECUTED_JSON, result);
        assertEquals(0, executed.get());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("an ordinary conversation is untouched by the hook")
    void ordinaryConversationRunsTheTool() {
        when(service.executeToolWrapped(any(ToolInvocation.class), any(), any(), any(), any(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyInt(),
                anyInt())).thenReturn("real result");

        String result = call("alice", "createBooking", "{}", new ArrayList<>());

        assertTrue(result.contains("real result"), result);
    }
}
