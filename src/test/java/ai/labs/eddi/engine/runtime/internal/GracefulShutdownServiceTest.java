/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.annotation.Annotation;
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

        TimedDrain timed = timeDrain(shutdownService);
        long elapsedMillis = timed.elapsedMillis();

        assertFalse(timed.drained(), "a drain that times out must report failure");
        assertTrue(shutdownService.isShuttingDown(), "the accept gate stays closed even when the drain times out");
        // Proportional to the CONFIGURED 150 ms budget (~13x slack for a slow CI
        // box), not an arbitrary 20 s ceiling: a bound that loose would happily
        // accept a drain that ignores the timeout and hardcodes 10 s.
        assertTrue(elapsedMillis < 2_000, "the drain must be bounded by its configured budget, took " + elapsedMillis + " ms");
    }

    /**
     * B3's drain budget is CONFIGURABLE, and the configured value has to be the
     * actual deadline. A drain that hardcodes its own timeout still "is bounded"
     * and still "reports failure" — the only thing that catches it is measuring
     * that a larger budget waits proportionally longer.
     */
    @Test
    @Timeout(60)
    @DisplayName("the configured drain timeout IS the deadline — a larger budget waits proportionally longer")
    void drainHonoursTheConfiguredTimeoutAsItsDeadline() {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of("conv-hung", 1));

        long shortBudgetElapsed = timeDrain(service(coordinator, 100)).elapsedMillis();
        long longBudgetElapsed = timeDrain(service(coordinator, 1_500)).elapsedMillis();

        assertTrue(shortBudgetElapsed >= 100,
                "a 100 ms budget must actually be waited out, took " + shortBudgetElapsed + " ms");
        assertTrue(shortBudgetElapsed < 900,
                "a 100 ms budget must not wait anywhere near a second, took " + shortBudgetElapsed + " ms");
        assertTrue(longBudgetElapsed >= 1_400,
                "a 1500 ms budget must be waited out in full, took " + longBudgetElapsed + " ms");
        assertTrue(longBudgetElapsed - shortBudgetElapsed > 500,
                "the deadline must scale with eddi.shutdown.drain-timeout-seconds — measured "
                        + shortBudgetElapsed + " ms vs " + longBudgetElapsed + " ms");
    }

    /**
     * The SHIPPED defaults have to fit inside the termination grace period of the
     * manifests this repo ships ({@code terminationGracePeriodSeconds: 30} in
     * {@code k8s/base/eddi-deployment.yaml}, {@code k8s/quickstart.yaml} and
     * {@code helm/eddi/templates/deployment.yaml}). A budget that exceeds it means
     * the pod is SIGKILLed part-way through the drain, so the drain B3 promises
     * never actually happens.
     * <p>
     * Asserted against the {@code @ConfigProperty} defaults themselves — the
     * millisecond constructor the other tests use bypasses them entirely.
     */
    @Test
    @DisplayName("the shipped drain budget fits inside the manifests' terminationGracePeriodSeconds: 30")
    void defaultDrainBudgetFitsTheKubernetesTerminationGracePeriod() throws Exception {
        int drainSeconds = configuredDefault("eddi.shutdown.drain-timeout-seconds");
        int graceSeconds = configuredDefault("eddi.shutdown.readiness-grace-seconds");

        assertTrue(drainSeconds > 0, "the shipped drain budget must actually drain something");
        assertTrue(graceSeconds + drainSeconds <= MAX_SHUTDOWN_BUDGET_SECONDS,
                "readiness grace (" + graceSeconds + " s) + drain (" + drainSeconds + " s) = "
                        + (graceSeconds + drainSeconds) + " s must stay at or below " + MAX_SHUTDOWN_BUDGET_SECONDS
                        + " s so the rest of the Quarkus shutdown still fits inside the manifests' "
                        + MANIFEST_TERMINATION_GRACE_SECONDS + " s termination grace period");
    }

    /**
     * terminationGracePeriodSeconds shipped by this repo's k8s + helm manifests.
     */
    private static final int MANIFEST_TERMINATION_GRACE_SECONDS = 30;

    /**
     * Ceiling for readiness-grace + drain: leaves 5 s of the grace period for the
     * remainder of the Quarkus shutdown sequence (executor shutdown, Mongo client
     * close, JVM exit).
     */
    private static final int MAX_SHUTDOWN_BUDGET_SECONDS = MANIFEST_TERMINATION_GRACE_SECONDS - 5;

    /**
     * Reads the {@code defaultValue} of a {@code @ConfigProperty} constructor
     * parameter.
     */
    private static int configuredDefault(String propertyName) throws NoSuchMethodException {
        var constructor = GracefulShutdownService.class.getDeclaredConstructor(
                IConversationCoordinator.class, int.class, int.class, long.class);
        for (Annotation[] parameterAnnotations : constructor.getParameterAnnotations()) {
            for (Annotation annotation : parameterAnnotations) {
                if (annotation instanceof ConfigProperty configProperty && propertyName.equals(configProperty.name())) {
                    return Integer.parseInt(configProperty.defaultValue());
                }
            }
        }
        throw new AssertionError("No @ConfigProperty named " + propertyName + " on the injectable constructor");
    }

    /** Outcome of a drain plus how long it actually took. */
    private record TimedDrain(boolean drained, long elapsedMillis) {
    }

    /** Runs the drain and measures its wall-clock duration. */
    private static TimedDrain timeDrain(GracefulShutdownService shutdownService) {
        long start = System.nanoTime();
        boolean drained = shutdownService.drain();
        return new TimedDrain(drained, (System.nanoTime() - start) / 1_000_000L);
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

    /**
     * Pins the interrupt CONTRACT: an interrupted drain returns promptly, returns
     * the honest boolean, and leaves the accept gate shut.
     * <p>
     * Be clear about what this does <em>not</em> do: it does not distinguish the
     * readiness-grace fix from the code before it. Reverting that fix leaves these
     * tests green, because {@code sleepQuietly} restores the interrupt flag — so
     * the next {@code Thread.sleep} in the poll loop threw immediately and the old
     * code broke out just as fast, with the same return value. The fix buys two
     * things this test cannot see: the failure is logged as an interrupt rather
     * than as a 30-second TIMEOUT that never happened, and the exit no longer
     * depends on that restored-flag side effect to stop waiting.
     */
    @Test
    @Timeout(30)
    @DisplayName("an interrupt during the readiness grace stops the drain instead of being ignored")
    void interruptDuringReadinessGraceStopsTheDrain() throws Exception {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of("conv-1", 1));

        // A grace window long enough that the interrupt lands inside it.
        var shutdownService = new GracefulShutdownService(coordinator, 30_000L, 10_000L, 1L);

        var drained = new AtomicBoolean(true);
        var finished = new CountDownLatch(1);
        Thread drainThread = new Thread(() -> {
            drained.set(shutdownService.drain());
            finished.countDown();
        });
        drainThread.start();

        // Let it reach the grace sleep, then interrupt.
        Thread.sleep(150);
        drainThread.interrupt();

        assertTrue(finished.await(10, TimeUnit.SECONDS),
                "an interrupted drain must return promptly, not sit out the full 30s grace window");
        assertFalse(drained.get(), "work was still in flight, so the drain did not complete");
        assertTrue(shutdownService.isShuttingDown(), "the accept gate stays closed regardless of how the drain ended");
    }

    @Test
    @Timeout(30)
    @DisplayName("an interrupt with nothing in flight still reports a completed drain")
    void interruptWithNothingInFlightStillCompletes() throws Exception {
        IConversationCoordinator coordinator = mock(IConversationCoordinator.class);
        when(coordinator.getQueueDepths()).thenReturn(Map.of());

        var shutdownService = new GracefulShutdownService(coordinator, 30_000L, 10_000L, 1L);

        var drained = new AtomicBoolean(false);
        var finished = new CountDownLatch(1);
        Thread drainThread = new Thread(() -> {
            drained.set(shutdownService.drain());
            finished.countDown();
        });
        drainThread.start();

        Thread.sleep(150);
        drainThread.interrupt();

        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertTrue(drained.get(), "nothing was in flight, so an interrupted wait is still a clean drain");
    }
}
