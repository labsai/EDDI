/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolFailureException;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import ai.labs.eddi.modules.llm.tools.impl.ReadAttachmentTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tool-loop policies wired through the real {@link ToolExecutionService} and
 * {@link ToolCacheService}: conversation-bound tools and failures are never
 * cached, {@code discover_tools} activates from its untruncated result, and the
 * per-response / per-turn call caps answer every refused call.
 */
@DisplayName("ToolLoopRunner — cache, discovery and call-cap policies")
class ToolLoopRunnerToolPolicyTest {

    private ToolLoopRunner runner;
    private ToolResponseTruncator truncator;
    private LlmConfiguration.Task task;

    @BeforeEach
    void setUp() throws Exception {
        ToolCacheService cache = new ToolCacheService();
        setField(cache, "cacheFactory", new CacheFactory());
        setField(cache, "meterRegistry", new SimpleMeterRegistry());
        cache.init();

        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        setField(rateLimiter, "meterRegistry", new SimpleMeterRegistry());
        rateLimiter.init();

        ToolCostTracker costTracker = new ToolCostTracker();
        setField(costTracker, "meterRegistry", new SimpleMeterRegistry());
        costTracker.init();

        ToolExecutionService service = new ToolExecutionService();
        setField(service, "cacheService", cache);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", new SimpleMeterRegistry());
        service.init();

        truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));

        runner = new ToolLoopRunner(service, truncator, null, null, new ToolApprovalGate(), null, null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
        task = new LlmConfiguration.Task();
        task.setId("tool-policy");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static IConversationMemory memory(String conversationId) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.getAgentId()).thenReturn("agent-a");
        when(memory.getConversationId()).thenReturn(conversationId);
        return memory;
    }

    private String run(ToolExecutionRequest request, Map<String, ToolExecutor> executors, IConversationMemory memory, boolean lazy,
                       List<ToolSpecification> builtIns, List<ToolSpecification> active) {
        return runner.executeSingleToolCallResult(request, memory, new ArrayList<>(), executors, Map.of(), Map.of(),
                Map.of(request.name(), "builtin"), 100, null, memory.getConversationId(), false, true, false, task, lazy, builtIns, active);
    }

    private static ToolExecutor reflected(Object tool, String method) throws Exception {
        return ToolObjectReflector.executorFor(tool, tool.getClass().getMethod(method));
    }

    @Nested
    @DisplayName("cache")
    class CacheTests {

        @Test
        @DisplayName("listAttachments in conversation B never returns conversation A's files (same user)")
        void attachmentListIsNotSharedAcrossConversations() throws Exception {
            IAttachmentStore store = mock(IAttachmentStore.class);
            when(store.listAccessible("conv-A"))
                    .thenReturn(List.of(new IAttachmentStore.Attachment("ref-a", "alpha-A.txt", "text/plain", 3, "conv-A")));
            when(store.listAccessible("conv-B")).thenReturn(List.of());
            var request = ToolExecutionRequest.builder().id("c1").name("listAttachments").arguments("{}").build();

            String inA = run(request, Map.of("listAttachments", reflected(new ReadAttachmentTool(store, new AttachmentTextExtractor(1000),
                    "conv-A"), "listAttachments")), memory("conv-A"), false, List.of(), new ArrayList<>());
            String inB = run(request, Map.of("listAttachments", reflected(new ReadAttachmentTool(store, new AttachmentTextExtractor(1000),
                    "conv-B"), "listAttachments")), memory("conv-B"), false, List.of(), new ArrayList<>());

            assertTrue(inA.contains("alpha-A.txt"), inA);
            assertTrue(inB.contains("No attachments are available"), "conversation B saw: " + inB);
        }

        @Test
        @DisplayName("a typed failure is executed again on the next call, not served from the cache")
        void failureIsNotCached() throws Exception {
            FlakyTool tool = new FlakyTool();
            // Identical arguments on every call: they are what the cache key is built
            // from, so a cached failure would be served back for all three.
            var request = ToolExecutionRequest.builder().id("c1").name("fetch").arguments("{\"page\":\"https://example.com\"}").build();
            Map<String, ToolExecutor> executors = Map.of("fetch", ToolObjectReflector.executorFor(tool,
                    FlakyTool.class.getMethod("fetch")));

            String first = run(request, executors, memory("conv-1"), false, List.of(), new ArrayList<>());
            String second = run(request, executors, memory("conv-1"), false, List.of(), new ArrayList<>());
            String third = run(request, executors, memory("conv-1"), false, List.of(), new ArrayList<>());

            assertTrue(first.contains("Error: upstream unavailable"), first);
            assertTrue(second.contains("page content"), "the recovered call must run, was: " + second);
            assertTrue(third.contains("page content"), third);
            assertEquals(2, tool.calls.get(), "the success IS cached; only the failure was not");
        }
    }

    @Test
    @DisplayName("discover_tools activates from its untruncated result even when a response limit applies")
    void discoverToolsParsesUntruncatedResult() {
        when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> {
            String result = i.getArgument(1);
            return result.substring(0, Math.min(20, result.length()));
        });
        StringBuilder json = new StringBuilder("{\"tools\":[");
        List<ToolSpecification> builtIns = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            json.append(i > 0 ? "," : "").append("{\"name\":\"tool").append(i).append("\",\"description\":\"a long description\"}");
            builtIns.add(ToolSpecification.builder().name("tool" + i).description("d").build());
        }
        json.append("]}");
        var request = ToolExecutionRequest.builder().id("c1").name("discover_tools").arguments("{}").build();
        List<ToolSpecification> active = new ArrayList<>();

        run(request, Map.of("discover_tools", (req, id) -> json.toString()), memory("conv-1"), true, builtIns, active);

        assertEquals(30, active.size(), "every discovered tool is activated");
    }

    @Nested
    @DisplayName("call caps")
    class CallCapTests {

        private AiMessage response(int calls) {
            return AiMessage.from(IntStream.range(0, calls)
                    .mapToObj(i -> ToolExecutionRequest.builder().id("call_" + i).name("calculate").arguments("{}").build()).toList());
        }

        @Test
        @DisplayName("50 calls in one response: 20 admitted, 30 answered NOT_EXECUTED")
        void perResponseCap() {
            List<ChatMessage> messages = new ArrayList<>();
            List<Map<String, Object>> trace = new ArrayList<>();

            var call = response(50);
            var admission = ToolLoopRunner.admitWithinCallCaps(call, 20, 100, 0);
            assertTrue(messages.isEmpty(), "nothing is answered before the admitted calls ran");
            admission.answerRefused(call, messages, trace, null);
            var admitted = admission.admitted();

            assertEquals(20, admitted.size());
            assertEquals(30, messages.size(), "every refused call still gets its one result");
            var refusal = (ToolExecutionResultMessage) messages.getFirst();
            assertEquals("call_20", refusal.id());
            assertTrue(refusal.text().contains("NOT_EXECUTED") && refusal.text().contains("maxToolCallsPerIteration"), refusal.text());
            assertEquals(30, trace.stream().filter(t -> "tool_call_capped".equals(t.get("type"))).count());
        }

        @Test
        @DisplayName("through the loop: refused results follow the executed ones, so every result is in call order")
        void resultsStayInCallOrder() throws Exception {
            task.setMaxToolCallsPerIteration(2);
            task.setMaxToolContextTokens(-1);
            task.setEnableToolCaching(false);
            AtomicInteger executed = new AtomicInteger();
            ToolExecutor calc = (request, memoryId) -> "result " + executed.incrementAndGet();
            var spec = ToolSpecification.builder().name("calculate").description("d").build();
            var setup = new AgentOrchestrator.ToolSetup(List.of(spec), Map.of("calculate", calc), Map.of("calculate", "builtin"), List.of(spec),
                    Map.of(), Map.of(), Map.of());
            ChatModel model = mock(ChatModel.class);
            List<ChatRequest> requests = new ArrayList<>();
            when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                return requests.size() == 1
                        ? ChatResponse.builder().aiMessage(response(3)).build()
                        : ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            });

            var result = runner.executeWithTools(model, null, List.of(UserMessage.from("go")), setup, task, memory("conv-1"), null, -1, 0,
                    null);

            assertEquals("done", result.response());
            assertEquals(2, executed.get());
            List<String> resultIds = requests.get(1).messages().stream().filter(m -> m instanceof ToolExecutionResultMessage)
                    .map(m -> ((ToolExecutionResultMessage) m).id()).toList();
            assertEquals(List.of("call_0", "call_1", "call_2"), resultIds);
            var last = (ToolExecutionResultMessage) requests.get(1).messages().getLast();
            assertTrue(last.text().contains("NOT_EXECUTED"), last.text());
        }

        @Test
        @DisplayName("the per-turn cap counts the calls already made this turn")
        void perTurnCap() {
            List<ChatMessage> messages = new ArrayList<>();

            var call = response(10);
            var admission = ToolLoopRunner.admitWithinCallCaps(call, 20, 100, 95);
            admission.answerRefused(call, messages, new ArrayList<>(), null);
            var admitted = admission.admitted();

            assertEquals(5, admitted.size());
            assertTrue(((ToolExecutionResultMessage) messages.getFirst()).text().contains("maxToolCallsPerTurn"));
        }

        @Test
        @DisplayName("within the caps nothing is refused")
        void withinCaps() {
            List<ChatMessage> messages = new ArrayList<>();
            var admission = ToolLoopRunner.admitWithinCallCaps(response(3), 20, 100, 0);
            admission.answerRefused(response(3), messages, new ArrayList<>(), null);
            assertEquals(3, admission.admitted().size());
            assertTrue(messages.isEmpty());
        }

        @Test
        @DisplayName("defaults, the -1 opt-out, and the maxToolIterations ceiling")
        void capResolution() {
            assertEquals(20, ToolLoopRunner.effectiveCallCap(null, ToolLoopRunner.DEFAULT_MAX_TOOL_CALLS_PER_ITERATION));
            assertEquals(Integer.MAX_VALUE, ToolLoopRunner.effectiveCallCap(-1, 20));
            assertEquals(Integer.MAX_VALUE, ToolLoopRunner.effectiveCallCap(0, 20));
            assertEquals(7, ToolLoopRunner.effectiveCallCap(7, 20));
            assertEquals(2, ToolLoopRunner.admitWithinCallCaps(response(2), Integer.MAX_VALUE, Integer.MAX_VALUE, 500).admitted().size(),
                    "uncapped admits everything");

            var task = new LlmConfiguration.Task();
            assertEquals(10, ToolLoopRunner.effectiveMaxToolIterations(task));
            task.setMaxToolIterations(5_000);
            assertEquals(ToolLoopRunner.MAX_TOOL_ITERATIONS_CEILING, ToolLoopRunner.effectiveMaxToolIterations(task));
            task.setMaxToolIterations(3);
            assertEquals(3, ToolLoopRunner.effectiveMaxToolIterations(task));
        }

        @Test
        @DisplayName("tool_call entries in the trace are what the per-turn count resumes from")
        void countsTraceCalls() {
            List<Map<String, Object>> trace = new ArrayList<>(List.of(Map.of("type", "tool_call"), Map.of("type", "tool_result"),
                    Map.of("type", "tool_call")));
            assertEquals(2, ToolLoopRunner.countToolCalls(trace));
        }
    }

    /** A shared bean that fails once, then answers. */
    @ApplicationScoped
    public static class FlakyTool {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("fetch a page")
        public String fetch() {
            if (calls.incrementAndGet() == 1) {
                throw new ToolFailureException("Error: upstream unavailable");
            }
            return "page content";
        }
    }
}
