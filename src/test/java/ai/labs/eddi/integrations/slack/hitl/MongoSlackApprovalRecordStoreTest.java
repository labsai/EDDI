/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-MongoDB tests for {@link MongoSlackApprovalRecordStore} — the store the
 * HITL decision binding depends on. Covers {@code tryRecord} atomicity (exactly
 * one winner under concurrency, via the unique index), idempotency, expiry
 * replacement, {@code findBySubject} scoping and {@code delete}.
 */
class MongoSlackApprovalRecordStoreTest extends MongoTestBase {

    private static final String INT = "acme-int";
    private static final String OTHER_INT = "other-int";
    private static final String SUBJECT = "conv-1";

    private MongoSlackApprovalRecordStore store;

    @BeforeEach
    void setUp() {
        dropCollections(MongoSlackApprovalRecordStore.COLLECTION);
        store = new MongoSlackApprovalRecordStore(getDatabase(), Duration.ofDays(30));
    }

    @Test
    void tryRecord_firstWins_secondForSamePauseIsSuppressed() {
        assertTrue(store.tryRecord(INT, SUBJECT, "1000", "card-1000", "C_APPROVAL"));
        assertFalse(store.tryRecord(INT, SUBJECT, "1000", "card-1000", "C_APPROVAL"),
                "a live record for the same (integration, subject, pause) must suppress a second card");
        // A different pause of the same subject gets its own record.
        assertTrue(store.tryRecord(INT, SUBJECT, "2000", "card-2000", "C_APPROVAL"));
    }

    @Test
    void tryRecord_isAtomic_exactlyOneWinnerUnderConcurrency() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger winners = new AtomicInteger();
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    if (store.tryRecord(INT, SUBJECT, "9000", "card-9000", "C_APPROVAL")) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<?> f : pool.invokeAll(tasks)) {
                f.get();
            }
            assertEquals(1, winners.get(),
                    "the unique index must make exactly one concurrent tryRecord win (no duplicate cards)");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredRecord_isReplaced_soANewCardCanBePostedAfterTheTtl() {
        // A store with an already-elapsed retention writes rows that are immediately
        // expired; the next tryRecord must therefore succeed (replace), not be blocked.
        var shortLived = new MongoSlackApprovalRecordStore(getDatabase(), Duration.ofMillis(1));
        assertTrue(shortLived.tryRecord(INT, "conv-ttl", "1000", "card-1000", "C_APPROVAL"));
        sleepPastExpiry();
        // Expired rows are not honoured by findBySubject...
        assertTrue(store.findBySubject(INT, "conv-ttl").isEmpty(), "an expired record must not be returned");
        // ...and a fresh record can replace it, carrying the NEW card's id.
        assertTrue(store.tryRecord(INT, "conv-ttl", "1000", "card-new", "C_APPROVAL"));
        assertEquals("card-new", store.findBySubject(INT, "conv-ttl").get(0).cardId());
    }

    @Test
    void findBySubject_isScopedToIntegrationAndSubject() {
        store.tryRecord(INT, SUBJECT, "1000", "card-1000", "C_APPROVAL");
        store.tryRecord(OTHER_INT, SUBJECT, "1000", "card-1000", "C_APPROVAL");
        store.tryRecord(INT, "other-subject", "1000", "card-1000", "C_APPROVAL");

        List<SlackApprovalRecord> mine = store.findBySubject(INT, SUBJECT);
        assertEquals(1, mine.size());
        assertEquals(INT, mine.get(0).integrationName());
        assertEquals(SUBJECT, mine.get(0).subject());
        assertTrue(mine.get(0).matchesPause(Instant.ofEpochMilli(1000)));
        // The card id round-trips, so the handler can bind a click to this card.
        assertEquals("card-1000", mine.get(0).cardId());
        assertTrue(mine.get(0).matchesCard("card-1000"));
        assertFalse(mine.get(0).matchesCard("card-2000"));
        // Another integration's card for the same subject is not visible here.
        assertEquals(1, store.findBySubject(OTHER_INT, SUBJECT).size());
    }

    private static void sleepPastExpiry() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void delete_removesTheRecord_soAFailedDeliveryCanRetry() {
        store.tryRecord(INT, SUBJECT, "1000", "card-1000", "C_APPROVAL");
        store.delete(INT, SUBJECT, "1000");
        assertTrue(store.findBySubject(INT, SUBJECT).isEmpty());
        // After delete, the same pause can be recorded again (retry path).
        assertTrue(store.tryRecord(INT, SUBJECT, "1000", "card-1000", "C_APPROVAL"));
    }
}
