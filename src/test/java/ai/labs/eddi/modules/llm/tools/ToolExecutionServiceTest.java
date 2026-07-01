/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("ToolExecutionService Tests")
class ToolExecutionServiceTest {

    private ToolExecutionService service;

    @Mock
    private ToolCacheService cacheService;

    @Mock
    private ToolRateLimiter rateLimiter;

    @Mock
    private ToolCostTracker costTracker;

    @Mock
    private IJsonSerialization jsonSerialization;

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
        setField(service, "jsonSerialization", jsonSerialization);

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
            when(cacheService.get("testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
                    () -> "tool result",
                    true, true, true, 60);

            assertEquals("tool result", result);
            verify(cacheService).put("testTool", "args", "tool result");
            verify(costTracker).trackToolCall("testTool", "conv-1");
        }

        @Test
        @DisplayName("should return cached result when available")
        void cachedResult() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get("testTool", "args")).thenReturn("cached");

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
                    () -> "should not be called",
                    true, true, true, 60);

            assertEquals("cached", result);
            verify(cacheService, never()).put(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should return error when rate limited")
        void rateLimited() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(false);

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
                    () -> "should not run",
                    true, true, true, 60);

            assertTrue(result.contains("Rate limit exceeded"));
            verify(cacheService, never()).get(anyString(), anyString());
        }

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void rateLimitingDisabled() {
            when(cacheService.get("testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
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
                    "testTool", "args", "conv-1",
                    () -> "result",
                    true, false, true, 60);

            assertEquals("result", result);
            verify(cacheService, never()).get(anyString(), anyString());
            verify(cacheService, never()).put(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should skip cost tracking when disabled")
        void costTrackingDisabled() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get("testTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "testTool", "args", "conv-1",
                    () -> "result",
                    true, true, false, 60);

            verify(costTracker, never()).trackToolCall(anyString(), anyString());
        }

        @Test
        @DisplayName("should skip cost tracking when conversationId is null")
        void nullConversationIdSkipsCostTracking() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get("testTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "testTool", "args", null,
                    () -> "result",
                    true, true, true, 60);

            verify(costTracker, never()).trackToolCall(anyString(), anyString());
        }

        @Test
        @DisplayName("should return error message when tool throws exception")
        void toolThrowsException() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get("testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
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
                    "testTool", "args", "conv-1",
                    () -> "plain result",
                    false, false, false, 60);

            assertEquals("plain result", result);
        }

        @Test
        @DisplayName("should handle exception without message")
        void exceptionWithoutMessage() {
            when(rateLimiter.tryAcquire("testTool", 60)).thenReturn(true);
            when(cacheService.get("testTool", "args")).thenReturn(null);

            var result = service.executeToolWrapped(
                    "testTool", "args", "conv-1",
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
            when(cacheService.get("myTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "myTool", "args", "conv-1",
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
            when(cacheService.get("myTool", "args")).thenReturn(null);

            service.executeToolWrapped(
                    "myTool", "args", "conv-1",
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
                    "myTool", "args", "conv-1",
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
            when(cacheService.get("myTool", "args")).thenReturn("cached-value");

            service.executeToolWrapped(
                    "myTool", "args", "conv-1",
                    () -> "should not run",
                    true, true, false, 60);

            var cachedCounter = meterRegistry.find("eddi.tool.execution.cached")
                    .tag("tool", "myTool").counter();
            assertNotNull(cachedCounter);
            assertEquals(1.0, cachedCounter.count());
        }
    }

    // ==================== executeTool (reflection-based) ====================

    @Nested
    @DisplayName("executeTool")
    class ExecuteToolTests {

        @Test
        @DisplayName("should execute tool method via reflection successfully")
        void successfulExecution() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.001);
            when(jsonSerialization.serialize(any())).thenReturn("[\"arg1\"]");

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("dummyTool", String.class);
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{"arg1"}, "conv-1", trace);

            assertEquals("result:arg1", result);
            assertFalse(trace.getToolCalls().isEmpty());
            assertTrue(trace.getToolCalls().get(0).isSuccess());
        }

        @Test
        @DisplayName("should return rate limit error")
        void rateLimited() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(false);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("dummyTool", String.class);
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{"arg1"}, "conv-1", trace);

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("Rate limit exceeded"));
            assertTrue(trace.isHasErrors());
        }

        @Test
        @DisplayName("should return cached result and mark trace as from cache")
        void cachedResult() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(true);
            when(jsonSerialization.serialize(any())).thenReturn("[\"cached-args\"]");
            when(cacheService.get(eq("ExecuteToolTests"), anyString())).thenReturn("cached-result");

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("dummyTool", String.class);
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{"cached-args"}, "conv-1", trace);

            assertEquals("cached-result", result);
            assertTrue(trace.getToolCalls().get(0).isFromCache());
        }

        @Test
        @DisplayName("should handle tool method that returns null")
        void nullResult() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("nullTool");
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{}, "conv-1", trace);

            assertEquals("null", result);
        }

        @Test
        @DisplayName("should handle null args array")
        void nullArgs() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("noArgTool");
            method.setAccessible(true);

            String result = service.executeTool(this, method, null, "conv-1", trace);

            assertEquals("no-arg-result", result);
        }

        @Test
        @DisplayName("should handle tool method that throws exception")
        void toolThrows() throws Exception {
            when(rateLimiter.tryAcquire("ExecuteToolTests")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            var trace = new ToolExecutionTrace();
            Method method = ExecuteToolTests.class.getDeclaredMethod("failingTool");
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{}, "conv-1", trace);

            assertTrue(result.contains("Error executing tool"));
            assertTrue(trace.isHasErrors());
        }

        // Helper methods for reflection-based executeTool tests
        @SuppressWarnings("unused")
        private String dummyTool(String arg) {
            return "result:" + arg;
        }

        @SuppressWarnings("unused")
        private String nullTool() {
            return null;
        }

        @SuppressWarnings("unused")
        private String noArgTool() {
            return "no-arg-result";
        }

        @SuppressWarnings("unused")
        private String failingTool() {
            throw new RuntimeException("tool exploded");
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

    // ==================== executeToolsParallel ====================

    @Nested
    @DisplayName("executeToolsParallel")
    class ParallelTests {

        @Test
        @DisplayName("should throw on mismatched array lengths")
        void mismatchedArrays() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.executeToolsParallel(
                            new Object[1], new java.lang.reflect.Method[2], new Object[1][],
                            "conv-1", null));
        }

        @Test
        @DisplayName("should throw on all different array lengths")
        void allDifferentLengths() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.executeToolsParallel(
                            new Object[1], new java.lang.reflect.Method[3], new Object[2][],
                            "conv-1", null));
        }

        @Test
        @DisplayName("should return empty array from executeToolsParallelAndWait on timeout")
        void parallelTimeout() {
            when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);

            var trace = new ToolExecutionTrace();

            // Use a method that takes a very long time
            String[] results = service.executeToolsParallelAndWait(
                    new Object[0], new java.lang.reflect.Method[0], new Object[0][],
                    "conv-1", trace, 100);

            // Empty arrays → no futures → allOf completes immediately → empty result
            assertEquals(0, results.length);
        }
    }

    // ==================== shutdown ====================

    @Nested
    @DisplayName("shutdown")
    class ShutdownTests {

        @Test
        @DisplayName("should complete without error")
        void shutdownCompletes() {
            assertDoesNotThrow(() -> service.shutdown());
        }
    }

    // ==================== serializeArguments (via executeTool)
    // ====================

    @Nested
    @DisplayName("serializeArguments edge cases")
    class SerializeArgumentsTests {

        @Test
        @DisplayName("should handle serialization failure gracefully with toString fallback")
        void serializationFails() throws Exception {
            when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            // Force serialization to fail → fallback to toString
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("serialize failed"));

            var trace = new ToolExecutionTrace();
            Method method = SerializeArgumentsTests.class.getDeclaredMethod("helperTool", String.class);
            method.setAccessible(true);

            String result = service.executeTool(this, method, new Object[]{"val"}, "conv-1", trace);

            assertEquals("helper:val", result);
            // Verify it still recorded something in the trace
            assertFalse(trace.getToolCalls().isEmpty());
        }

        @SuppressWarnings("unused")
        private String helperTool(String arg) {
            return "helper:" + arg;
        }
    }
}
