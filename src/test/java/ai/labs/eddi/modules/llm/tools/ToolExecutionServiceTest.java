/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.caching.CacheFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ToolExecutionService Tests")
class ToolExecutionServiceTest {

    /**
     * Stand-in scope tag; the resolution table itself is tested in
     * ToolCacheServiceTest.
     */
    private static final String SCOPE = "u:0123456789abcdef0123456789abcdef";

    private ToolExecutionService service;

    @Mock
    private ToolCacheService cacheService;

    @Mock
    private ToolRateLimiter rateLimiter;

    @Mock
    private ToolCostTracker costTracker;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        service = new ToolExecutionService();
        meterRegistry = new SimpleMeterRegistry();

        // Inject mocks via reflection (field injection)
        setField(service, "cacheService", cacheService);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", meterRegistry);

        service.init();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== executeToolWrapped ====================

    @Nested
    @DisplayName("executeToolWrapped")
    class ExecuteToolWrappedTests {

        @Test
        @DisplayName("should execute tool successfully with all features enabled")
        void success() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "tool result",
                    true, true, true, 60);

            assertEquals("tool result", result);
            verify(cacheService).put(SCOPE, ToolInvocation.of("testTool"), "args", "tool result");
            verify(costTracker).trackToolCall(ToolInvocation.of("testTool"), "conv-1");
        }

        @Test
        @DisplayName("should return cached result when available")
        void cachedResult() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn("cached");

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "should not be called",
                    true, true, true, 60);

            assertEquals("cached", result);
            verify(cacheService, never()).put(nullable(String.class), any(ToolInvocation.class), anyString(), anyString());
        }

        @Test
        @DisplayName("should return error when rate limited")
        void rateLimited() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(false);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "should not run",
                    true, true, true, 60);

            assertTrue(result.contains("Rate limit exceeded"));
            verify(cacheService, never()).get(nullable(String.class), anyString(), anyString());
        }

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void rateLimitingDisabled() {
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "result",
                    false, true, true, 60);

            assertEquals("result", result);
            verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        }

        @Test
        @DisplayName("should skip caching when disabled")
        void cachingDisabled() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "result",
                    true, false, true, 60);

            assertEquals("result", result);
            verify(cacheService, never()).get(nullable(String.class), anyString(), anyString());
            verify(cacheService, never()).put(nullable(String.class), any(ToolInvocation.class), anyString(), anyString());
        }

        @Test
        @DisplayName("should skip cost tracking when disabled")
        void costTrackingDisabled() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "result",
                    true, true, false, 60);

            verify(costTracker, never()).trackToolCall(nullable(ToolInvocation.class), nullable(String.class));
        }

        /**
         * Pins the {@code conversationId != null} half of the cost-tracking guard.
         * <p>
         * The matcher MUST be {@code nullable(String.class)}: the only invocation this
         * test could ever observe is {@code trackToolCall(invocation, null)}, and
         * {@code anyString()} does not match null — with it the verification matches
         * zero invocations whether the guard exists or not, and drops the guard
         * silently. Without the guard {@code ToolCostTracker} would
         * {@code computeIfAbsent(null, …)} on a {@code ConcurrentHashMap} and every
         * tool call would come back to the model as "Error executing tool: null".
         */
        @Test
        @DisplayName("should skip cost tracking when conversationId is null")
        void nullConversationIdSkipsCostTracking() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, null,
                    () -> "result",
                    true, true, true, 60);

            assertEquals("result", result, "an unattributable call must still return the tool's own result");
            verify(costTracker, never()).trackToolCall(nullable(ToolInvocation.class), nullable(String.class));
        }

        @Test
        @DisplayName("should return error message when tool throws exception")
        void toolThrowsException() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> {
                        throw new RuntimeException("tool failed");
                    },
                    true, true, true, 60);

            assertTrue(result.contains("Error executing tool"));
            assertTrue(result.contains("tool failed"));
        }

        @Test
        @DisplayName("should work with all features disabled")
        void allFeaturesDisabled() {
            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> "plain result",
                    false, false, false, 60);

            assertEquals("plain result", result);
        }

        @Test
        @DisplayName("should handle exception without message")
        void exceptionWithoutMessage() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", SCOPE, "conv-1",
                    () -> {
                        throw new NullPointerException();
                    },
                    true, true, true, 60);

            assertTrue(result.contains("Error executing tool"));
            assertTrue(result.contains("NullPointerException"));
        }

        @Test
        @DisplayName("should record success metrics")
        void recordsSuccessMetrics() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "myTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "myTool", "args", SCOPE, "conv-1",
                    () -> "ok",
                    true, true, false, 60);

            var successCounter = meterRegistry.find("eddi.tool.execution.success")
                    .tag("tool", "myTool").counter();
            assertNotNull(successCounter);
            assertEquals(1.0, successCounter.count());
        }

        @Test
        @DisplayName("should record failure metrics on error")
        void recordsFailureMetrics() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "myTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "myTool", "args", SCOPE, "conv-1",
                    () -> {
                        throw new RuntimeException("fail");
                    },
                    true, true, false, 60);

            var failureCounter = meterRegistry.find("eddi.tool.execution.failure")
                    .tag("tool", "myTool").counter();
            assertNotNull(failureCounter);
            assertEquals(1.0, failureCounter.count());
        }

        @Test
        @DisplayName("should record rate limit metrics when denied")
        void recordsRateLimitMetrics() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(false);

            service.executeToolWrapped(
                    "myTool", "args", SCOPE, "conv-1",
                    () -> "nope",
                    true, true, false, 60);

            var rateLimitCounter = meterRegistry.find("eddi.tool.execution.ratelimited")
                    .tag("tool", "myTool").counter();
            assertNotNull(rateLimitCounter);
            assertEquals(1.0, rateLimitCounter.count());
        }

        @Test
        @DisplayName("should record cached metrics when cache hit")
        void recordsCachedMetrics() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "myTool", "args")).thenReturn("cached-value");

            service.executeToolWrapped(
                    "myTool", "args", SCOPE, "conv-1",
                    () -> "should not run",
                    true, true, false, 60);

            var cachedCounter = meterRegistry.find("eddi.tool.execution.cached")
                    .tag("tool", "myTool").counter();
            assertNotNull(cachedCounter);
            assertEquals(1.0, cachedCounter.count());
        }
    }

    // ==================== canonical vs dispatch name ====================

    /**
     * A built-in tool has two names — the {@code @Tool} method the model dispatches
     * on ({@code searchWeb}) and the slug it is configured under
     * ({@code websearch}) — and this class is the boundary where the two must be
     * used for different things. Getting that split wrong in either direction is a
     * real defect: canonicalising the cache key makes three different searches
     * share one entry, and dispatching the price lookup on the method name (the
     * shipped behaviour) prices every built-in at $0.00.
     */
    @Nested
    @DisplayName("canonical name routing")
    class CanonicalNameTests {

        private static final ToolInvocation SEARCH_WEB = new ToolInvocation("searchWeb", "websearch", null);

        @Test
        @DisplayName("prices under the canonical slug, keyed under the dispatch name")
        void pricesUnderSlugKeysUnderDispatchName() {
            when(rateLimiter.tryAcquire("searchWeb", 60)).thenReturn(true);
            when(cacheService.get(SCOPE, "searchWeb", "args")).thenReturn(null);

            var result = service.executeToolWrapped(SEARCH_WEB, "args", SCOPE, "conv-1",
                    () -> "hits", true, true, true, 60);

            assertEquals("hits", result);
            // The cache key must stay on the dispatch name — searchNews and
            // searchWikipedia canonicalise to the same slug and would collide.
            verify(cacheService).get(SCOPE, "searchWeb", "args");
            verify(cacheService).put(SCOPE, SEARCH_WEB, "args", "hits");
            // The price, however, is a property of the tool, so the whole invocation
            // (carrying the slug) goes to the tracker.
            verify(costTracker).trackToolCall(SEARCH_WEB, "conv-1");
        }

        @Test
        @DisplayName("rate limits the dispatch name, never the slug")
        void rateLimitBucketIsPerDispatchName() {
            when(rateLimiter.tryAcquire("searchWeb", 30)).thenReturn(true);

            service.executeToolWrapped(SEARCH_WEB, "args", SCOPE, "conv-1",
                    () -> "hits", true, false, false, 30);

            // {"websearch": 30} configures the LIMIT; the BUCKET stays per method, so
            // searchWeb/searchNews/searchWikipedia get 30/min each, not 30 between them.
            verify(rateLimiter).tryAcquire("searchWeb", 30);
            verify(rateLimiter, never()).tryAcquire("websearch", 30);
        }

        @Test
        @DisplayName("reports metrics under the dispatch name so dashboards keep working")
        void metricsUseDispatchName() {
            when(rateLimiter.tryAcquire("searchWeb", 60)).thenReturn(true);

            service.executeToolWrapped(SEARCH_WEB, "args", SCOPE, "conv-1",
                    () -> "hits", true, false, false, 60);

            assertNotNull(meterRegistry.find("eddi.tool.execution.success").tag("tool", "searchWeb").counter());
            assertNull(meterRegistry.find("eddi.tool.execution.success").tag("tool", "websearch").counter());
        }

        @Test
        @DisplayName("carries the operator price override through to the tracker")
        void priceOverrideIsCarried() {
            var priced = new ToolInvocation("searchWeb", "websearch", 0.05);
            when(rateLimiter.tryAcquire("searchWeb", 60)).thenReturn(true);

            service.executeToolWrapped(priced, "args", SCOPE, "conv-1",
                    () -> "hits", true, false, true, 60);

            verify(costTracker).trackToolCall(priced, "conv-1");
        }

        @Test
        @DisplayName("the legacy String overload behaves like an identity invocation")
        void legacyOverloadDelegates() {
            when(rateLimiter.tryAcquire("plainTool", 60)).thenReturn(true);

            service.executeToolWrapped("plainTool", "args", SCOPE, "conv-1",
                    () -> "ok", true, false, true, 60);

            verify(costTracker).trackToolCall(ToolInvocation.of("plainTool"), "conv-1");
        }
    }

    // ==================== null cache scope tag ====================

    /**
     * A {@code null} cache scope tag means the caller could not derive any usable
     * identity for this tool call. The cache must then be skipped on BOTH sides —
     * substituting a placeholder tag would put every unattributable call back into
     * one shared partition, which is the cross-user leak this scoping exists to
     * close.
     */
    @Nested
    @DisplayName("null cache scope tag bypasses the cache")
    class NullScopeTagTests {

        @Test
        @DisplayName("does not read the cache")
        void nullScope_noCacheRead() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            var result = service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, true, true, 60);

            assertEquals("fresh result", result);
            // anyString() would NOT match the null first argument and this verification
            // would pass vacuously; nullable() matches it.
            verify(cacheService, never()).get(nullable(String.class), anyString(), anyString());
        }

        @Test
        @DisplayName("does not write the cache")
        void nullScope_noCacheWrite() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, true, true, 60);

            verify(cacheService, never()).put(nullable(String.class), any(ToolInvocation.class), anyString(), anyString());
        }

        @Test
        @DisplayName("a stale cached value can never be served when the scope tag is null")
        void nullScope_stubbedCacheIsIgnored() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            lenient().when(cacheService.get(nullable(String.class), anyString(), anyString())).thenReturn("SOMEONE ELSES RESULT");

            var result = service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, true, true, 60);

            assertEquals("fresh result", result);
        }

        @Test
        @DisplayName("records the eddi.tool.cache.bypassed meter")
        void nullScope_recordsBypassMeter() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, true, true, 60);

            var bypassed = meterRegistry.find("eddi.tool.cache.bypassed").tag("tool", "testTool").counter();
            assertNotNull(bypassed);
            assertEquals(1.0, bypassed.count());
        }

        @Test
        @DisplayName("no bypass meter when caching was disabled anyway")
        void cachingDisabled_noBypassMeter() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, false, true, 60);

            assertNull(meterRegistry.find("eddi.tool.cache.bypassed").tag("tool", "testTool").counter());
        }

        @Test
        @DisplayName("rate limiting and cost tracking still run")
        void nullScope_otherControlsUnaffected() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);

            service.executeToolWrapped(
                    "testTool", "args", null, "conv-1",
                    () -> "fresh result",
                    true, true, true, 60);

            verify(rateLimiter).tryAcquire("testTool", 60);
            verify(costTracker).trackToolCall(ToolInvocation.of("testTool"), "conv-1");
        }
    }

    // ==================== null tool arguments ====================

    /**
     * A tool call may legitimately arrive with {@code null} arguments.
     * {@code ToolExecutionRequest.arguments()} is an unvalidated field with no
     * default (see the assertion in
     * {@link #nullArgumentsAreWhatLangchain4jHandsUs}), and langchain4j's OpenAI
     * chat-completions mapper passes the wire value straight through, so any
     * provider that omits {@code function.arguments} for a zero-argument call
     * produces one. {@code AgentOrchestrator} forwards
     * {@code toolRequest.arguments()} here unguarded.
     *
     * <p>
     * These tests use the REAL {@link ToolCacheService} rather than the mock the
     * outer class injects: the defect lived in its private {@code buildKey}, and a
     * mocked cache service cannot reach it. {@code buildKey} dereferenced the
     * arguments, so the NPE was raised inside {@code executeToolWrapped}'s try
     * block and came back to the model as an "Error executing tool" string — BEFORE
     * the tool ran. The tool call therefore failed with caching on (the default)
     * and succeeded with caching off.
     * </p>
     */
    @Nested
    @DisplayName("null tool arguments still reach the tool")
    class NullArgumentsTests {

        private ToolExecutionService cachingService;
        private AtomicInteger toolRuns;

        @BeforeEach
        void wireRealCacheService() throws Exception {
            ToolCacheService realCacheService = new ToolCacheService();
            setField(realCacheService, "cacheFactory", new CacheFactory());
            setField(realCacheService, "meterRegistry", new SimpleMeterRegistry());
            realCacheService.init();
            realCacheService.clear();

            cachingService = new ToolExecutionService();
            setField(cachingService, "cacheService", realCacheService);
            setField(cachingService, "rateLimiter", rateLimiter);
            setField(cachingService, "costTracker", costTracker);
            setField(cachingService, "meterRegistry", new SimpleMeterRegistry());
            cachingService.init();

            toolRuns = new AtomicInteger();
        }

        /**
         * The provenance of the null: langchain4j's own type, not a synthetic value.
         */
        @Test
        @DisplayName("langchain4j yields null arguments for a request built without them")
        void nullArgumentsAreWhatLangchain4jHandsUs() {
            var request = ToolExecutionRequest.builder().id("call_1").name("datetime").build();

            assertNull(request.arguments(),
                    "ToolExecutionRequest.arguments() has no default; AgentOrchestrator forwards it verbatim");
        }

        @Test
        @DisplayName("a zero-argument tool call executes instead of erroring out")
        void nullArgumentsExecuteTheTool() {
            var request = ToolExecutionRequest.builder().id("call_1").name("datetime").build();

            String result = cachingService.executeToolWrapped(
                    ToolInvocation.of(request.name()), request.arguments(), SCOPE, "conv-1",
                    () -> {
                        toolRuns.incrementAndGet();
                        return "2026-07-23T09:00:00Z";
                    },
                    false, true, false, 60);

            assertEquals("2026-07-23T09:00:00Z", result,
                    "with caching enabled the cache lookup must not swallow the call");
            assertEquals(1, toolRuns.get(), "the tool itself must have run exactly once");
        }

        @Test
        @DisplayName("the result of a zero-argument call is cached and served back")
        void nullArgumentsAreCacheable() {
            var request = ToolExecutionRequest.builder().id("call_1").name("datetime").build();

            String first = cachingService.executeToolWrapped(
                    ToolInvocation.of(request.name()), request.arguments(), SCOPE, "conv-1",
                    () -> "run-" + toolRuns.incrementAndGet(), false, true, false, 60);
            String second = cachingService.executeToolWrapped(
                    ToolInvocation.of(request.name()), request.arguments(), SCOPE, "conv-1",
                    () -> "run-" + toolRuns.incrementAndGet(), false, true, false, 60);

            assertEquals("run-1", first);
            assertEquals("run-1", second, "the second call must be served from the cache, so the write worked too");
            assertEquals(1, toolRuns.get(), "a cached zero-argument call must not re-run the tool");
        }
    }

    // ==================== getCostTracker ====================

    @Nested
    @DisplayName("getCostTracker")
    class GetCostTrackerTests {

        @Test
        @DisplayName("should return the injected cost tracker")
        void returnsInjected() {
            assertSame(costTracker, service.getCostTracker());
        }
    }

    // ==================== removed parallel-execution machinery
    // ====================

    /**
     * Tripwire for the deletion of the reflection-based parallel tool machinery
     * ({@code executeToolsParallel}, {@code executeToolsParallelAndWait} and the
     * {@code executeTool(Object, Method, …)} they wrapped).
     *
     * <p>
     * None of it was reachable from production: the live dispatch path is
     * langchain4j's {@code ToolExecutor.execute(ToolExecutionRequest, memoryId)},
     * which hands over a {@code (name, jsonArguments)} pair, while those methods
     * required an {@code (instance, java.lang.reflect.Method, Object[])} triple
     * that MCP, A2A and dynamic tools cannot produce at all. Its only callers were
     * its own tests, so the deleted coverage guarded a path production could never
     * enter.
     * </p>
     *
     * <p>
     * These assertions fail if any of it is reintroduced by a merge or a revert —
     * including the always-zero {@code eddi.tool.execution.parallel} meter, which
     * used to be registered eagerly in {@link ToolExecutionService#init()} and so
     * appeared on {@code /q/metrics} advertising a feature that did not exist.
     * </p>
     */
    @Nested
    @DisplayName("dead parallel machinery stays deleted")
    class ParallelMachineryRemovedTests {

        @Test
        @DisplayName("no executeToolsParallel* method survives on the public API")
        void noParallelMethods() {
            assertTrue(Arrays.stream(ToolExecutionService.class.getMethods())
                    .noneMatch(m -> m.getName().startsWith("executeToolsParallel")),
                    "executeToolsParallel/AndWait were deleted as unreachable; reintroducing them "
                            + "re-adds a dispatch path no live tool call can reach");
        }

        @Test
        @DisplayName("no reflection-based executeTool(Object, Method, ...) survives")
        void noReflectionExecuteTool() {
            assertTrue(Arrays.stream(ToolExecutionService.class.getMethods())
                    .noneMatch(m -> m.getName().equals("executeTool")),
                    "the reflection overload was deleted with its only caller (executeToolsParallel); "
                            + "executeToolWrapped is the single entry point");
        }

        @Test
        @DisplayName("startup registers no eddi.tool.execution.parallel* meter")
        void noParallelMetersRegistered() {
            // setUp() already ran init(); every remaining meter is created lazily at a
            // reachable increment site, so nothing parallel-shaped may exist.
            var parallelMeters = meterRegistry.getMeters().stream()
                    .map(m -> m.getId().getName())
                    .filter(name -> name.startsWith("eddi.tool.execution.parallel"))
                    .toList();

            assertEquals(List.of(), parallelMeters,
                    "these series could only ever report zero and were removed from /q/metrics, "
                            + "docs/metrics.md, the monitoring guide and the Grafana dashboard");
        }
    }
}
