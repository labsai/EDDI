/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which tool calls the result cache may answer, and how its entries are
 * partitioned, through the real {@link ToolExecutionService} and
 * {@link ToolCacheService}.
 * <p>
 * The NEW finding: HTTP, MCP and A2A tools were cached by default, so a second
 * identical POST within the TTL was answered from the cache and never sent.
 * M-T3: the key had no agent and no source, so same-named tools of two agents
 * serving one user answered each other.
 */
@DisplayName("ToolLoopRunner — tool result cache policy")
class ToolLoopRunnerCachePolicyTest {

    private ToolLoopRunner runner;
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

        var truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));

        runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
        task = new LlmConfiguration.Task();
        task.setId("cache-policy");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static IConversationMemory memory(String agentId) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.getAgentId()).thenReturn(agentId);
        return memory;
    }

    /**
     * Runs the tool twice with identical arguments; returns how often it really
     * executed.
     */
    private int executions(String toolName, String source, IConversationMemory first, IConversationMemory second) {
        AtomicInteger calls = new AtomicInteger();
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put(toolName, (req, memoryId) -> "result " + calls.incrementAndGet());
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("c1").name(toolName).arguments("{\"item\":\"pizza\"}").build();
        for (IConversationMemory memory : List.of(first, second)) {
            runner.executeSingleToolCallResult(request, memory, new ArrayList<>(), executors, Map.of(), Map.of(), Map.of(toolName, source), 100,
                    null, "conv-1", false, true, false, task, false, List.of(), new ArrayList<>());
        }
        return calls.get();
    }

    @Test
    @DisplayName("an HTTP tool is executed every time — a cache hit would skip a POST")
    void httpToolIsNotCachedByDefault() {
        assertEquals(2, executions("placeOrder", "http", memory("agent-a"), memory("agent-a")));
    }

    @Test
    @DisplayName("MCP and A2A tools are not cached by default either")
    void mcpAndA2aAreNotCachedByDefault() {
        assertEquals(2, executions("write_file", "mcp", memory("agent-a"), memory("agent-a")));
        assertEquals(2, executions("ask_peer", "a2a", memory("agent-a"), memory("agent-a")));
    }

    @Test
    @DisplayName("naming an HTTP tool in toolCacheScopes opts it into caching")
    void explicitOptInCachesHttpTool() {
        task.setToolCacheScopes(Map.of("lookupCustomer", "user"));
        assertEquals(1, executions("lookupCustomer", "http", memory("agent-a"), memory("agent-a")));
    }

    @Test
    @DisplayName("built-in tools keep caching by default")
    void builtinStillCached() {
        assertEquals(1, executions("calculate", "builtin", memory("agent-a"), memory("agent-a")));
    }

    @Test
    @DisplayName("M-T3: two agents serving one user do not share an opted-in tool's entry")
    void agentsDoNotShareEntries() {
        task.setToolCacheScopes(Map.of("lookup", "user"));
        assertEquals(2, executions("lookup", "http", memory("agent-a"), memory("agent-b")),
                "agent B's `lookup` is a different HTTP call than agent A's, whatever the name says");
    }

    @Test
    @DisplayName("namespacedScopeTag: source always, agent unless a global built-in")
    void namespacedScopeTag() {
        assertNull(ToolCacheService.namespacedScopeTag(null, "a", "http"));
        String userTag = ToolCacheService.resolveScopeTag("calc", "calculator", null, null, "u", "c");
        assertNotEquals(ToolCacheService.namespacedScopeTag(userTag, "agent-a", "builtin"),
                ToolCacheService.namespacedScopeTag(userTag, "agent-b", "builtin"));
        assertNotEquals(ToolCacheService.namespacedScopeTag(userTag, "agent-a", "builtin"),
                ToolCacheService.namespacedScopeTag(userTag, "agent-a", "mcp"));
        String globalTag = ToolCacheService.resolveScopeTag("calc", "calculator", null, "global", "u", "c");
        assertEquals(ToolCacheService.namespacedScopeTag(globalTag, "agent-a", "builtin"),
                ToolCacheService.namespacedScopeTag(globalTag, "agent-b", "builtin"),
                "a global built-in is the same tool for every agent — sharing it is what global means");
        assertNotEquals(ToolCacheService.namespacedScopeTag(globalTag, "agent-a", "http"),
                ToolCacheService.namespacedScopeTag(globalTag, "agent-b", "http"));
    }

    @Test
    @DisplayName("mayCache: built-ins and unknown sources yes, side-effecting sources only when named")
    void mayCache() {
        assertTrue(ToolCacheService.mayCache("builtin", "calc", "calculator", null));
        assertTrue(ToolCacheService.mayCache(null, "calc", "calc", null));
        assertFalse(ToolCacheService.mayCache("http", "post", "post", null));
        assertFalse(ToolCacheService.mayCache("mcp", "post", "post", Map.of("other", "user")));
        assertTrue(ToolCacheService.mayCache("a2a", "ask", "ask", Map.of("ask", "conversation")));
    }
}
