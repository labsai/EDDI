/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpendReservations — paid calls in flight held against the conversation's ceiling")
class SpendReservationsTest {

    private static final double EPS = 1e-12;

    @Nested
    @DisplayName("the admission rule")
    class Rule {

        @Test
        @DisplayName("admits below the ceiling and holds the expected cost")
        void admitsAndHolds() {
            var reservations = new SpendReservations();

            Optional<SpendReservations.Reservation> r = reservations.tryReserve(0.40, 1.00, 0.30);

            assertTrue(r.isPresent());
            assertEquals(0.30, reservations.heldUsd(), EPS);
            assertEquals(1, reservations.openReservations());
            assertEquals(0.30, r.get().amountUsd(), EPS);
        }

        @Test
        @DisplayName("refuses once the settled spend alone reaches the ceiling")
        void refusesAtTheCeiling() {
            var reservations = new SpendReservations();

            assertTrue(reservations.tryReserve(1.00, 1.00, 0.0).isEmpty());
            assertEquals(0, reservations.openReservations());
        }

        @Test
        @DisplayName("the call that crosses the ceiling is still admitted: its own expected cost is not compared")
        void crossingCallIsAdmitted() {
            var reservations = new SpendReservations();

            assertTrue(reservations.tryReserve(0.90, 1.00, 5.00).isPresent());
        }

        @Test
        @DisplayName("what others hold counts: a second call is refused while the first holds the rest of the budget")
        void heldAmountsCount() {
            var reservations = new SpendReservations();
            var first = reservations.tryReserve(0.50, 1.00, 0.50);

            assertTrue(first.isPresent());
            assertTrue(reservations.tryReserve(0.50, 1.00, 0.10).isEmpty(), "0.50 spent + 0.50 held reaches 1.00");
        }

        @Test
        @DisplayName("a ceiling of 0 admits nothing")
        void zeroCeiling() {
            assertTrue(new SpendReservations().tryReserve(0.0, 0.0, 0.0).isEmpty());
        }

        @Test
        @DisplayName("negative, NaN and infinite expected costs hold nothing")
        void badExpectedCostsHoldNothing() {
            var reservations = new SpendReservations();
            reservations.tryReserve(0.0, 1.0, -1.0);
            reservations.tryReserve(0.0, 1.0, Double.NaN);
            reservations.tryReserve(0.0, 1.0, Double.POSITIVE_INFINITY);

            assertEquals(0.0, reservations.heldUsd(), EPS);
            assertEquals(3, reservations.openReservations());
        }
    }

    @Nested
    @DisplayName("settle and close")
    class Lifecycle {

        @Test
        @DisplayName("settle replaces the expected cost with the actual one and keeps holding it")
        void settleReplaces() {
            var reservations = new SpendReservations();
            var r = reservations.tryReserve(0.0, 1.0, 0.30).orElseThrow();

            r.settle(0.10);

            assertEquals(0.10, reservations.heldUsd(), EPS);
            assertEquals(0.10, r.amountUsd(), EPS);
            assertEquals(1, reservations.openReservations());
        }

        @Test
        @DisplayName("close releases what is held, and closing twice releases once")
        void closeReleasesOnce() {
            var reservations = new SpendReservations();
            var a = reservations.tryReserve(0.0, 1.0, 0.30).orElseThrow();
            var b = reservations.tryReserve(0.0, 1.0, 0.20).orElseThrow();

            a.close();
            a.close();

            assertEquals(0.20, reservations.heldUsd(), EPS);
            assertEquals(1, reservations.openReservations());
            assertEquals(0.0, a.amountUsd(), EPS);
            b.close();
            assertEquals(0.0, reservations.heldUsd(), EPS);
            assertEquals(0, reservations.openReservations());
        }

        @Test
        @DisplayName("settle after close changes nothing")
        void settleAfterClose() {
            var reservations = new SpendReservations();
            var r = reservations.tryReserve(0.0, 1.0, 0.30).orElseThrow();
            r.close();

            r.settle(0.50);

            assertEquals(0.0, reservations.heldUsd(), EPS);
        }

        @Test
        @DisplayName("a failed call releases its reservation when closed in finally")
        void failureReleases() {
            var reservations = new SpendReservations();

            assertThrows(IllegalStateException.class, () -> {
                try (var r = reservations.tryReserve(0.0, 1.0, 0.60).orElseThrow()) {
                    assertEquals(0.60, reservations.heldUsd(), EPS);
                    throw new IllegalStateException("provider down");
                }
            });

            assertEquals(0.0, reservations.heldUsd(), EPS);
            assertTrue(reservations.tryReserve(0.50, 1.0, 0.60).isPresent(), "the released budget is available again");
        }

        @Test
        @DisplayName("released budget admits the next call that was refused while it was held")
        void releasedBudgetAdmitsAgain() {
            var reservations = new SpendReservations();
            var first = reservations.tryReserve(0.50, 1.00, 0.50).orElseThrow();
            assertTrue(reservations.tryReserve(0.50, 1.00, 0.10).isEmpty());

            first.close();

            assertTrue(reservations.tryReserve(0.50, 1.00, 0.10).isPresent());
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @RepeatedTest(20)
        @DisplayName("two threads racing one ceiling: exactly one passes")
        void twoRacersOnePasses() throws Exception {
            var reservations = new SpendReservations();
            var barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    results.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        // 0.60 spent, 0.40 expected each: either one alone may start,
                        // both together would overrun 1.00 at once.
                        return reservations.tryReserve(0.60, 1.00, 0.40).isPresent();
                    }));
                }
                int passed = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        passed++;
                    }
                }
                assertEquals(1, passed);
                assertEquals(0.40, reservations.heldUsd(), EPS);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("many threads: the admitted calls never hold more than the ceiling leaves, and all of it is released")
        void manyRacersStayBounded() throws Exception {
            var reservations = new SpendReservations();
            int threads = 16;
            var start = new CountDownLatch(1);
            var admitted = new AtomicInteger();
            var maxSeen = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < 500; i++) {
                            // 0.0 spent, ceiling 1.0, 0.25 each: at most 4 in flight at once
                            var r = reservations.tryReserve(0.0, 1.0, 0.25);
                            if (r.isPresent()) {
                                admitted.incrementAndGet();
                                maxSeen.accumulateAndGet(reservations.openReservations(), Math::max);
                                assertFalse(reservations.heldUsd() > 1.0 + EPS, "held more than the ceiling");
                                r.get().close();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(admitted.get() > 0);
            assertTrue(maxSeen.get() <= 4, "at most four 0.25 reservations fit under 1.00, saw " + maxSeen.get());
            assertEquals(0.0, reservations.heldUsd(), EPS);
            assertEquals(0, reservations.openReservations());
        }
    }
}
