/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.engine.groups.budget.ITeamBudgetStore.AdjustOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ITeamBudgetStore} contract, run against a real database by each
 * implementation's {@code *IT} and against the in-memory double by a unit test
 * (so the contract itself is checked without Docker). Every test uses a group
 * id of its own.
 */
public interface TeamBudgetStoreContract {

    String MONTH = "2026-11";

    ITeamBudgetStore store();

    static String fresh() {
        return "group-" + UUID.randomUUID();
    }

    static Instant later() {
        return Instant.now().plusSeconds(3600L * 24 * 400);
    }

    static TeamBudgetMonth.Adjustment entry(String kind, double delta) {
        return new TeamBudgetMonth.Adjustment(Instant.parse("2026-11-02T10:00:00Z"), "admin", kind, delta, 0.0, "because", null, null);
    }

    @Test
    default void addSpendReturnsTheRunningTotal() {
        String group = fresh();
        assertEquals(1.5, store().addSpend(group, MONTH, 1.5, later()), 1e-9);
        assertEquals(2.0, store().addSpend(group, MONTH, 0.5, later()), 1e-9);
        assertEquals(2.0, store().read(group, MONTH).orElseThrow().spentUsd(), 1e-9);
        assertEquals(0.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
    }

    @Test
    default void anAbsentMonthReadsEmptyAndMonthsAreIndependent() {
        String group = fresh();
        assertTrue(store().read(group, MONTH).isEmpty());
        store().addSpend(group, MONTH, 1.0, later());
        assertTrue(store().read(group, "2026-12").isEmpty());
        store().addSpend(group, "2026-12", 3.0, later());
        assertEquals(1.0, store().read(group, MONTH).orElseThrow().spentUsd(), 1e-9);
    }

    @Test
    default void concurrentSpendFromManyThreadsSumsExactly() throws Exception {
        String group = fresh();
        int threads = 16;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    store().addSpend(group, MONTH, 0.25, later());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(threads * each * 0.25, store().read(group, MONTH).orElseThrow().spentUsd(), 1e-9);
    }

    @Test
    default void aCreditIsAlwaysApplied() {
        String group = fresh();
        assertEquals(AdjustOutcome.APPLIED, store().applyAdjustment(group, MONTH, 5.0, 10.0, entry("TRANSFER_IN", 5.0), "t1", later()));
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(5.0, month.adjustmentUsd(), 1e-9);
        assertEquals(1, month.adjustments().size());
        assertEquals("TRANSFER_IN", month.adjustments().get(0).kind());
        assertEquals(List.of("t1"), month.appliedTransferIds());
    }

    @Test
    default void aDebitIsRefusedBelowTheSpendAndAppliedAtExactlyTheSpend() {
        String group = fresh();
        store().addSpend(group, MONTH, 8.0, later());
        // base 10, spent 8: giving up 3 would leave a cap of 7 < 8
        assertEquals(AdjustOutcome.REFUSED, store().applyAdjustment(group, MONTH, -3.0, 10.0, entry("TRANSFER_OUT", -3.0), "t1", later()));
        assertEquals(0.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
        // giving up exactly 2 leaves a cap of 8 == spent: allowed
        assertEquals(AdjustOutcome.APPLIED, store().applyAdjustment(group, MONTH, -2.0, 10.0, entry("TRANSFER_OUT", -2.0), "t2", later()));
        assertEquals(-2.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
    }

    @Test
    default void aDebitOnAMonthWithNoSpendYetWorks() {
        String group = fresh();
        assertEquals(AdjustOutcome.APPLIED, store().applyAdjustment(group, MONTH, -4.0, 10.0, entry("TRANSFER_OUT", -4.0), null, later()));
        assertEquals(-4.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
    }

    @Test
    default void aRepeatedTransferIdIsADuplicateAndChangesNothing() {
        String group = fresh();
        assertEquals(AdjustOutcome.APPLIED, store().applyAdjustment(group, MONTH, 2.0, 10.0, entry("TRANSFER_IN", 2.0), "same", later()));
        assertEquals(AdjustOutcome.DUPLICATE, store().applyAdjustment(group, MONTH, 2.0, 10.0, entry("TRANSFER_IN", 2.0), "same", later()));
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(2.0, month.adjustmentUsd(), 1e-9);
        assertEquals(1, month.adjustments().size());
    }

    @Test
    default void concurrentDebitsCannotBothPass() throws Exception {
        String group = fresh();
        store().addSpend(group, MONTH, 5.0, later());
        // base 10, spent 5: room for exactly one debit of 3 (cap 7), not two (cap 4 <
        // 5)
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AdjustOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = "t" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return store().applyAdjustment(group, MONTH, -3.0, 10.0, entry("TRANSFER_OUT", -3.0), id, later());
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<AdjustOutcome> f : futures) {
            if (f.get() == AdjustOutcome.APPLIED) {
                applied++;
            }
        }
        pool.shutdown();
        assertEquals(1, applied);
        assertEquals(-3.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
    }

    @Test
    default void aSetReplacesTheAdjustmentItWasReadWithAndRecordsTheEntry() {
        String group = fresh();
        assertEquals(AdjustOutcome.APPLIED, store().setAdjustment(group, MONTH, 0.0, 15.0, 10.0, entry("SET", 15.0), later()),
                "a month not stored yet has an adjustment of 0");
        assertEquals(AdjustOutcome.APPLIED, store().setAdjustment(group, MONTH, 15.0, 5.0, 10.0, entry("SET", -10.0), later()));
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(5.0, month.adjustmentUsd(), 1e-9, "set, not added");
        assertEquals(List.of("SET", "SET"), month.adjustments().stream().map(TeamBudgetMonth.Adjustment::kind).toList());
        assertTrue(month.appliedTransferIds().isEmpty());
    }

    @Test
    default void aSetFromAStaleReadIsAConflictAndChangesNothing() {
        String group = fresh();
        store().setAdjustment(group, MONTH, 0.0, 20.0, 10.0, entry("SET", 20.0), later());
        assertEquals(AdjustOutcome.CONFLICT, store().setAdjustment(group, MONTH, 0.0, 40.0, 10.0, entry("SET", 40.0), later()));
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(20.0, month.adjustmentUsd(), 1e-9);
        assertEquals(1, month.adjustments().size());
    }

    @Test
    default void aSetBelowTheSpendIsRefusedAndAtExactlyTheSpendApplied() {
        String group = fresh();
        store().addSpend(group, MONTH, 8.0, later());
        // base 10: an adjustment of -3 is a cap of 7 < 8
        assertEquals(AdjustOutcome.REFUSED, store().setAdjustment(group, MONTH, 0.0, -3.0, 10.0, entry("SET", -3.0), later()));
        assertEquals(0.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
        assertEquals(AdjustOutcome.APPLIED, store().setAdjustment(group, MONTH, 0.0, -2.0, 10.0, entry("SET", -2.0), later()));
        assertEquals(-2.0, store().read(group, MONTH).orElseThrow().adjustmentUsd(), 1e-9);
    }

    @Test
    default void concurrentSetsFromTheSameReadApplyOnce() throws Exception {
        String group = fresh();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AdjustOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double adjustment = 10.0 + i;
            futures.add(pool.submit(() -> {
                start.await();
                return store().setAdjustment(group, MONTH, 0.0, adjustment, 10.0, entry("SET", adjustment), later());
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<AdjustOutcome> f : futures) {
            AdjustOutcome outcome = f.get();
            if (outcome == AdjustOutcome.APPLIED) {
                applied++;
            } else {
                assertEquals(AdjustOutcome.CONFLICT, outcome);
            }
        }
        pool.shutdown();
        assertEquals(1, applied);
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(month.adjustments().get(0).deltaUsd(), month.adjustmentUsd(), 1e-9, "the stored adjustment is the one that applied");
        assertEquals(1, month.adjustments().size());
    }

    @Test
    default void historyAndTransferIdsStayBounded() {
        String group = fresh();
        for (int i = 0; i < ITeamBudgetStore.MAX_ADJUSTMENTS + 20; i++) {
            store().applyAdjustment(group, MONTH, 0.01, 10.0, entry("TRANSFER_IN", 0.01), "id-" + i, later());
        }
        var month = store().read(group, MONTH).orElseThrow();
        assertEquals(ITeamBudgetStore.MAX_ADJUSTMENTS, month.adjustments().size());
        assertTrue(month.appliedTransferIds().size() <= ITeamBudgetStore.MAX_TRANSFER_IDS);
        assertTrue(month.appliedTransferIds().contains("id-" + (ITeamBudgetStore.MAX_ADJUSTMENTS + 19)), "newest is kept");
    }

    @Test
    default void readManyAndHistoryReturnWhatExists() {
        String a = fresh();
        String b = fresh();
        store().addSpend(a, MONTH, 1.0, later());
        store().addSpend(b, MONTH, 2.0, later());
        store().addSpend(a, "2026-10", 9.0, later());
        assertEquals(2, store().readMany(List.of(a, b, fresh()), MONTH).size());
        assertTrue(store().readMany(List.of(), MONTH).isEmpty());
        var history = store().history(a, 6);
        assertEquals(List.of("2026-11", "2026-10"), history.stream().map(TeamBudgetMonth::month).toList());
        assertEquals(1, store().history(a, 1).size());
    }

    @Test
    default void expiredMonthsAreDeleted() {
        String group = fresh();
        store().addSpend(group, MONTH, 1.0, Instant.now().minusSeconds(3600));
        assertTrue(store().deleteExpired(Instant.now()) >= 1);
        assertFalse(store().read(group, MONTH).isPresent());
    }
}
