/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.ToolDisplay;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolDisplayConfig;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The live tool events: {@code tool_call} announces name, call id and, only
 * when the designer configured it, a display; {@code tool_result} closes every
 * call exactly once with a status. Neither ever carries arguments or results
 * beyond the allow-listed fields.
 */
@DisplayName("ToolLoopRunner — tool_call / tool_result events")
class ToolLoopRunnerToolEventsTest {

    /**
     * Everything a sink was told, as flat strings, so a leak is one contains()
     * away.
     */
    static class RecordingSink implements ConversationEventSink {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<ToolDisplay> displays = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onToolCall(String toolName, String callId, ToolDisplay display) {
            events.add("call:" + toolName + ":" + callId);
            displays.add(display);
        }

        @Override
        public void onToolResult(String toolName, String callId, String status, long durationMs) {
            events.add("result:" + toolName + ":" + callId + ":" + status);
        }

        @Override
        public void onTaskStart(TaskId taskId, String taskType, int index) {
        }

        @Override
        public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
        }

        @Override
        public void onToken(String token) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
        }
    }

    private static ToolExecutionService serviceAnswering(Function<InvocationOnMock, Object> onExecute, boolean withinBudget) {
        var costTracker = mock(ToolCostTracker.class);
        when(costTracker.isWithinBudget(anyString(), anyDouble())).thenReturn(withinBudget);
        return mock(ToolExecutionService.class, Mockito.withSettings().defaultAnswer(inv -> {
            if (inv.getMethod().getName().equals("executeToolWrapped")) {
                return onExecute.apply(inv);
            }
            if (inv.getMethod().getName().equals("getCostTracker")) {
                return costTracker;
            }
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        }));
    }

    private static ToolLoopRunner runnerOver(ToolExecutionService service) {
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        return new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
    }

    private static IConversationMemory memoryWith(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(sink);
        return memory;
    }

    private static ToolLoopRunner.ToolCallResult call(ToolLoopRunner runner, IConversationMemory memory, ToolExecutionRequest request,
                                                      LlmConfiguration.Task task, List<Map<String, Object>> trace, Double maxBudget) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put(request.name(), (req, id) -> "unused");
        return runner.executeToolCall(request, memory, trace, executors, Map.of(), Map.of(), Map.of(), 100, maxBudget, "conv-1", false, false,
                false, task, false, List.of(), new ArrayList<>(), ToolLoopRunner.NO_RUN_SPEND);
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        return task;
    }

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    @Test
    @DisplayName("a successful call announces name and call id, then closes with ok - and neither carries the arguments or the result")
    void successfulCall() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "RESULT-SECRET-VALUE", true));

        call(runner, memoryWith(sink), request("c1", "lookup", "{\"password\":\"ARGUMENT-SECRET-VALUE\"}"), task(), new ArrayList<>(), null);

        assertEquals(List.of("call:lookup:c1", "result:lookup:c1:ok"), sink.events);
        assertNull(sink.displays.getFirst(), "no toolDisplay configured: no display");
        String everything = String.join("|", sink.events);
        assertFalse(everything.contains("ARGUMENT-SECRET-VALUE"));
        assertFalse(everything.contains("RESULT-SECRET-VALUE"));
    }

    @Test
    @DisplayName("a tool that reports an error closes with error")
    void failingCall() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "Error: the backend said no", true));

        call(runner, memoryWith(sink), request("c1", "lookup", "{}"), task(), new ArrayList<>(), null);

        assertEquals(List.of("call:lookup:c1", "result:lookup:c1:error"), sink.events);
    }

    @Test
    @DisplayName("a tool that throws still closes the call, with error, and the exception goes on")
    void throwingCall() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> {
            throw new IllegalStateException("boom");
        }, true));

        assertThrows(IllegalStateException.class,
                () -> call(runner, memoryWith(sink), request("c1", "lookup", "{}"), task(), new ArrayList<>(), null));

        assertEquals(List.of("call:lookup:c1", "result:lookup:c1:error"), sink.events);
    }

    @Test
    @DisplayName("a call refused by the budget closes with not_executed")
    void refusedByBudget() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "never reached", false));

        call(runner, memoryWith(sink), request("c1", "lookup", "{}"), task(), new ArrayList<>(), 1.0);

        assertEquals(List.of("call:lookup:c1", "result:lookup:c1:not_executed"), sink.events);
    }

    @Test
    @DisplayName("an unknown tool is closed too")
    void unknownTool() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "unused", true));
        // no executor registered for the name
        runner.executeToolCall(request("c1", "ghost", "{}"), memoryWith(sink), new ArrayList<>(), new HashMap<>(), Map.of(), Map.of(), Map.of(),
                100, null, "conv-1", false, false, false, task(), false, List.of(), new ArrayList<>(), ToolLoopRunner.NO_RUN_SPEND);

        assertEquals(List.of("call:ghost:c1", "result:ghost:c1:error"), sink.events);
    }

    @Test
    @DisplayName("parallel calls: every call is announced once and closed once")
    void parallelCalls() throws Exception {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));
        var memory = memoryWith(sink);
        var trace = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        int calls = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                String id = "c" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    call(runner, memory, request(id, "lookup", "{}"), task(), trace, null);
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < calls; i++) {
            String id = "c" + i;
            assertEquals(1, sink.events.stream().filter(e -> e.equals("call:lookup:" + id)).count(), id);
            assertEquals(1, sink.events.stream().filter(e -> e.equals("result:lookup:" + id + ":ok")).count(), id);
        }
        assertEquals(calls * 2, sink.events.size());
    }

    @Test
    @DisplayName("a sink that throws never breaks the tool loop")
    void throwingSink() {
        var sink = new RecordingSink() {
            @Override
            public void onToolCall(String toolName, String callId, ToolDisplay display) {
                throw new IllegalStateException("sink down");
            }

            @Override
            public void onToolResult(String toolName, String callId, String status, long durationMs) {
                throw new IllegalStateException("sink down");
            }
        };
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));

        var result = assertDoesNotThrow(
                () -> call(runner, memoryWith(sink), request("c1", "lookup", "{}"), task(), new ArrayList<>(), null));
        assertNotNull(result);
    }

    @Test
    @DisplayName("a sink that only knows onToolCall(String) still hears the call")
    void legacySinkStillWorks() {
        var legacy = new ArrayList<String>();
        // implements only what the interface requires, plus the name-only onToolCall:
        // the three-argument form is the interface default
        ConversationEventSink sink = new ConversationEventSink() {
            @Override
            public void onToolCall(String toolName) {
                legacy.add(toolName);
            }

            @Override
            public void onTaskStart(TaskId taskId, String taskType, int index) {
            }

            @Override
            public void onTaskComplete(TaskId taskId, String taskType, long durationMs, Map<String, Object> summary) {
            }

            @Override
            public void onToken(String token) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));

        call(runner, memoryWith(sink), request("c1", "lookup", "{}"), task(), new ArrayList<>(), null);

        assertEquals(List.of("lookup"), legacy);
    }

    // ── trace identity ──

    @Test
    @DisplayName("the stored trace keeps the call id on tool_call and tool_result")
    void traceKeepsCallId() {
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));
        var trace = new ArrayList<Map<String, Object>>();

        call(runner, memoryWith(null), request("call-77", "lookup", "{}"), task(), trace, null);

        assertEquals("tool_call", trace.get(0).get("type"));
        assertEquals("call-77", trace.get(0).get("callId"));
        assertEquals("tool_result", trace.get(1).get("type"));
        assertEquals("call-77", trace.get(1).get("callId"));
    }

    @Test
    @DisplayName("the stored trace keeps the call id on a tool_error")
    void traceKeepsCallIdOnError() {
        var runner = runnerOver(serviceAnswering(inv -> "never reached", false));
        var trace = new ArrayList<Map<String, Object>>();

        call(runner, memoryWith(null), request("call-78", "lookup", "{}"), task(), trace, 1.0);

        var error = trace.stream().filter(s -> "tool_error".equals(s.get("type"))).findFirst().orElseThrow();
        assertEquals("call-78", error.get("callId"));
    }

    // ── toolDisplay ──

    private static LlmConfiguration.Task taskWithDisplay(String tool, String label, String... fields) {
        var task = task();
        var config = new ToolDisplayConfig();
        config.setLabel(label);
        config.setFields(List.of(fields));
        task.setToolDisplay(Map.of(tool, config));
        return task;
    }

    @Test
    @DisplayName("toolDisplay: the label and only the allow-listed scalar fields are shown")
    void displayShowsOnlyAllowListed() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));
        String args = "{\"title\":\"Renew the contract\",\"password\":\"NOT-LISTED-VALUE\",\"nested\":{\"a\":1},\"count\":3,\"flag\":true}";

        call(runner, memoryWith(sink), request("c1", "createAction", args),
                taskWithDisplay("createAction", "Creating an action", "title", "nested", "count", "flag", "missing"),
                new ArrayList<>(), null);

        var display = sink.displays.getFirst();
        assertEquals("Creating an action", display.displayName());
        assertEquals(Map.of("title", "Renew the contract", "count", "3", "flag", "true"), display.fields());
        assertFalse(display.fields().toString().contains("NOT-LISTED-VALUE"));
    }

    @Test
    @DisplayName("toolDisplay: a label alone shows no argument values")
    void labelOnly() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));
        var task = task();
        var config = new ToolDisplayConfig();
        config.setLabel("Looking something up");
        task.setToolDisplay(Map.of("lookup", config));

        call(runner, memoryWith(sink), request("c1", "lookup", "{\"q\":\"private\"}"), task, new ArrayList<>(), null);

        assertEquals("Looking something up", sink.displays.getFirst().displayName());
        assertTrue(sink.displays.getFirst().fields().isEmpty());
    }

    @Test
    @DisplayName("toolDisplay: a tool without an entry gets no display")
    void otherToolsGetNoDisplay() {
        var sink = new RecordingSink();
        var runner = runnerOver(serviceAnswering(inv -> "ok", true));

        call(runner, memoryWith(sink), request("c1", "lookup", "{\"title\":\"x\"}"), taskWithDisplay("createAction", "Creating", "title"),
                new ArrayList<>(), null);

        assertNull(sink.displays.getFirst());
    }

    @Test
    @DisplayName("toolDisplay: a value is redacted, then cut to 80 characters with an ellipsis")
    void valueIsRedactedAndCut() {
        String longValue = "x".repeat(200);
        ToolDisplay display = ToolDisplayResolver.resolve(taskWithDisplay("t", "L", "title").getToolDisplay(), "t", "t",
                "{\"title\":\"" + longValue + "\"}");
        String shown = display.fields().get("title");
        assertEquals(80, shown.length());
        assertTrue(shown.endsWith(String.valueOf((char) 0x2026)));
        assertTrue(shown.startsWith("x".repeat(79)));

        // exactly at the limit is left alone
        String limit = "y".repeat(80);
        assertEquals(limit, ToolDisplayResolver.resolve(taskWithDisplay("t", "L", "title").getToolDisplay(), "t", "t",
                "{\"title\":\"" + limit + "\"}").fields().get("title"));
    }

    @Test
    @DisplayName("toolDisplay: a credential-shaped value never reaches the display")
    void secretIsRedacted() {
        String secret = "Bearer " + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6";
        ToolDisplay display = ToolDisplayResolver.resolve(taskWithDisplay("t", "L", "title").getToolDisplay(), "t", "t",
                "{\"title\":\"" + secret + "\"}");
        assertFalse(display.fields().get("title").contains("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"));
    }

    @Test
    @DisplayName("toolDisplay: the dispatch name wins over the built-in slug; the slug is found when the dispatch name is not")
    void dispatchNameWinsOverSlug() {
        var byDispatch = new ToolDisplayConfig();
        byDispatch.setLabel("by dispatch");
        var bySlug = new ToolDisplayConfig();
        bySlug.setLabel("by slug");
        Map<String, ToolDisplayConfig> both = Map.of("calculate", byDispatch, "calculator", bySlug);
        assertEquals("by dispatch", ToolDisplayResolver.resolve(both, "calculate", "calculator", "{}").displayName());
        assertEquals("by slug", ToolDisplayResolver.resolve(Map.of("calculator", bySlug), "calculate", "calculator", "{}").displayName());
        assertNull(ToolDisplayResolver.resolve(Map.of("other", bySlug), "calculate", "calculator", "{}"));
    }

    @Test
    @DisplayName("toolDisplay: unparsable arguments give a label and no fields")
    void unparsableArguments() {
        ToolDisplay display = ToolDisplayResolver.resolve(taskWithDisplay("t", "L", "title").getToolDisplay(), "t", "t", "not json");
        assertEquals("L", display.displayName());
        assertTrue(display.fields().isEmpty());
    }
}
