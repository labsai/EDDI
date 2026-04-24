/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolRateLimiter} covering token bucket logic, window
 * resets, custom limits, and informational methods.
 */
class ToolRateLimiterTest {

    private ToolRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new ToolRateLimiter();
        rateLimiter.meterRegistry = new SimpleMeterRegistry();
        rateLimiter.init();
    }

    @Nested
    @DisplayName("tryAcquire with default limit")
    class DefaultLimit {

        @Test
        @DisplayName("should allow first call")
        void firstCallAllowed() {
            assertTrue(rateLimiter.tryAcquire("testTool"));
        }

        @Test
        @DisplayName("should allow multiple calls within default limit (100)")
        void multipleCallsWithinLimit() {
            for (int i = 0; i < 50; i++) {
                assertTrue(rateLimiter.tryAcquire("testTool"), "Call " + i + " should be allowed");
            }
        }

        @Test
        @DisplayName("should deny call at default limit boundary")
        void denyAtBoundary() {
            // Exhaust default limit (100)
            for (int i = 0; i < 100; i++) {
                assertTrue(rateLimiter.tryAcquire("exhaustTool"));
            }
            // 101st call should be denied
            assertFalse(rateLimiter.tryAcquire("exhaustTool"));
        }
    }

    @Nested
    @DisplayName("tryAcquire with custom limit")
    class CustomLimit {

        @Test
        @DisplayName("should enforce custom lower limit")
        void customLowerLimit() {
            for (int i = 0; i < 5; i++) {
                assertTrue(rateLimiter.tryAcquire("limitedTool", 5));
            }
            assertFalse(rateLimiter.tryAcquire("limitedTool", 5));
        }

        @Test
        @DisplayName("should enforce limit of 1")
        void limitOfOne() {
            assertTrue(rateLimiter.tryAcquire("onceTool", 1));
            assertFalse(rateLimiter.tryAcquire("onceTool", 1));
        }
    }

    @Nested
    @DisplayName("getRemaining")
    class GetRemaining {

        @Test
        @DisplayName("should return default limit for unknown tool")
        void unknownToolReturnsDefault() {
            assertEquals(100, rateLimiter.getRemaining("unknownTool"));
        }

        @Test
        @DisplayName("should decrease after calls")
        void decreasesAfterCalls() {
            rateLimiter.tryAcquire("countTool", 10);
            rateLimiter.tryAcquire("countTool", 10);
            assertEquals(8, rateLimiter.getRemaining("countTool"));
        }
    }

    @Nested
    @DisplayName("getInfo")
    class GetInfo {

        @Test
        @DisplayName("should return default info for unknown tool")
        void unknownToolInfo() {
            var info = rateLimiter.getInfo("newTool");
            assertEquals(100, info.limit);
            assertEquals(100, info.remaining);
            assertTrue(info.resetTimeMs > System.currentTimeMillis());
        }

        @Test
        @DisplayName("should reflect current state for known tool")
        void knownToolInfo() {
            rateLimiter.tryAcquire("knownTool", 5);
            var info = rateLimiter.getInfo("knownTool");
            assertEquals(5, info.limit);
            assertEquals(4, info.remaining);
        }

        @Test
        @DisplayName("toString should contain rate limit info")
        void infoToString() {
            var info = rateLimiter.getInfo("anyTool");
            String str = info.toString();
            assertTrue(str.contains("Rate Limit"));
            assertTrue(str.contains("remaining"));
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("should clear specific tool bucket")
        void resetSpecificTool() {
            // Exhaust the limit
            for (int i = 0; i < 5; i++) {
                rateLimiter.tryAcquire("resetMe", 5);
            }
            assertFalse(rateLimiter.tryAcquire("resetMe", 5));

            // Reset and verify it's available again
            rateLimiter.reset("resetMe");
            assertTrue(rateLimiter.tryAcquire("resetMe", 5));
        }

        @Test
        @DisplayName("resetAll should clear all buckets")
        void resetAll() {
            rateLimiter.tryAcquire("tool1", 1);
            rateLimiter.tryAcquire("tool2", 1);
            assertFalse(rateLimiter.tryAcquire("tool1", 1));
            assertFalse(rateLimiter.tryAcquire("tool2", 1));

            rateLimiter.resetAll();
            assertTrue(rateLimiter.tryAcquire("tool1", 1));
            assertTrue(rateLimiter.tryAcquire("tool2", 1));
        }
    }
}
