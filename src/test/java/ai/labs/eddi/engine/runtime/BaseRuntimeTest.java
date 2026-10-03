/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for BaseRuntime — construction, version, and callable submission.
 */
class BaseRuntimeTest {

    private BaseRuntime runtime;
    private ManagedExecutor mockExecutor;
    private ExecutorService realExecutor;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new BaseRuntime("TestProject", "1.2.3");

        // Create a mock ManagedExecutor that delegates to a real single-thread executor
        mockExecutor = Mockito.mock(ManagedExecutor.class);
        realExecutor = Executors.newSingleThreadExecutor();
        when(mockExecutor.submit(any(Callable.class))).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(0);
            return realExecutor.submit(callable);
        });

        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, mockExecutor);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.getScheduledExecutorService().shutdownNow();
        }
        if (realExecutor != null) {
            realExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("getVersion returns configured version")
    void getVersion() {
        assertEquals("1.2.3", runtime.getVersion());
    }

    @Test
    @DisplayName("logVersion does not throw")
    void logVersion() {
        assertDoesNotThrow(() -> runtime.logVersion());
    }

    @Test
    @DisplayName("init called twice logs warning but does not crash")
    void doubleInit() {
        assertDoesNotThrow(() -> runtime.init());
    }

    @Test
    @DisplayName("getExecutorService returns injected executor")
    void getExecutorService() {
        assertSame(mockExecutor, runtime.getExecutorService());
    }

    @Test
    @DisplayName("getScheduledExecutorService returns non-null")
    void getScheduledExecutorService() {
        assertNotNull(runtime.getScheduledExecutorService());
    }

    @Test
    @DisplayName("submitCallable executes and returns result")
    void submitCallable() throws Exception {
        Future<String> future = runtime.submitCallable(() -> "hello", null);
        assertEquals("hello", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("submitCallable with callback invokes onComplete")
    void submitCallable_withCallback() throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();

        runtime.submitCallable(
                () -> "result",
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(String result) {
                        completed.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        completed.completeExceptionally(t);
                    }
                },
                null);

        assertEquals("result", completed.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("submitCallable with failing callable invokes onFailure")
    void submitCallable_failure() throws Exception {
        CompletableFuture<Throwable> failureCaught = new CompletableFuture<>();

        runtime.submitCallable(
                () -> {
                    throw new RuntimeException("boom");
                },
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(Object result) {
                        failureCaught.completeExceptionally(new AssertionError("should not complete"));
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        failureCaught.complete(t);
                    }
                },
                null);

        Throwable error = failureCaught.get(5, TimeUnit.SECONDS);
        assertEquals("boom", error.getMessage());
    }

    @Test
    @DisplayName("construction with empty project name logs error but works")
    void emptyProjectName() {
        assertDoesNotThrow(() -> {
            var rt = new BaseRuntime("", "0.0.1");
            rt.getScheduledExecutorService().shutdownNow();
        });
    }

    @Test
    @DisplayName("submitCallable with interrupted thread routes to onFailure instead of onComplete")
    void submitCallable_cancelledRoutesToOnFailure() throws Exception {
        CompletableFuture<String> onCompleteCalled = new CompletableFuture<>();
        CompletableFuture<Throwable> onFailureCalled = new CompletableFuture<>();

        // Sets the interrupt flag to simulate the scenario where future.cancel(true)
        // was called during a non-interruptible I/O operation (e.g., LLM HTTP call).
        // The callable completes normally but the interrupt flag remains set.
        Future<String> future = runtime.submitCallable(
                () -> {
                    Thread.currentThread().interrupt(); // simulate interrupt flag left by cancel(true)
                    return "stale-result";
                },
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(String result) {
                        onCompleteCalled.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        onFailureCalled.complete(t);
                    }
                },
                null);

        // onFailure must be called with the dedicated abandonment type — a bare
        // InterruptedException would be indistinguishable from a real interruption
        // of the callable body, which callers must treat as a genuine failure.
        Throwable failure = onFailureCalled.get(5, TimeUnit.SECONDS);
        assertInstanceOf(ExecutionAbandonedException.class, failure);
        assertTrue(failure.getMessage().contains("cancellation"));

        // onComplete should NOT have been called
        assertFalse(onCompleteCalled.isDone(),
                "onComplete should not be called when thread was interrupted");

        // Future must return null — NOT the stale "stale-result" value.
        // This prevents callers who do future.get() from receiving data
        // that was already routed to onFailure.
        assertNull(future.get(5, TimeUnit.SECONDS),
                "Future should return null when thread was interrupted to prevent stale result leakage");
    }

    @Test
    @DisplayName("submitCallable without interruption still calls onComplete normally")
    // Pair test with submitCallable_cancelledRoutesToOnFailure — confirms the
    // interrupt-checking code path doesn't affect normal (non-cancelled) execution.
    void submitCallable_noInterruption_callsOnComplete() throws Exception {
        CompletableFuture<String> completed = new CompletableFuture<>();

        runtime.submitCallable(
                () -> "normal-result",
                new IRuntime.IFinishedExecution<>() {
                    @Override
                    public void onComplete(String result) {
                        completed.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        completed.completeExceptionally(t);
                    }
                },
                null);

        assertEquals("normal-result", completed.get(5, TimeUnit.SECONDS));
    }

    /**
     * Turn-level executor saturation was not measured anywhere. The executor here
     * has one thread, so a second submission has to wait behind the first: that
     * wait is exactly what {@code queued} exists to show, and a cancelled
     * submission that never ran must not stay counted as queued.
     */
    @Test
    @DisplayName("executor load gauges count queued and running work, and a cancel before start leaves the queue")
    void executorLoadIsMeasured() throws Exception {
        var registry = new SimpleMeterRegistry();
        runtime.meterRegistry = registry;
        runtime.registerMetrics();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        runtime.submitCallable(() -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "first";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Future<String> waiting = runtime.submitCallable(() -> "second", null);
        Future<String> cancelled = runtime.submitCallable(() -> "third", null);

        assertEquals(1.0, gauge(registry, "eddi.runtime.executor.active", "managed"));
        assertEquals(2.0, gauge(registry, "eddi.runtime.executor.queued", "managed"));

        assertTrue(cancelled.cancel(false));
        assertEquals(1.0, gauge(registry, "eddi.runtime.executor.queued", "managed"),
                "a submission cancelled before it started must leave the queued count");

        release.countDown();
        assertEquals("second", waiting.get(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gauge(registry, "eddi.runtime.executor.active", "managed") > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0.0, gauge(registry, "eddi.runtime.executor.active", "managed"));
        assertEquals(0.0, gauge(registry, "eddi.runtime.executor.queued", "managed"));
        assertTrue(gauge(registry, "eddi.runtime.executor.max_threads", "managed") > 0);
    }

    /**
     * Every way out of a submission has to give the gauges back: a refused
     * submission never queues, a callable that throws still stops being active, and
     * one cancelled while running leaves the queue exactly once. A leak here reads
     * as permanent saturation, which is the alert's own trigger.
     */
    @Test
    @DisplayName("executor load returns to zero after a rejection, a failure and a cancel during the run")
    void executorLoadBalancesOnEveryPath() throws Exception {
        var registry = new SimpleMeterRegistry();
        runtime.meterRegistry = registry;
        runtime.registerMetrics();

        // Rejection: the executor refuses the submission.
        when(mockExecutor.submit(any(Callable.class))).thenThrow(new RejectedExecutionException("full"));
        assertThrows(RejectedExecutionException.class, () -> runtime.submitCallable(() -> "x", null));
        assertEquals(0.0, gauge(registry, "eddi.runtime.executor.queued", "managed"), "a refused submission must not stay queued");

        // Failure and cancel during the run, on a working executor.
        when(mockExecutor.submit(any(Callable.class))).thenAnswer(inv -> {
            Callable<?> callable = inv.getArgument(0);
            return realExecutor.submit(callable);
        });
        runtime.submitCallable(() -> {
            throw new IllegalStateException("boom");
        }, null);
        CountDownLatch running = new CountDownLatch(1);
        Future<String> cancelledWhileRunning = runtime.submitCallable(() -> {
            running.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }, null);
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertEquals(1.0, gauge(registry, "eddi.runtime.executor.active", "managed"));
        cancelledWhileRunning.cancel(true);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gauge(registry, "eddi.runtime.executor.active", "managed") > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0.0, gauge(registry, "eddi.runtime.executor.active", "managed"));
        assertEquals(0.0, gauge(registry, "eddi.runtime.executor.queued", "managed"));
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String pool) {
        return registry.get(name).tag("pool", pool).gauge().value();
    }
}
