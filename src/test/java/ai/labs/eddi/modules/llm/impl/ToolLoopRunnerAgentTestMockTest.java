/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.configs.shared.TurnDeadline;
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

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        // The 6-argument overload (conversation id first) is what the loop calls.
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
        executors.put("getAvailability", (_, _) -> "real " + executed.incrementAndGet());
        executors.put("createBooking", (_, _) -> "real " + executed.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        ToolMockRegistry.unregister(TEST_USER);
    }

    /**
     * As {@link #call}, but with caching and cost tracking switched ON for the call
     * and the tool opted in to caching — so that any {@code false} reaching the
     * service can only come from the mock path turning them off.
     */
    private String callWithAccounting(String user, String tool, String args, List<Map<String, Object>> trace) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(user);
        var request = ToolExecutionRequest.builder().id("c1").name(tool).arguments(args).build();
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setToolCacheScopes(Map.of(tool, "conversation"));
        return runner.executeSingleToolCallResult(request, memory, trace, executors, Map.of(), Map.of(), Map.of(tool, "http"), 100, null,
                "conv-1", false, true, true, task, false, List.of(), new ArrayList<>());
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

    /**
     * An httpcall tool's executor stores the response and runs its postResponse
     * property instructions; a mock answered around it would skip both. The mock is
     * handed to it instead — through the service (rate limiting, the turn deadline)
     * but uncached and unpriced — and the call is recorded once.
     */
    @Test
    @DisplayName("a mock for a MockAwareToolExecutor goes through that executor, uncached and unpriced, and is recorded once")
    void mockGoesThroughAMockAwareExecutor() {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("getAvailability", null, new MockResponse(200, Map.of("rooms", 3)))), null));
        ToolMockRegistry.register(TEST_USER, session);
        var handed = new ArrayList<ToolMockRegistry.MockResult>();
        executors.put("getAvailability", new MockAwareToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return "real " + executed.incrementAndGet();
            }

            @Override
            public String executeMocked(ToolExecutionRequest request, ToolMockRegistry.MockResult mock) {
                handed.add(mock);
                return "{\"httpCode\":200,\"body\":{\"rooms\":3},\"postResponseRan\":true}";
            }
        });
        when(service.executeToolWrapped(any(ToolInvocation.class), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyInt(),
                anyInt())).thenAnswer(i -> i.<Supplier<String>>getArgument(4).get());
        var trace = new ArrayList<Map<String, Object>>();

        String result = callWithAccounting(TEST_USER, "getAvailability", "{}", trace);

        assertTrue(result.contains("postResponseRan"), result);
        assertEquals(1, handed.size());
        assertEquals(200, handed.get(0).httpCode());
        assertEquals(0, executed.get(), "the live path never ran");
        assertEquals(1, session.calls().size());
        verify(service).executeToolWrapped(any(ToolInvocation.class), any(), any(), any(), any(), anyBoolean(), eq(false), eq(false), anyInt(),
                anyInt());
        // Control: the same call outside a test conversation is cached and priced, so
        // the two false flags above come from the mock, not from this setup.
        callWithAccounting("alice", "getAvailability", "{}", new ArrayList<>());
        verify(service).executeToolWrapped(any(ToolInvocation.class), any(), any(), any(), any(), anyBoolean(), eq(true), eq(true), anyInt(),
                anyInt());
        assertEquals(Boolean.TRUE, trace.get(trace.size() - 1).get("mocked"));
    }

    /**
     * A mock handed to an executor still has to pass the loop's own gates. One a
     * gate refuses was never made, so it must not count as a call of the turn.
     */
    @Test
    @DisplayName("a mock refused by the turn deadline before the executor runs is not recorded")
    void mockRefusedByAGateIsNotRecorded() {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("getAvailability", null, new MockResponse(200, "3"))), null));
        ToolMockRegistry.register(TEST_USER, session);
        executors.put("getAvailability", new MockAwareToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return "real " + executed.incrementAndGet();
            }

            @Override
            public String executeMocked(ToolExecutionRequest request, ToolMockRegistry.MockResult mock) {
                return "mocked " + executed.incrementAndGet();
            }
        });
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(TEST_USER);
        when(memory.getTurnDeadline()).thenReturn(TurnDeadline.of(Clock.systemUTC(), 0L, 1_000L, 0L));
        var task = new LlmConfiguration.Task();
        task.setId("t");

        var result = runner.executeToolCall(ToolExecutionRequest.builder().id("c1").name("getAvailability").arguments("{}").build(), memory,
                new ArrayList<>(), executors, Map.of(), Map.of(), Map.of("getAvailability", "http"), 100, null, "conv-1", false, false,
                false, task, false, List.of(), new ArrayList<>());

        assertTrue(result.notExecuted(), result.raw());
        assertEquals(0, executed.get());
        assertEquals(List.of(), session.calls());
    }

    @Test
    @DisplayName("an unmocked call is refused before a MockAwareToolExecutor ever runs")
    void unmockedCallNeverReachesAMockAwareExecutor() {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null, List.of(), null)));
        executors.put("createBooking", new MockAwareToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return "real " + executed.incrementAndGet();
            }

            @Override
            public String executeMocked(ToolExecutionRequest request, ToolMockRegistry.MockResult mock) {
                return "mocked " + executed.incrementAndGet();
            }
        });

        String result = call(TEST_USER, "createBooking", "{}", new ArrayList<>());

        assertEquals(ToolMockRegistry.NOT_EXECUTED_JSON, result);
        assertEquals(0, executed.get());
        verifyNoInteractions(service);
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

    @Test
    @DisplayName("a mock counts as the tool's answer; a refusal counts as a call that never ran")
    void mockIsExecutedRefusalIsNot() {
        ToolMockRegistry.register(TEST_USER, new ToolMockRegistry.Session(new AgentTestSuite("s", null, null, null,
                List.of(new ToolMock("getAvailability", null, new MockResponse(403, "forbidden"))), null)));
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(TEST_USER);
        var task = new LlmConfiguration.Task();
        task.setId("t");

        var mocked = runner.executeToolCall(ToolExecutionRequest.builder().id("c1").name("getAvailability").arguments("{}").build(), memory,
                new ArrayList<>(), executors, Map.of(), Map.of(), Map.of("getAvailability", "http"), 100, null, "conv-1", false, false,
                false, task, false, List.of(), new ArrayList<>());
        var refused = runner.executeToolCall(ToolExecutionRequest.builder().id("c2").name("createBooking").arguments("{}").build(), memory,
                new ArrayList<>(), executors, Map.of(), Map.of(), Map.of("createBooking", "http"), 100, null, "conv-1", false, false,
                false, task, false, List.of(), new ArrayList<>());

        assertFalse(mocked.notExecuted());
        assertTrue(refused.notExecuted());
        assertEquals(0, executed.get());
    }
}
