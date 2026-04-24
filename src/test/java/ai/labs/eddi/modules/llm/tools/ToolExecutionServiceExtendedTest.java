/*
 * Copyright (c) 2016-2026 EDDI contributors
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

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ToolExecutionService} covering executeTool,
 * executeToolWrapped, rate limiting, caching, cost tracking, serialization
 * fallback, and parallel execution.
 */
@DisplayName("ToolExecutionService Extended Tests")
class ToolExecutionServiceExtendedTest {

    private ToolExecutionService service;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() {
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

    // Simple tool for reflection tests
    public static class SampleTool {
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        public String fail() {
            throw new RuntimeException("Tool failure");
        }
    }

    private Method greetMethod() throws NoSuchMethodException {
        return SampleTool.class.getMethod("greet", String.class);
    }

    private Method failMethod() throws NoSuchMethodException {
        return SampleTool.class.getMethod("fail");
    }

    @Nested
    @DisplayName("executeTool")
    class ExecuteTool {

        @Test
        @DisplayName("should execute tool successfully")
        void executesSuccessfully() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.001);
            when(jsonSerialization.serialize(any())).thenReturn("[\"World\"]");

            SampleTool tool = new SampleTool();
            ToolExecutionTrace trace = new ToolExecutionTrace();

            String result = service.executeTool(tool, greetMethod(), new Object[]{"World"}, "conv-1", trace);

            assertEquals("Hello, World!", result);
            verify(cacheService).put(eq("SampleTool"), anyString(), eq("Hello, World!"));
        }

        @Test
        @DisplayName("should return cached result when available")
        void returnsCachedResult() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn("Cached!");
            when(jsonSerialization.serialize(any())).thenReturn("[\"arg\"]");

            ToolExecutionTrace trace = new ToolExecutionTrace();
            String result = service.executeTool(new SampleTool(), greetMethod(), new Object[]{"arg"}, "conv-1", trace);

            assertEquals("Cached!", result);
            verify(cacheService, never()).put(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should return error when rate limited")
        void returnsErrorWhenRateLimited() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(false);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            ToolExecutionTrace trace = new ToolExecutionTrace();
            String result = service.executeTool(new SampleTool(), greetMethod(), new Object[]{"test"}, "conv-1", trace);

            assertTrue(result.startsWith("Error:"));
            assertTrue(result.contains("Rate limit"));
        }

        @Test
        @DisplayName("should handle tool execution exception")
        void handlesToolException() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenReturn("[]");

            ToolExecutionTrace trace = new ToolExecutionTrace();
            String result = service.executeTool(new SampleTool(), failMethod(), new Object[]{}, "conv-1", trace);

            assertTrue(result.contains("Error executing tool"));
        }

        @Test
        @DisplayName("should fall back to toString when serialization fails")
        void fallsBackOnSerializationError() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);
            when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("JSON error"));

            ToolExecutionTrace trace = new ToolExecutionTrace();
            String result = service.executeTool(new SampleTool(), greetMethod(), new Object[]{"World"}, "conv-1", trace);

            assertEquals("Hello, World!", result);
        }

        @Test
        @DisplayName("should handle null args array")
        void handlesNullArgs() throws Exception {
            when(rateLimiter.tryAcquire("SampleTool")).thenReturn(true);
            when(cacheService.get(anyString(), anyString())).thenReturn(null);
            when(costTracker.trackToolCall(anyString(), anyString())).thenReturn(0.0);

            ToolExecutionTrace trace = new ToolExecutionTrace();
            // null args → serializeArguments returns "[]" without calling jsonSerialization
            String result = service.executeTool(new SampleTool(), failMethod(), null, "conv-1", trace);
            // fail() will throw, but serialization should handle null args
            assertTrue(result.contains("Error"));
        }
    }

    @Nested
    @DisplayName("executeToolWrapped")
    class ExecuteWrapped {

        @Test
        @DisplayName("should execute with all features enabled")
        void executesWithAllFeatures() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get("myTool", "arg1")).thenReturn(null);

            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> "wrapped result", true, true, true, 60);

            assertEquals("wrapped result", result);
            verify(cacheService).put("myTool", "arg1", "wrapped result");
            verify(costTracker).trackToolCall("myTool", "conv-1");
        }

        @Test
        @DisplayName("should return cached when caching enabled")
        void returnsCachedWhenEnabled() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);
            when(cacheService.get("myTool", "arg1")).thenReturn("cached");

            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> "should not run", true, true, false, 60);

            assertEquals("cached", result);
        }

        @Test
        @DisplayName("should skip cache when disabled")
        void skipsCacheWhenDisabled() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(true);

            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> "direct result", true, false, false, 60);

            assertEquals("direct result", result);
            verify(cacheService, never()).get(anyString(), anyString());
        }

        @Test
        @DisplayName("should return error when rate limited")
        void rateLimited() {
            when(rateLimiter.tryAcquire("myTool", 60)).thenReturn(false);

            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> "should not run", true, false, false, 60);

            assertTrue(result.contains("Rate limit exceeded"));
        }

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void skipsRateLimitWhenDisabled() {
            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> "no rate limit", false, false, false, 0);

            assertEquals("no rate limit", result);
            verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        }

        @Test
        @DisplayName("should skip cost when conversationId is null")
        void skipsCostWhenNoConversation() {
            String result = service.executeToolWrapped("myTool", "arg1", null,
                    () -> "result", false, false, true, 0);

            assertEquals("result", result);
            verify(costTracker, never()).trackToolCall(anyString(), anyString());
        }

        @Test
        @DisplayName("should handle exception from supplier")
        void handlesException() {
            String result = service.executeToolWrapped("myTool", "arg1", "conv-1",
                    () -> {
                        throw new RuntimeException("boom");
                    }, false, false, false, 0);

            assertTrue(result.contains("Error executing tool"));
            assertTrue(result.contains("boom"));
        }
    }

    @Nested
    @DisplayName("parallel execution")
    class ParallelExec {

        @Test
        @DisplayName("should throw on mismatched array lengths")
        void throwsOnMismatch() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.executeToolsParallel(
                            new Object[]{new SampleTool()},
                            new Method[]{},
                            new Object[][]{},
                            "conv-1",
                            new ToolExecutionTrace()));
        }
    }

    @Nested
    @DisplayName("getCostTracker")
    class CostTrackerAccess {

        @Test
        @DisplayName("should return injected cost tracker")
        void returnsCostTracker() {
            assertSame(costTracker, service.getCostTracker());
        }
    }

    @Nested
    @DisplayName("shutdown")
    class ShutdownTest {

        @Test
        @DisplayName("should shutdown gracefully")
        void shutsDown() {
            assertDoesNotThrow(() -> service.shutdown());
        }
    }
}
