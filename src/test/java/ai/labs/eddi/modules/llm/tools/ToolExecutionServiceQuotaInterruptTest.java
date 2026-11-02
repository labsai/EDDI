/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ToolExecutionService quota reservation when the caller is interrupted")
class ToolExecutionServiceQuotaInterruptTest {

    @Test
    @DisplayName("an interrupted wait keeps the count: the abandoned worker may still finish the side effect")
    void interruptKeepsReservation() {
        ToolExecutionService service = new ToolExecutionService();
        service.cacheService = mock(ToolCacheService.class);
        service.rateLimiter = mock(ToolRateLimiter.class);
        service.costTracker = mock(ToolCostTracker.class);
        service.meterRegistry = new SimpleMeterRegistry();
        service.init();
        ToolQuotaGate gate = mock(ToolQuotaGate.class);
        when(gate.reserve()).thenReturn(Optional.empty());
        CountDownLatch block = new CountDownLatch(1);

        Thread.currentThread().interrupt();
        try {
            service.executeToolWrapped(ToolInvocation.of("myTool"), "args", "u:0123456789abcdef0123456789abcdef", "conv-1", () -> {
                try {
                    block.await();
                } catch (InterruptedException ignored) {
                    // the worker is cancelled with the wait
                }
                return "done";
            }, false, false, false, 0, 5000, gate);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must survive");
        } finally {
            Thread.interrupted();
        }

        verify(gate).reserve();
        verify(gate, never()).settle(any(), any());
    }
}
