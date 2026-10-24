/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.UnaryOperator;

/**
 * Runs a list of items on virtual threads, at most {@code maxConcurrency} at a
 * time, and returns one outcome per item <em>in the order of the items</em>,
 * whatever order they finish in.
 * <p>
 * Built for a step that calls something once per element (an httpcall's
 * {@code forEach}), where every item may be a billed call. Three rules follow
 * from that:
 * <ul>
 * <li><b>A failing item never cancels its siblings.</b> An item's failure is
 * its own outcome; the others run to completion.</li>
 * <li><b>Work in flight is never interrupted.</b> The pool is shut down, never
 * {@code shutdownNow()}: interrupting a call whose request already left would
 * throw away a result that may already have been paid for.</li>
 * <li><b>Nothing starts after the caller stopped waiting.</b> When the wait
 * budget runs out, items still queued are not started at all, so no request is
 * sent whose result nobody would collect.</li>
 * </ul>
 * Stateless: everything about one run arrives as arguments.
 */
public final class BoundedFanOut {

    private BoundedFanOut() {
    }

    /**
     * Decides, right before an item starts, whether it may — the turn deadline
     * today, and the seam for a per-conversation cost reservation: a gate that
     * reserves an item's expected price in {@link #refuse} and settles it in
     * {@link #finished} keeps concurrent items from all passing the same check.
     * Called on the item's own thread, so an implementation must be thread-safe.
     */
    public interface Admission {

        /** Admits every item. */
        Admission ALWAYS = index -> null;

        /**
         * @return {@code null} to start the item, otherwise an EDDI-written reason it
         *         is not started (never a value from the data it carries)
         */
        String refuse(int index);

        /**
         * Called once for every admitted item after it finished, whatever its outcome —
         * the place to release what {@link #refuse} reserved.
         */
        default void finished(int index) {
        }

        /**
         * This admission, then {@code next}: an item starts only when both admit it,
         * and {@code next} is asked only when this one did.
         */
        default Admission andThen(Admission next) {
            Admission first = this;
            return new Admission() {
                @Override
                public String refuse(int index) {
                    String refusal = first.refuse(index);
                    if (refusal != null) {
                        return refusal;
                    }
                    String second = next.refuse(index);
                    if (second != null) {
                        first.finished(index);
                    }
                    return second;
                }

                @Override
                public void finished(int index) {
                    try {
                        next.finished(index);
                    } finally {
                        first.finished(index);
                    }
                }
            };
        }
    }

    /** Builds the outcome of an item that produced none of its own. */
    public interface Outcomes<T> {

        /** The item was not started: {@code reason} is the admission's refusal. */
        T notStarted(int index, String reason);

        /** The item threw instead of returning an outcome. */
        T failed(int index, Throwable failure);

        /**
         * The caller stopped waiting before the item finished.
         *
         * @param started
         *            whether it had started — if so its call may still complete, and
         *            its result is unknown rather than absent
         */
        T unfinished(int index, boolean started);
    }

    /**
     * Runs {@code items}.
     *
     * @param maxConcurrency
     *            most items in flight at once; values below 1 mean 1
     * @param admission
     *            asked before each item starts
     * @param outcomes
     *            builds the outcomes the items could not produce themselves
     * @param wrap
     *            applied to each item's work before it is handed to its thread —
     *            the place to carry the turn's thread-bound context over
     * @param waitBudgetMs
     *            how long to wait for the whole run; {@code <= 0} waits until every
     *            item finished
     * @param threadName
     *            prefix of the virtual threads' names
     * @return one outcome per item, in the order of {@code items}
     */
    public static <T> List<T> run(List<Callable<T>> items, int maxConcurrency, Admission admission, Outcomes<T> outcomes,
                                  UnaryOperator<Callable<T>> wrap, long waitBudgetMs, String threadName) {
        int count = items.size();
        if (count == 0) {
            return List.of();
        }
        int threads = Math.max(1, Math.min(maxConcurrency, count));
        Admission gate = admission != null ? admission : Admission.ALWAYS;
        AtomicBoolean closed = new AtomicBoolean(false);
        // 0 queued, 1 started, 2 finished
        AtomicIntegerArray states = new AtomicIntegerArray(count);

        ExecutorService pool = Executors.newFixedThreadPool(threads, Thread.ofVirtual().name(threadName + "-", 0).factory());
        List<Future<T>> futures = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                Callable<T> work = items.get(index);
                Callable<T> body = () -> {
                    if (closed.get()) {
                        states.set(index, 2);
                        return outcomes.notStarted(index, "the step stopped waiting before this item could start");
                    }
                    String refusal = gate.refuse(index);
                    if (refusal != null) {
                        states.set(index, 2);
                        return outcomes.notStarted(index, refusal);
                    }
                    states.set(index, 1);
                    try {
                        return work.call();
                    } catch (Throwable failure) {
                        return outcomes.failed(index, failure);
                    } finally {
                        states.set(index, 2);
                        gate.finished(index);
                    }
                };
                futures.add(pool.submit(wrap != null ? wrap.apply(body) : body));
            }
        } finally {
            // Not shutdownNow(): an item in flight may be a request that already left.
            pool.shutdown();
        }

        long waitUntil = waitBudgetMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitBudgetMs) : Long.MAX_VALUE;
        List<T> results = new ArrayList<>(count);
        boolean stopWaiting = false;
        for (int i = 0; i < count; i++) {
            Future<T> future = futures.get(i);
            if (stopWaiting && !future.isDone()) {
                results.add(outcomes.unfinished(i, states.get(i) == 1));
                continue;
            }
            try {
                T result;
                if (waitUntil == Long.MAX_VALUE) {
                    result = future.get();
                } else {
                    result = future.get(Math.max(0L, waitUntil - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
                results.add(result);
            } catch (TimeoutException e) {
                closed.set(true);
                stopWaiting = true;
                results.add(outcomes.unfinished(i, states.get(i) == 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closed.set(true);
                stopWaiting = true;
                results.add(outcomes.unfinished(i, states.get(i) == 1));
            } catch (ExecutionException e) {
                results.add(outcomes.failed(i, e.getCause() != null ? e.getCause() : e));
            }
        }
        return results;
    }
}
