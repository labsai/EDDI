/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Kind;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ISpendControlChangeStore} contract, run against a real database by
 * {@code MongoSpendControlChangeStoreIT} and
 * {@code PostgresSpendControlChangeStoreIT}, and against the in-memory double
 * by a unit test so the contract itself is checked without Docker. Every
 * implementation starts each test from an empty store.
 */
public interface SpendControlChangeStoreContract {

    ISpendControlChangeStore store();

    static SpendControlChange change(Status status, long proposedAt) {
        var catalog = new ModelPriceCatalog(4, UnpricedPolicy.ALLOW, null, null,
                List.of(new ModelPriceEntry("openai", "gpt-4o", 0.0, 0.0, null, null, null, null, "https://example.com/p", null)));
        return new SpendControlChange(UUID.randomUUID().toString(), Kind.MODEL_PRICES, status, null, 3L, null, catalog, null,
                List.of("changed openai/gpt-4o"), List.of("changed openai/gpt-4o: input 2.5 -> 0.0"), "cheaper", "assistant", "mcp", proposedAt,
                proposedAt + 1000, null, null, null, null, "hash-" + proposedAt);
    }

    @Test
    default void createdChangesReadBackWhole() {
        var quota = new SpendControlChange(UUID.randomUUID().toString(), Kind.TENANT_QUOTA, Status.PENDING, "acme", null,
                new TenantQuota("acme", 100, -1, 60, 50.0, true), null, new TenantQuota("acme", 100, -1, 60, 500.0, true),
                List.of("maxMonthlyCostUsd 50 -> 500"), List.of("maxMonthlyCostUsd 50 -> 500"), "bigger", "assistant", "mcp", 10, 20, null, null,
                null, null, "h");
        var prices = change(Status.PENDING, 11);
        store().create(quota);
        store().create(prices);

        assertEquals(quota, store().read(quota.id()).orElseThrow());
        assertEquals(prices, store().read(prices.id()).orElseThrow());
        assertTrue(store().read("absent").isEmpty());
    }

    @Test
    default void listsNewestFirstByStatusAndCounts() {
        var old = change(Status.PENDING, 100);
        var applied = change(Status.APPLIED, 200);
        var recent = change(Status.PENDING, 300);
        var applying = change(Status.APPLYING, 400);
        for (var c : List.of(old, applied, recent, applying)) {
            store().create(c);
        }

        assertEquals(List.of(recent.id(), old.id()), store().list(Status.PENDING, 10).stream().map(SpendControlChange::id).toList());
        assertEquals(List.of(applying.id(), recent.id(), applied.id(), old.id()),
                store().list(null, 10).stream().map(SpendControlChange::id).toList());
        assertEquals(List.of(applying.id()), store().list(null, 1).stream().map(SpendControlChange::id).toList());
        assertEquals(2, store().count(Status.PENDING));
        assertEquals(1, store().count(Status.APPLYING));
        assertEquals(0, store().count(Status.FAILED));
    }

    @Test
    default void compareAndSetMovesOnlyFromTheExpectedStatus() {
        var pending = change(Status.PENDING, 100);
        store().create(pending);

        var claimed = pending.decided(Status.APPLYING, "alice", 150, "ok", "applying");
        assertFalse(store().compareAndSetStatus(pending.id(), Status.APPLIED, claimed));
        assertTrue(store().compareAndSetStatus(pending.id(), Status.PENDING, claimed));
        assertFalse(store().compareAndSetStatus(pending.id(), Status.PENDING, pending.decided(Status.REJECTED, "bob", 160, null, "no")));
        assertEquals(claimed, store().read(pending.id()).orElseThrow());

        var applied = pending.decided(Status.APPLIED, "alice", 170, "ok", "applied as revision 5");
        assertTrue(store().compareAndSetStatus(pending.id(), Status.APPLYING, applied));
        assertEquals(Status.APPLIED, store().read(pending.id()).orElseThrow().status());
        assertEquals(1, store().list(Status.APPLIED, 10).size());
        assertEquals(0, store().count(Status.APPLYING));
        assertFalse(store().compareAndSetStatus("absent", Status.PENDING, applied));
    }

    @Test
    default void ofConcurrentClaimsExactlyOneWins() throws Exception {
        var pending = change(Status.PENDING, 100);
        store().create(pending);
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                var claim = pending.decided(Status.APPLYING, "admin-" + i, 150 + i, null, "applying");
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store().compareAndSetStatus(pending.id(), Status.PENDING, claim);
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Status.APPLYING, store().read(pending.id()).orElseThrow().status());
    }

    @Test
    default void retentionDeletesOnlyOldDecidedChanges() {
        var oldPending = change(Status.PENDING, 100);
        var oldApplying = change(Status.APPLYING, 100);
        var oldApplied = change(Status.APPLIED, 100);
        var oldFailed = change(Status.FAILED, 100);
        var newApplied = change(Status.APPLIED, 900);
        for (var c : List.of(oldPending, oldApplying, oldApplied, oldFailed, newApplied)) {
            store().create(c);
        }

        assertEquals(2, store().deleteDecidedBefore(500));

        assertTrue(store().read(oldPending.id()).isPresent());
        assertTrue(store().read(oldApplying.id()).isPresent(), "an interrupted approval must stay visible");
        assertTrue(store().read(oldApplied.id()).isEmpty());
        assertTrue(store().read(oldFailed.id()).isEmpty());
        assertTrue(store().read(newApplied.id()).isPresent());
    }
}
