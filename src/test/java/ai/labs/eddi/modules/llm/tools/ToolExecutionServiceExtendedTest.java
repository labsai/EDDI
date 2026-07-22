/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ToolExecutionService} covering executeToolWrapped,
 * rate limiting, caching and cost tracking.
 */
@DisplayName("ToolExecutionService Extended Tests")
class ToolExecutionServiceExtendedTest {

    private ToolExecutionService service;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;

    @BeforeEach
    void setUp() {
        service = new ToolExecutionService();
        cacheService = mock(ToolCacheService.class);
        rateLimiter = mock(ToolRateLimiter.class);
        costTracker = mock(ToolCostTracker.class);

        service.cacheService = cacheService;
        service.rateLimiter = rateLimiter;
        service.costTracker = costTracker;
        service.meterRegistry = new SimpleMeterRegistry();
        service.init();
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
    @DisplayName("getCostTracker")
    class CostTrackerAccess {

        @Test
        @DisplayName("should return injected cost tracker")
        void returnsCostTracker() {
            assertSame(costTracker, service.getCostTracker());
        }
    }
}
