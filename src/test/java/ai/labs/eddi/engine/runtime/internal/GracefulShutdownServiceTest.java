/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B3 — graceful shutdown: readiness flip, accept gate, bounded drain.
 *
 * <p>
 * The drain is driven by a fake coordinator whose reported depth is controlled
 * by latches, so "SIGTERM arrives while a turn is running" is deterministic
 * rather than timing-dependent.
 * </p>
 */
class GracefulShutdownServiceTest {

    private static GracefulShutdownService service(IConversationCoordinator coordinator, long drainTimeoutMillis) {
        // no readiness grace + a tight poll interval: the tests control progress with
        // latches, not with wall-clock waits.
        return new GracefulShutdownService(coordinator, drainTimeoutMillis, 0L, 1L);
    }

    @Test
    @DisplayName("starts accepting work — isShuttingDown is false before any shutdown signal")
    void notShuttingDownInitially() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        assertFalse(service(coordinator, 1_000).isShuttingDown());
    }

    /**
     * The ACCEPT gate must flip before the drain starts — otherwise the drain keeps
     * chasing turns that are still being admitted.
     */
    @Test
    @Timeout(30)
    @DisplayName("SIGTERM during an active turn: the turn completes, and new turns are rejected while it drains")
    void drainWaitsForTheActiveTurnAndRejectsNewOnesMeanwhile() throws Exception {
        // "one turn in flight" until the worker below finishes it.
        AtomicInteger inFlight = new AtomicInteger(1);
        CountDownLatch drainObservedTheActiveTurn = new CountDownLatch(1);
        AtomicBoolean gateWasClosedWhileTurnRan = new AtomicBoolean(false);
        CountDownLatch turnFinished = new CountDownLatch(1);

        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        GracefulShutdownService shutdownService = service(coordinator, 20_000);

        when(coordinator.getQueueDepths()).thenAnswer(inv -> {
            int remaining = inFlight.get();
            if (remaining > 0) {
                // The gate MUST already be closed the very first time the drain looks.
                gateWasClosedWhileTurnRan.set(shutdownService.isShuttingDown());
                drainObservedTheActiveTurn.countDown();
            }
            return remaining > 0 ? Map.of("conv-active", remaining) : Map.<String, Integer>of();
        });

        // The "active turn": finishes only after the drain has observed it.
        Thread activeTurn = new Thread(() -> {
            try {
                assertTrue(drainObservedTheActiveTurn.await(20, TimeUnit.SECONDS));
                // countDown FIRST: the drain may observe the depth drop the instant
                // inFlight flips, and the assertion below must not race it.
                turnFinished.countDown();
                inFlight.set(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "active-turn");
        activeTurn.start();

        boolean drained = shutdownService.drain();

        assertTrue(drained, "the drain must wait for the in-flight turn instead of dropping it");
        assertEquals(0, turnFinished.getCount(), "the drain must not return before the active turn finished");
        assertTrue(gateWasClosedWhileTurnRan.get(),
                "new turns must already be rejected while the drain is still waiting for the in-flight one");
        assertTrue(shutdownService.isShuttingDown());

        activeTurn.join(TimeUnit.SECONDS.toMillis(10));
    }

    /**
     * The wait must be hard bounded — a hung pipeline cannot be allowed to prevent
     * the process from exiting.
     */
    @Test
    @Timeout(30)
    @DisplayName("a turn that never finishes does not block shutdown forever")
    void drainIsBounded() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of("conv-hung", 1));

        GracefulShutdownService shutdownService = service(coordinator, 150);

        long start = System.nanoTime();
        boolean drained = shutdownService.drain();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(drained, "a drain that times out must report failure");
        assertTrue(shutdownService.isShuttingDown(), "the accept gate stays closed even when the drain times out");
        assertTrue(elapsedMillis < 20_000, "the drain must be bounded, took " + elapsedMillis + " ms");
    }

    @Test
    @Timeout(30)
    @DisplayName("nothing in flight — drain returns immediately and still closes the gate")
    void drainWithEmptyQueuesCompletesImmediately() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of());

        GracefulShutdownService shutdownService = service(coordinator, 20_000);

        assertTrue(shutdownService.drain());
        assertTrue(shutdownService.isShuttingDown());
    }

    @Test
    @Timeout(30)
    @DisplayName("a coordinator that cannot report its depth does not block the exit")
    void drainToleratesCoordinatorFailure() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenThrow(new IllegalStateException("bus disconnected"));

        GracefulShutdownService shutdownService = service(coordinator, 20_000);

        assertTrue(shutdownService.drain());
        assertTrue(shutdownService.isShuttingDown());
    }

    @Test
    @Timeout(30)
    @DisplayName("the ShutdownEvent observer runs the drain")
    void shutdownEventTriggersTheDrain() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of());

        GracefulShutdownService shutdownService = service(coordinator, 20_000);
        shutdownService.onShutdown(new ShutdownEvent());

        assertTrue(shutdownService.isShuttingDown());
    }

    @Test
    @DisplayName("readiness reports UP before shutdown and DOWN afterwards")
    void readinessFlipsOnShutdown() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of());

        GracefulShutdownService shutdownService = service(coordinator, 1_000);
        var healthCheck = new ShutdownReadinessHealthCheck(shutdownService);

        assertEquals(HealthCheckResponse.Status.UP, healthCheck.call().getStatus());

        shutdownService.drain();

        assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus(),
                "readiness must go DOWN so the load balancer stops routing traffic to this node");
    }
}
