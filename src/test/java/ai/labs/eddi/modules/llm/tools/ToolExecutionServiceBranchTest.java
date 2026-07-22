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
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage tests for {@link ToolExecutionService}: the
 * {@code executeToolWrapped} error branch that falls back to the exception
 * class name, and the cost-tracking branch taken when a conversation id is
 * present.
 */
@DisplayName("ToolExecutionService Branch Coverage Tests")
class ToolExecutionServiceBranchTest {

    /**
     * Stand-in scope tag; the resolution table itself is tested in
     * ToolCacheServiceTest.
     */
    private static final String SCOPE = "u:0123456789abcdef0123456789abcdef";

    private ToolExecutionService service;
    private ToolCacheService cacheService;
    private ToolRateLimiter rateLimiter;
    private ToolCostTracker costTracker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
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

    // ==================== executeToolWrapped exception with null message
    // ====================

    @Nested
    @DisplayName("executeToolWrapped edge cases")
    class WrappedEdgeCases {

        @Test
        @DisplayName("exception with null message uses class name")
        void exceptionWithNullMessage() {
            String result = service.executeToolWrapped("myTool", "args", SCOPE, "conv-1",
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
            when(cacheService.get(SCOPE, "myTool", "args")).thenReturn(null);

            service.executeToolWrapped("myTool", "args", SCOPE, "conv-1",
                    () -> "result", true, true, true, 60);

            verify(costTracker).trackToolCall(ToolInvocation.of("myTool"), "conv-1");
        }
    }
}
