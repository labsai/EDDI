package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolRateLimiter - including custom per-tool limits fix.
 */
class ToolRateLimiterTest {

    private ToolRateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter = new ToolRateLimiter();
        // Inject a SimpleMeterRegistry since @PostConstruct won't run in unit tests
        Field meterField = ToolRateLimiter.class.getDeclaredField("meterRegistry");
        meterField.setAccessible(true);
        meterField.set(rateLimiter, new SimpleMeterRegistry());
        rateLimiter.init();
    }

    @Test
    void testTryAcquire_Default_AllowsWithinLimit() {
        for (int i = 0; i < 100; i++) {
            assertTrue(rateLimiter.tryAcquire("testTool"), "Should allow call " + (i + 1) + " within default limit");
        }
    }

    @Test
    void testTryAcquire_Default_DeniesOverLimit() {
        for (int i = 0; i < 100; i++) {
            rateLimiter.tryAcquire("testTool");
        }
        assertFalse(rateLimiter.tryAcquire("testTool"), "Should deny call 101 (exceeds default limit of 100)");
    }

    @Test
    void testTryAcquire_CustomLimit_RespectsLimit() {
        // Set a custom limit of 5
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire("limitedTool", 5), "Should allow call " + (i + 1) + " within custom limit of 5");
        }
        assertFalse(rateLimiter.tryAcquire("limitedTool", 5), "Should deny call 6 (exceeds custom limit of 5)");
    }

    @Test
    void testTryAcquire_CustomLimitUpdate_IsApplied() {
        // First, create bucket with limit of 3
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquire("updatedTool", 3));
        }
        assertFalse(rateLimiter.tryAcquire("updatedTool", 3), "Should deny at limit of 3");

        // Reset and re-create with higher limit
        rateLimiter.reset("updatedTool");

        // Now use a higher limit of 10
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.tryAcquire("updatedTool", 10), "Should allow call " + (i + 1) + " with updated limit of 10");
        }
        assertFalse(rateLimiter.tryAcquire("updatedTool", 10));
    }

    @Test
    void testTryAcquire_CustomLimitAppliedOnExistingBucket() {
        // Create bucket with limit 5
        assertTrue(rateLimiter.tryAcquire("dynamicTool", 5));

        // Update to limit 2 - next call with limit 2 should update the bucket limit
        rateLimiter.tryAcquire("dynamicTool", 2);
        // After 2 total calls (1 + 1), with limit now 2, next should fail
        assertFalse(rateLimiter.tryAcquire("dynamicTool", 2), "After updating limit to 2, 3rd call should be denied");
    }

    @Test
    void testTryAcquire_DifferentToolsIndependent() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire("toolA", 5);
        }
        assertFalse(rateLimiter.tryAcquire("toolA", 5));

        // toolB should still be allowed
        assertTrue(rateLimiter.tryAcquire("toolB", 5));
    }

    @Test
    void testGetRemaining_UnknownTool() {
        // Unknown tool should return default limit
        assertEquals(100, rateLimiter.getRemaining("unknownTool"));
    }

    @Test
    void testGetRemaining_AfterUsage() {
        rateLimiter.tryAcquire("remainTool", 10);
        rateLimiter.tryAcquire("remainTool", 10);
        assertEquals(8, rateLimiter.getRemaining("remainTool"));
    }

    @Test
    void testGetInfo_UnknownTool() {
        var info = rateLimiter.getInfo("unknownTool");
        assertEquals(100, info.limit);
        assertEquals(100, info.remaining);
    }

    @Test
    void testGetInfo_AfterUsage() {
        rateLimiter.tryAcquire("infoTool", 20);
        var info = rateLimiter.getInfo("infoTool");
        assertEquals(20, info.limit);
        assertEquals(19, info.remaining);
    }

    @Test
    void testReset_ClearsTool() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire("resetTool", 5);
        }
        assertFalse(rateLimiter.tryAcquire("resetTool", 5));

        rateLimiter.reset("resetTool");
        assertTrue(rateLimiter.tryAcquire("resetTool", 5), "Should allow after reset");
    }

    @Test
    void testResetAll_ClearsAllTools() {
        rateLimiter.tryAcquire("tool1", 1);
        rateLimiter.tryAcquire("tool2", 1);
        assertFalse(rateLimiter.tryAcquire("tool1", 1));
        assertFalse(rateLimiter.tryAcquire("tool2", 1));

        rateLimiter.resetAll();
        assertTrue(rateLimiter.tryAcquire("tool1", 1));
        assertTrue(rateLimiter.tryAcquire("tool2", 1));
    }

    @Test
    void testRateLimitInfo_ToString() {
        rateLimiter.tryAcquire("infoStr", 10);
        var info = rateLimiter.getInfo("infoStr");
        String str = info.toString();
        assertTrue(str.contains("Rate Limit: 9/10 remaining"));
    }
}
