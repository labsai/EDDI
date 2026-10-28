/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What both mailbox state stores must do, against a real database
 * ({@link MongoEmailMailboxStateStoreIT},
 * {@link PostgresEmailMailboxStateStoreIT}).
 */
interface EmailMailboxStateStoreContract {

    IEmailMailboxStateStore store();

    void clear() throws Exception;

    @BeforeEach
    default void reset() throws Exception {
        clear();
    }

    Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    @DisplayName("the cursor round-trips, and a cursor write clears a recorded failure")
    default void cursor() {
        assertTrue(store().read("int-1").isEmpty());
        store().recordFailure("int-1", 3, NOW.plusSeconds(300), "CONNECTION: refused", NOW);
        var failed = store().read("int-1").orElseThrow();
        assertEquals(3, failed.failures());
        assertEquals(NOW.plusSeconds(300), failed.backoffUntil());
        assertEquals("CONNECTION: refused", failed.lastError());
        assertEquals(0, failed.uidValidity());

        store().saveCursor("int-1", "INBOX", 1792893887L, 42L, NOW.minusSeconds(60), NOW);
        var saved = store().read("int-1").orElseThrow();
        assertEquals("INBOX", saved.folder());
        assertEquals(1792893887L, saved.uidValidity());
        assertEquals(42L, saved.lastUid());
        assertEquals(NOW.minusSeconds(60), saved.since());
        assertEquals(0, saved.failures());
        assertNull(saved.backoffUntil());
        assertNull(saved.lastError());
        assertTrue(store().read("int-2").isEmpty());
    }

    @Test
    @DisplayName("spend adds up within a day, starts over on the next, and keeps the cursor")
    default void spend() {
        store().saveCursor("int-1", "INBOX", 7, 9, null, NOW);
        assertEquals(1.5, store().addSpend("int-1", "2026-10-25", 1.5, NOW), 1e-9);
        assertEquals(2.0, store().addSpend("int-1", "2026-10-25", 0.5, NOW), 1e-9);
        assertEquals(0.25, store().addSpend("int-1", "2026-10-26", 0.25, NOW), 1e-9);
        var state = store().read("int-1").orElseThrow();
        assertEquals("2026-10-26", state.budgetDay());
        assertEquals(9, state.lastUid());
        assertEquals(3.0, store().addSpend("int-new", "2026-10-25", 3.0, NOW), 1e-9, "an upsert for an integration not polled yet");
    }

    @Test
    @DisplayName("concurrent spend from several nodes is not lost")
    default void concurrentSpend() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Double>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                tasks.add(() -> store().addSpend("int-1", "2026-10-25", 0.25, NOW));
            }
            for (Future<Double> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(10.0, store().read("int-1").orElseThrow().spentUsd(), 1e-9);
    }

    @Test
    @DisplayName("a notice key is claimed once; deleting the integration forgets it all")
    default void noticesAndDelete() {
        assertTrue(store().claimNotice("int-1", "2026-10-25:budget", NOW));
        assertFalse(store().claimNotice("int-1", "2026-10-25:budget", NOW));
        assertTrue(store().claimNotice("int-1", "2026-10-25:throttled", NOW));
        assertTrue(store().claimNotice("int-2", "2026-10-25:budget", NOW));
        store().saveCursor("int-1", "INBOX", 1, 1, null, NOW);

        store().delete("int-1");

        assertTrue(store().read("int-1").isEmpty());
        assertTrue(store().claimNotice("int-1", "2026-10-25:budget", NOW), "forgotten with the integration");
        assertFalse(store().claimNotice("int-2", "2026-10-25:budget", NOW), "another integration's notices are kept");
    }
}
