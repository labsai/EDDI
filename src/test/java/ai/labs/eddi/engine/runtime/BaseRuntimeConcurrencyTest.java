/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Concurrency contracts of {@link BaseRuntime} — the three findings that make
 * conversation turns either deadlock, resurrect stale state, or run twice.
 *
 * <p>
 * Every test forces its interleaving with latches/barriers rather than
 * sleeping, and carries a {@link Timeout} so a regression fails fast instead of
 * hanging the fork.
 * </p>
 */
class BaseRuntimeConcurrencyTest {

    /**
     * Deliberately small stand-in for the bounded {@code ManagedExecutor}: the
     * production pool is 200 threads and the cliff sits at half of it.
     */
    private static final int POOL_SIZE = 4;

    private BaseRuntime runtime;
    private ExecutorService boundedPool;

    @BeforeEach
    void setUp() throws Exception {
        runtime = new BaseRuntime("TestProject", "1.0.0");

        boundedPool = Executors.newFixedThreadPool(POOL_SIZE);
        ManagedExecutor mockExecutor = Mockito.mock(ManagedExecutor.class);
        when(mockExecutor.submit(any(Callable.class))).thenAnswer(inv -> boundedPool.submit((Callable<?>) inv.getArgument(0)));

        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, mockExecutor);
    }

    @AfterEach
    void tearDown() {
        runtime.shutdown();
        boundedPool.shutdownNow();
    }

    /**
     * C4 — a conversation turn consumes TWO threads: the coordinator's callable
     * submits the pipeline execution and then BLOCKS on its Future. When both come
     * from the same bounded pool, concurrency collapses at HALF the pool size:
     * every thread is a waiter and no inner task can ever be scheduled.
     *
     * <p>
     * The barrier makes that precondition deterministic — all {@value #POOL_SIZE}
     * pool threads are parked in the outer task before any inner task is submitted,
     * so with the shared-pool behaviour NOTHING can make progress.
     * </p>
     */
    @Test
    @Timeout(60)
    @DisplayName("C4: nested submissions do not starve the bounded pool — N turns at pool size all complete")
    void nestedSubmissionsDoNotStarveTheBoundedPool() throws Exception {
        CyclicBarrier allOuterTasksRunning = new CyclicBarrier(POOL_SIZE);
        List<Future<String>> outerFutures = new ArrayList<>();

        for (int i = 0; i < POOL_SIZE; i++) {
            final int index = i;
            outerFutures.add(runtime.submitCallable(() -> {
                // Occupy every thread of the bounded pool BEFORE submitting inner work.
                allOuterTasksRunning.await(20, TimeUnit.SECONDS);

                Future<String> inner = runtime.submitCallable(() -> "inner-" + index, null);
                return inner.get(20, TimeUnit.SECONDS);
            }, null));
        }

        for (int i = 0; i < POOL_SIZE; i++) {
            assertEquals("inner-" + i, outerFutures.get(i).get(30, TimeUnit.SECONDS),
                    "every turn must complete; a null result means the inner task was never scheduled (pool deadlock)");
        }
    }

    /**
     * C3 — the watchdog abandons a turn, but the pipeline swallows the interrupt
     * (any {@code Thread.interrupted()} call CLEARS the flag) and runs to
     * completion. If completion is still reported, the abandoned turn persists its
     * stale full snapshot over whatever ran after it.
     */
    @Test
    @Timeout(30)
    @DisplayName("C3: a watchdog-abandoned turn reports failure even when the pipeline swallows the interrupt")
    void abandonedExecutionNeverReportsCompletion() throws Exception {
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        CountDownLatch onCompleteCalled = new CountDownLatch(1);
        CountDownLatch onFailureCalled = new CountDownLatch(1);
        AtomicInteger failureCount = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        Future<String> future = runtime.submitCallable(
                () -> {
                    executionStarted.countDown();
                    // Pipeline-style interrupt swallowing: the flag is cleared and never
                    // restored, so it cannot serve as a completion guard.
                    while (releaseExecution.getCount() > 0) {
                        try {
                            releaseExecution.await();
                        } catch (InterruptedException e) {
                            Thread.interrupted();
                        }
                    }
                    return "stale-result";
                },
                new IRuntime.IFinishedExecution<String>() {
                    @Override
                    public void onComplete(String result) {
                        onCompleteCalled.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        synchronized (failures) {
                            failures.add(t);
                        }
                        failureCount.incrementAndGet();
                        onFailureCalled.countDown();
                    }
                }, null);

        assertTrue(executionStarted.await(10, TimeUnit.SECONDS), "execution should have started");

        // The watchdog gives up on the turn...
        future.cancel(true);
        // ...and only then does the pipeline finish anyway.
        releaseExecution.countDown();

        assertTrue(onFailureCalled.await(10, TimeUnit.SECONDS),
                "a turn abandoned by the watchdog must be routed to onFailure, never onComplete");
        assertEquals(1, failureCount.get());
        synchronized (failures) {
            assertInstanceOf(InterruptedException.class, failures.get(0));
        }
        assertEquals(1, onCompleteCalled.getCount(),
                "onComplete must not fire for an abandoned turn — it would persist stale state over a newer turn");
    }

    /**
     * C9 — {@code onComplete} used to sit inside the {@code catch (Throwable)} that
     * routes to {@code onFailure}. Any unchecked throw on the completion path
     * therefore reported the turn as failed, and the coordinator's retry then
     * re-executed a callable that had ALREADY run (duplicate LLM calls, duplicate
     * tool side effects, duplicate cost).
     *
     * <p>
     * Suppressing onFailure must not turn the failure into silence: the throw is
     * surfaced through the Future instead, so the submitter (which knows the
     * conversation) can still log it with context and flip the state —
     * {@code ConversationService.waitForExecutionFinishOrTimeout} maps the
     * resulting {@link java.util.concurrent.ExecutionException} onto
     * {@code logConversationError} + ERROR. Swallowing it here (log-only) would
     * leave the caller unable to tell a persisted turn from a lost one.
     * </p>
     */
    @Test
    @Timeout(30)
    @DisplayName("C9: a throwing completion callback is not reported as a failure, but fails the Future")
    void throwingCompletionCallbackIsNotRoutedToOnFailure() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch onCompleteCalled = new CountDownLatch(1);
        CountDownLatch onFailureCalled = new CountDownLatch(1);

        Future<String> future = runtime.submitCallable(
                () -> {
                    executions.incrementAndGet();
                    return "ok";
                },
                new IRuntime.IFinishedExecution<String>() {
                    @Override
                    public void onComplete(String result) {
                        onCompleteCalled.countDown();
                        throw new IllegalStateException("completion callback blew up");
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        onFailureCalled.countDown();
                    }
                }, null);

        assertTrue(onCompleteCalled.await(10, TimeUnit.SECONDS), "onComplete should have been invoked");
        // The Future settles only after the callback dispatch, so once it is done the
        // decision about onFailure has already been made — no polling window needed.
        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS),
                "a completion callback that blew up must reach the submitter through the Future — "
                        + "logging it inside BaseRuntime leaves nobody able to act on it");
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("completion callback blew up", thrown.getCause().getMessage());
        assertEquals(1, onFailureCalled.getCount(),
                "onFailure must not fire after the work already executed — callers read it as 'never ran' and re-execute");
        assertEquals(1, executions.get(), "the callable must be executed exactly once");
    }

    /**
     * Pair test for C9: the one-shot gate must not swallow a genuine failure — a
     * callable that throws still reaches onFailure exactly once.
     */
    @Test
    @Timeout(30)
    @DisplayName("C9: a genuinely failing callable still reaches onFailure exactly once")
    void failingCallableStillReportsFailureOnce() throws Exception {
        CountDownLatch onFailureCalled = new CountDownLatch(1);
        AtomicInteger failureCount = new AtomicInteger();

        Future<String> future = runtime.submitCallable(
                () -> {
                    throw new IllegalStateException("boom");
                },
                new IRuntime.IFinishedExecution<String>() {
                    @Override
                    public void onComplete(String result) {
                        fail("onComplete must not fire for a failing callable");
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        failureCount.incrementAndGet();
                        onFailureCalled.countDown();
                    }
                }, null);

        assertTrue(onFailureCalled.await(10, TimeUnit.SECONDS));
        assertNull(future.get(10, TimeUnit.SECONDS));
        assertEquals(1, failureCount.get());
    }

    /**
     * The abandonment token must not fire for a turn that finished before the
     * watchdog gave up — cancelling a completed Future is a no-op.
     */
    @Test
    @Timeout(30)
    @DisplayName("cancel() after completion does not retroactively invalidate the completed turn")
    void cancelAfterCompletionDoesNotAffectAlreadyDispatchedCallback() throws Exception {
        CountDownLatch onCompleteCalled = new CountDownLatch(1);
        CountDownLatch onFailureCalled = new CountDownLatch(1);

        Future<String> future = runtime.submitCallable(
                () -> "done",
                new IRuntime.IFinishedExecution<String>() {
                    @Override
                    public void onComplete(String result) {
                        onCompleteCalled.countDown();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        onFailureCalled.countDown();
                    }
                }, null);

        assertEquals("done", future.get(10, TimeUnit.SECONDS));
        assertTrue(onCompleteCalled.await(10, TimeUnit.SECONDS));

        assertFalse(future.cancel(true), "cancelling a completed Future must report false");
        assertEquals(1, onFailureCalled.getCount(), "no callback may fire a second time");
    }

    /**
     * Sanity check that the nested routing does not change the observable contract
     * for nested work: it still completes, still reports through the callback, and
     * still propagates its failures.
     */
    @Test
    @Timeout(30)
    @DisplayName("nested submissions keep the normal callback contract")
    void nestedSubmissionKeepsCallbackContract() throws Exception {
        CountDownLatch nestedCompleted = new CountDownLatch(1);

        Future<String> outer = runtime.submitCallable(() -> {
            Future<String> inner = runtime.submitCallable(
                    () -> "nested",
                    new IRuntime.IFinishedExecution<String>() {
                        @Override
                        public void onComplete(String result) {
                            nestedCompleted.countDown();
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            // not expected
                        }
                    }, null);
            return inner.get(20, TimeUnit.SECONDS);
        }, null);

        assertEquals("nested", outer.get(30, TimeUnit.SECONDS));
        assertTrue(nestedCompleted.await(10, TimeUnit.SECONDS));
    }

    /**
     * Guards the marker's lifetime: it is scoped to the work BODY, so a completion
     * callback that submits follow-up work (the coordinator scheduling the next
     * turn) is treated as a top-level submission and keeps using the bounded pool.
     */
    @Test
    @Timeout(30)
    @DisplayName("work submitted from a completion callback goes back to the bounded pool")
    void submissionFromCompletionCallbackIsNotTreatedAsNested() throws Exception {
        AtomicInteger poolSubmissions = new AtomicInteger();
        ManagedExecutor countingExecutor = Mockito.mock(ManagedExecutor.class);
        when(countingExecutor.submit(any(Callable.class))).thenAnswer(inv -> {
            poolSubmissions.incrementAndGet();
            return boundedPool.submit((Callable<?>) inv.getArgument(0));
        });
        var executorField = BaseRuntime.class.getDeclaredField("executorService");
        executorField.setAccessible(true);
        executorField.set(runtime, countingExecutor);

        CountDownLatch followUpDone = new CountDownLatch(1);

        runtime.submitCallable(
                () -> "first",
                new IRuntime.IFinishedExecution<String>() {
                    @Override
                    public void onComplete(String result) {
                        runtime.submitCallable(() -> {
                            followUpDone.countDown();
                            return null;
                        }, null);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        // not expected
                    }
                }, null);

        assertTrue(followUpDone.await(20, TimeUnit.SECONDS));
        assertEquals(2, poolSubmissions.get(),
                "both the original submission and the one made from its completion callback belong on the bounded pool");
    }

    /**
     * Documents the pre-fix failure mode explicitly: with a fully saturated pool,
     * an inner task submitted onto that SAME pool can never be scheduled. The
     * nested executor is what makes
     * {@link #nestedSubmissionsDoNotStarveTheBoundedPool} pass, and this test
     * proves the saturation is real rather than incidental.
     */
    @Test
    @Timeout(30)
    @DisplayName("baseline: the bounded pool really is saturated by the waiting outer tasks")
    void boundedPoolIsSaturatedByWaitingOuterTasks() throws Exception {
        CyclicBarrier allRunning = new CyclicBarrier(POOL_SIZE);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < POOL_SIZE; i++) {
            boundedPool.submit(() -> {
                allRunning.await(20, TimeUnit.SECONDS);
                release.await();
                return null;
            });
        }

        // With every pool thread parked, a direct pool submission cannot run.
        Future<String> starved = boundedPool.submit(() -> "never");
        assertThrows(TimeoutException.class, () -> starved.get(200, TimeUnit.MILLISECONDS));

        // The runtime's nested executor is unaffected by that saturation.
        release.countDown();
    }
}
