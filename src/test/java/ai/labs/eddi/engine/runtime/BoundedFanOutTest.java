/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BoundedFanOut")
class BoundedFanOutTest {

    private static final BoundedFanOut.Outcomes<String> OUTCOMES = new BoundedFanOut.Outcomes<>() {
        @Override
        public String notStarted(int index, String reason) {
            return "not-started:" + index + ":" + reason;
        }

        @Override
        public String failed(int index, Throwable failure) {
            return "failed:" + index + ":" + failure.getMessage();
        }

        @Override
        public String unfinished(int index, boolean started) {
            return "unfinished:" + index + ":" + started;
        }
    };

    @Nested
    @DisplayName("ordering and concurrency")
    class OrderingAndConcurrency {

        @Test
        @DisplayName("outcomes come back in item order, whatever order the items finish in")
        void ordered() {
            List<Callable<String>> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final int n = i;
                items.add(() -> {
                    Thread.sleep(10L * (5 - n));
                    return "done:" + n;
                });
            }

            List<String> results = BoundedFanOut.run(items, 5, null, OUTCOMES, null, 0, "test");

            assertEquals(List.of("done:0", "done:1", "done:2", "done:3", "done:4"), results);
        }

        @Test
        @DisplayName("never more than maxConcurrency items in flight")
        void capped() {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            List<Callable<String>> items = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                items.add(() -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(15);
                    inFlight.decrementAndGet();
                    return "ok";
                });
            }

            BoundedFanOut.run(items, 4, null, OUTCOMES, null, 0, "test");

            assertEquals(4, peak.get());
        }

        @Test
        @DisplayName("a value below 1 runs one at a time; an empty list returns an empty list")
        void degenerate() {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            List<Callable<String>> items = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                items.add(() -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Thread.sleep(5);
                    inFlight.decrementAndGet();
                    return "ok";
                });
            }

            BoundedFanOut.run(items, 0, null, OUTCOMES, null, 0, "test");

            assertEquals(1, peak.get());
            assertEquals(List.of(), BoundedFanOut.run(List.<Callable<String>>of(), 3, null, OUTCOMES, null, 0, "test"));
        }

        @Test
        @DisplayName("every item's work goes through the wrapper")
        void wrapped() {
            AtomicInteger wrapped = new AtomicInteger();
            List<Callable<String>> items = List.of(() -> "a", () -> "b");

            BoundedFanOut.run(items, 2, null, OUTCOMES, work -> {
                wrapped.incrementAndGet();
                return work;
            }, 0, "test");

            assertEquals(2, wrapped.get());
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a failing item is its own outcome; its siblings still run")
        void failureIsIsolated() {
            AtomicInteger ran = new AtomicInteger();
            List<Callable<String>> items = List.of(() -> {
                ran.incrementAndGet();
                return "a";
            }, () -> {
                throw new IllegalStateException("boom");
            }, () -> {
                ran.incrementAndGet();
                return "c";
            });

            List<String> results = BoundedFanOut.run(items, 1, null, OUTCOMES, null, 0, "test");

            assertEquals(List.of("a", "failed:1:boom", "c"), results);
            assertEquals(2, ran.get());
        }

        @Test
        @DisplayName("a refused item is not started, and finished() is called only for admitted ones")
        void admission() {
            AtomicInteger finished = new AtomicInteger();
            AtomicInteger ran = new AtomicInteger();
            BoundedFanOut.Admission everyOther = new BoundedFanOut.Admission() {
                @Override
                public String refuse(int index) {
                    return index % 2 == 1 ? "odd" : null;
                }

                @Override
                public void finished(int index) {
                    finished.incrementAndGet();
                }
            };
            List<Callable<String>> items = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                items.add(() -> "ran:" + ran.incrementAndGet());
            }

            List<String> results = BoundedFanOut.run(items, 2, everyOther, OUTCOMES, null, 0, "test");

            assertEquals("not-started:1:odd", results.get(1));
            assertEquals("not-started:3:odd", results.get(3));
            assertEquals(2, ran.get());
            assertEquals(2, finished.get());
        }

        @Test
        @DisplayName("andThen: both must admit; the first is released when the second refuses")
        void chainedAdmission() {
            AtomicInteger firstReleased = new AtomicInteger();
            BoundedFanOut.Admission first = new BoundedFanOut.Admission() {
                @Override
                public String refuse(int index) {
                    return null;
                }

                @Override
                public void finished(int index) {
                    firstReleased.incrementAndGet();
                }
            };
            BoundedFanOut.Admission second = index -> index == 0 ? "no" : null;

            List<String> results = BoundedFanOut.run(List.of(() -> "a", () -> "b"), 1, first.andThen(second), OUTCOMES, null, 0, "test");

            assertEquals(List.of("not-started:0:no", "b"), results);
            assertEquals(2, firstReleased.get(), "released once for the refused item, once for the finished one");
        }
    }

    @Nested
    @DisplayName("wait budget")
    class WaitBudget {

        @Test
        @DisplayName("past the budget a running item is unfinished, and queued items never start")
        void queuedItemsDoNotStartAfterTheCallerStoppedWaiting() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger started = new AtomicInteger();
            List<Callable<String>> items = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                items.add(() -> {
                    started.incrementAndGet();
                    release.await(5, TimeUnit.SECONDS);
                    return "ok";
                });
            }

            List<String> results = BoundedFanOut.run(items, 1, null, OUTCOMES, null, 100, "test");
            release.countDown();
            Thread.sleep(200);

            assertEquals("unfinished:0:true", results.get(0));
            assertEquals("unfinished:1:false", results.get(1));
            assertEquals("unfinished:2:false", results.get(2));
            assertEquals(1, started.get(), "nothing starts after the caller gave up waiting");
        }

        @Test
        @DisplayName("items that finish within the budget are returned as usual")
        void withinBudget() {
            List<String> results = BoundedFanOut.run(List.of(() -> "a", () -> "b"), 2, null, OUTCOMES, null, 5_000, "test");
            assertEquals(List.of("a", "b"), results);
            assertTrue(results.stream().noneMatch(r -> r.startsWith("unfinished")));
        }
    }
}
