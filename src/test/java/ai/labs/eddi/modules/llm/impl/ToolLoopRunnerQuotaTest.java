/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.time.ZoneId;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.quota.InMemoryToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaRules;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.config;
import static ai.labs.eddi.modules.llm.tools.quota.ToolQuotaConfigValidatorTest.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code toolQuotas} through the real execution pipeline: the quota is counted
 * when — and only when — the tool really runs, for every tool source.
 */
@DisplayName("ToolLoopRunner — toolQuotas")
class ToolLoopRunnerQuotaTest {

    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long DAY_START = ToolQuotaRules.windowStart(Instant.parse("2026-11-02T10:00:00Z"), Duration.ofDays(1));

    private ToolLoopRunner runner;
    private LlmConfiguration.Task task;
    private InMemoryToolQuotaStore store;
    private IConversationMemory memory;
    private final Instant[] now = {Instant.parse("2026-11-02T10:00:00Z")};

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

        store = new InMemoryToolQuotaStore();
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        lenient().when(converter.convert(any())).thenReturn(Map.of());
        var quotaService = new ToolQuotaService(store, mock(ITemplatingEngine.class), converter, new SimpleMeterRegistry(), clock);

        ToolExecutionService service = new ToolExecutionService();
        setField(service, "cacheService", cache);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", new SimpleMeterRegistry());
        setField(service, "quotaService", quotaService);
        service.init();

        var truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));

        runner = new ToolLoopRunner(service, truncator, null, null, null, null, null, new ToolResultGuardrail(new SimpleMeterRegistry()));
        task = new LlmConfiguration.Task();
        task.setId("quota");
        memory = memory("user-1");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static IConversationMemory memory(String userId) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(userId);
        when(memory.getAgentId()).thenReturn(AGENT);
        when(memory.getConversationId()).thenReturn("conv-1");
        return memory;
    }

    private ToolQuotaKey key(String counter) {
        return new ToolQuotaKey(AGENT, counter, "user-1", DAY_START);
    }

    private static ToolExecutionRequest request(String tool, String args) {
        return ToolExecutionRequest.builder().id("c-" + args.hashCode()).name(tool).arguments(args).build();
    }

    /**
     * One call through the shared pipeline (the one the live loop and the HITL
     * resume both use).
     */
    private ToolLoopRunner.ToolCallResult call(String tool, String source, String args, ToolExecutor executor, boolean caching, int rateLimit) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put(tool, executor);
        return runner.executeToolCall(request(tool, args), memory, new ArrayList<>(), executors, Map.of(), Map.of(), Map.of(tool, source),
                rateLimit, null, "conv-1", true, caching, false, task, false, List.of(), new ArrayList<>());
    }

    private ToolLoopRunner.ToolCallResult call(String tool, String source, String args, ToolExecutor executor) {
        return call(tool, source, args, executor, false, 100);
    }

    @Test
    @DisplayName("a task without toolQuotas never reads the store")
    void noQuotaNoStoreRead() {
        var ran = new AtomicInteger();
        call("updateAction", "http", "{\"a\":1}", (req, id) -> "ok " + ran.incrementAndGet());
        assertEquals(1, ran.get());
        assertEquals(0, store.calls.get());
    }

    @Test
    @DisplayName("an HTTP tool is counted when it runs, and refused with the tool unrun once the limit is reached")
    void httpToolCountedThenRefused() {
        task.setToolQuotas(config("updateAction", rule(2, "P1D", null)));
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "{\"httpCode\":200,\"body\":\"" + ran.incrementAndGet() + "\"}";

        assertFalse(call("updateAction", "http", "{\"n\":1}", executor).notExecuted());
        assertFalse(call("updateAction", "http", "{\"n\":2}", executor).notExecuted());
        var third = call("updateAction", "http", "{\"n\":3}", executor);

        assertEquals(2, ran.get(), "the third call never reached the tool");
        assertTrue(third.notExecuted(), "the loop must know the tool did not run");
        assertTrue(third.governed().contains("Not run: the daily limit for this tool (2) is reached"), third.governed());
        assertEquals(2, store.peek(key("tool:updateAction")));
    }

    @Test
    @DisplayName("a cache hit is not counted")
    void cacheHitNotCounted() {
        task.setToolQuotas(config("calculate", rule(5, "P1D", null)));
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "42 #" + ran.incrementAndGet();

        // builtin tools are cached by default
        call("calculate", "builtin", "{\"expression\":\"6*7\"}", executor, true, 100);
        call("calculate", "builtin", "{\"expression\":\"6*7\"}", executor, true, 100);
        call("calculate", "builtin", "{\"expression\":\"6*7\"}", executor, true, 100);

        assertEquals(1, ran.get());
        assertEquals(1, store.peek(key("tool:calculate")), "three calls, one real execution, one count");
    }

    @Test
    @DisplayName("a rate-limited call is not counted")
    void rateLimitedNotCounted() {
        task.setToolQuotas(config("updateAction", rule(5, "P1D", null)));
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "{\"httpCode\":200,\"n\":" + ran.incrementAndGet() + "}";

        call("updateAction", "http", "{\"n\":1}", executor, false, 1);
        var limited = call("updateAction", "http", "{\"n\":2}", executor, false, 1);

        assertTrue(limited.governed().contains("Rate limit exceeded"), limited.governed());
        assertEquals(1, ran.get());
        assertEquals(1, store.peek(key("tool:updateAction")), "the rate-limited call took no slot");
    }

    @Test
    @DisplayName("an HTTP non-2xx result gives the slot back; a 2xx keeps it")
    void httpFailureReleases() {
        task.setToolQuotas(config("updateAction", rule(5, "P1D", null)));

        call("updateAction", "http", "{\"n\":1}", (req, id) -> "{\"httpCode\":500,\"body\":\"upstream\"}");
        assertEquals(0, store.peek(key("tool:updateAction")), "5xx released");

        call("updateAction", "http", "{\"n\":2}", (req, id) -> "{\"httpCode\":403,\"body\":\"no\"}");
        assertEquals(0, store.peek(key("tool:updateAction")), "4xx released");

        call("updateAction", "http", "{\"n\":3}", (req, id) -> "{\"error\":\"connection refused\"}");
        assertEquals(0, store.peek(key("tool:updateAction")), "transport failure released");

        call("updateAction", "http", "{\"n\":4}", (req, id) -> "{\"httpCode\":201,\"body\":\"created\"}");
        assertEquals(1, store.peek(key("tool:updateAction")), "2xx kept");
    }

    @Test
    @DisplayName("a tool that throws gives the slot back")
    void exceptionReleases() {
        task.setToolQuotas(config("updateAction", rule(5, "P1D", null)));
        var result = call("updateAction", "http", "{\"n\":1}", (req, id) -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(result.governed().startsWith("Error") || result.governed().contains("failed"), result.governed());
        assertEquals(0, store.peek(key("tool:updateAction")));
    }

    @Test
    @DisplayName("a timed-out call keeps its count: the abandoned worker may still finish the side effect")
    void timeoutKeepsTheCount() {
        task.setToolQuotas(config("updateAction", rule(5, "P1D", null)));
        task.setDefaultToolTimeoutMs(100);

        var result = call("updateAction", "http", "{\"n\":1}", (req, id) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });

        assertTrue(result.governed().contains("timed out"), result.governed());
        assertEquals(1, store.peek(key("tool:updateAction")));
    }

    @Test
    @DisplayName("a refusal is not cached: after the window resets the same call runs")
    void refusalIsNotCached() {
        task.setToolQuotas(config("calculate", rule(1, "P1D", null)));
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "result " + ran.incrementAndGet();

        call("calculate", "builtin", "{\"expression\":\"1+1\"}", executor, true, 100);
        var refused = call("calculate", "builtin", "{\"expression\":\"2+2\"}", executor, true, 100);
        assertTrue(refused.notExecuted());

        now[0] = Instant.parse("2026-11-03T00:00:01Z");
        var again = call("calculate", "builtin", "{\"expression\":\"2+2\"}", executor, true, 100);

        assertFalse(again.notExecuted(), again.governed());
        assertEquals("result 2", again.raw(), "served by the tool, not by a cached refusal");
    }

    @Test
    @DisplayName("fail closed: with the counter store down the tool is not run and the turn does not fail")
    void storeDownRefuses() {
        task.setToolQuotas(config("updateAction", rule(5, "P1D", null)));
        store.failing = true;
        var ran = new AtomicInteger();

        var result = call("updateAction", "http", "{\"n\":1}", (req, id) -> "ok " + ran.incrementAndGet());

        assertEquals(0, ran.get());
        assertTrue(result.notExecuted());
        assertTrue(result.governed().contains("could not be checked"), result.governed());
    }

    @Test
    @DisplayName("built-in slug and dispatch name both bind, dispatch name first")
    void slugResolution() {
        task.setToolQuotas(config("websearch", rule(1, "P1D", null)));
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "hit " + ran.incrementAndGet();
        Map<String, ToolExecutor> executors = new HashMap<>();
        executors.put("searchWeb", executor);
        // searchWeb is dispatched under that name but configured under the slug
        // "websearch"
        for (int i = 0; i < 2; i++) {
            runner.executeToolCall(request("searchWeb", "{\"q\":\"" + i + "\"}"), memory, new ArrayList<>(), executors, Map.of(),
                    Map.of("searchWeb", "websearch"), Map.of("searchWeb", "builtin"), 100, null, "conv-1", true, false, false, task, false,
                    List.of(), new ArrayList<>());
        }
        assertEquals(1, ran.get());
        assertEquals(1, store.peek(key("tool:websearch")));
    }

    @Test
    @DisplayName("a batch of 5 parallel calls with 3 left runs the tool exactly 3 times")
    void parallelBatchRunsExactlyTheRemaining() throws Exception {
        task.setToolQuotas(config("updateAction", rule(10, "P1D", null)));
        store.set(key("tool:updateAction"), 7);
        var ran = new AtomicInteger();
        ToolExecutor executor = (req, id) -> "{\"httpCode\":200,\"n\":" + ran.incrementAndGet() + "}";

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ToolLoopRunner.ToolCallResult>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                go.await();
                return call("updateAction", "http", "{\"n\":" + n + "}", executor);
            }));
        }
        go.countDown();
        int refused = 0;
        for (var result : results) {
            if (result.get().notExecuted()) {
                refused++;
            }
        }
        pool.shutdown();

        assertEquals(3, ran.get(), "exactly the three that were left");
        assertEquals(2, refused);
        assertEquals(10, store.peek(key("tool:updateAction")));
    }

    @Test
    @DisplayName("a tool that no rule names is neither counted nor looked up")
    void quotaDoesNotApplyToUnconfiguredTools() {
        task.setToolQuotas(config("somethingElse", rule(1, "P1D", null)));
        var ran = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            call("updateAction", "http", "{\"n\":" + i + "}", (req, id) -> "ok " + ran.incrementAndGet());
        }
        assertEquals(3, ran.get());
        assertEquals(0, store.calls.get(), "a tool no rule names is not looked up");
    }
}
