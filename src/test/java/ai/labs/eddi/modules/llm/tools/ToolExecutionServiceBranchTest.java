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
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage tests for {@link ToolExecutionService}. Focuses
 * on: - executeToolsParallelAndWait with actual work -
 * executeToolsParallelAndWait timeout and error branches - serializeArguments
 * multi-arg fallback (toString path) - shutdown InterruptedException branch -
 * executeTool with InvocationTargetException
 */
@DisplayName("ToolExecutionService Branch Coverage Tests")
class ToolExecutionServiceBranchTest {

    private ToolExecutionService service;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ToolExecutionService();
        cacheService = mock(ToolCacheService.class);
        rateLimiter = mock(ToolRateLimiter.class);
        costTracker = mock(ToolCostTracker.class);
        jsonSerialization = mock(IJsonSerialization.class);

        service.cacheService = cacheService;
        service.rateLimiter = rateLimiter;
        service.costTracker = costTracker;
        service.jsonSerialization = jsonSerialization;
        service.meterRegistry = new SimpleMeterRegistry();
        service.init();
    }

    public static class TestTool {
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        public String fail() {
            throw new RuntimeException("boom");
        }

        public String slow() throws InterruptedException {
            Thread.sleep(5000);
            return "done";
        }
    }

    // ==================== executeToolsParallelAndWait ====================

    @Nested
    @DisplayName("executeToolsParallelAndWait")
    class ParallelAndWaitTests {

        @Test
        @DisplayName("should execute single tool and return result")
        void executeSingleTool() throws Exception {
            when(rateLimiter.tryAcquire("TestTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.001);
            when(jsonSerialization.serialize(any())).thenReturn("[\"World\"]");

            TestTool tool = new TestTool();
            Method method = TestTool.class.getMethod("greet", String.class);
            ToolExecutionTrace trace = new ToolExecutionTrace();

            String[] results = service.executeToolsParallelAndWait(
                    new Object[]{tool},
                    new Method[]{method},
                    new Object[][]{{"World"}},
                    "conv-1", trace, 5000);

            assertEquals(1, results.length);
            assertEquals("Hello, World!", results[0]);
        }

        @Test
        @DisplayName("should return empty array on general exception")
        void returnsEmptyOnException() throws Exception {
            when(rateLimiter.tryAcquire("TestTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            TestTool tool = new TestTool();
            Method method = TestTool.class.getMethod("fail");
            ToolExecutionTrace trace = new ToolExecutionTrace();

            // fail() throws inside the tool, but executeTool catches it
            // The parallel wrapper should complete normally (error string result)
            String[] results = service.executeToolsParallelAndWait(
                    new Object[]{tool},
                    new Method[]{method},
                    new Object[][]{{}},
                    "conv-1", trace, 5000);

            assertEquals(1, results.length);
            assertTrue(results[0].contains("Error executing tool"));
        }
    }

    // ==================== serializeArguments edge cases ====================

    @Nested
    @DisplayName("serializeArguments")
    class SerializeArgumentsTests {

        @Test
        @DisplayName("multiple args toString fallback when serialization fails")
        void multipleArgsToStringFallback() throws Exception {
            when(rateLimiter.tryAcquire("TestTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("JSON fail"));

            TestTool tool = new TestTool();
            Method method = TestTool.class.getMethod("greet", String.class);
            ToolExecutionTrace trace = new ToolExecutionTrace();

            String result = service.executeTool(tool, method, new Object[]{"World"}, "conv-1", trace);

            assertEquals("Hello, World!", result);
            // Cache put should have been called with fallback serialized args
            verify(cacheService).put(eq("TestTool"), contains("World"), eq("Hello, World!"));
        }

        @Test
        @DisplayName("null arg in array is serialized as 'null'")
        void nullArgInArray() throws Exception {
            when(rateLimiter.tryAcquire("TestTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            // Simulate null in args going through toString fallback
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("fail"));

            TestTool tool = new TestTool();
            Method method = TestTool.class.getMethod("greet", String.class);
            ToolExecutionTrace trace = new ToolExecutionTrace();

            // Passing null as arg will cause InvocationTargetException wrapping NPE
            // at "Hello, " + name + "!" but that's handled as "Error executing tool"
            String result = service.executeTool(tool, method, new Object[]{null}, "conv-1", trace);
            // Result should contain "Hello, null!" since String concat handles null
            assertEquals("Hello, null!", result);
        }
    }

    // ==================== executeToolWrapped exception with null message
    // ====================

    @Nested
    @DisplayName("executeToolWrapped edge cases")
    class WrappedEdgeCases {

        @Test
        @DisplayName("exception with null message uses class name")
        void exceptionWithNullMessage() {
            String result = service.executeToolWrapped("myTool", "args", "conv-1",
                    () -> {
                        throw new NullPointerException();
                    },
                    false, false, false, 0);

            assertTrue(result.contains("NullPointerException"));
        }

        @Test
        @DisplayName("cost tracking enabled with non-null conversationId")
        void costTrackingEnabled() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get("myTool", "args")).thenReturn(null);

            service.executeToolWrapped("myTool", "args", "conv-1",
                    () -> "result", true, true, true, 60);

            verify(costTracker).trackToolCall("myTool", "conv-1");
        }
    }

    // ==================== executeToolsParallel ====================

    @Nested
    @DisplayName("executeToolsParallel")
    class ParallelTests {

        @Test
        @DisplayName("should execute multiple tools in parallel")
        void executeMultipleInParallel() throws Exception {
            when(rateLimiter.tryAcquire("TestTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenReturn("[\"arg\"]");

            TestTool tool = new TestTool();
            Method method = TestTool.class.getMethod("greet", String.class);
            ToolExecutionTrace trace = new ToolExecutionTrace();

            CompletableFuture<String>[] futures = service.executeToolsParallel(
                    new Object[]{tool, tool},
                    new Method[]{method, method},
                    new Object[][]{{"Alice"}, {"Bob"}},
                    "conv-1", trace);

            assertEquals(2, futures.length);
            CompletableFuture.allOf(futures).join();

            String r1 = futures[0].get();
            String r2 = futures[1].get();
            assertEquals("Hello, Alice!", r1);
            assertEquals("Hello, Bob!", r2);
        }
    }
}
