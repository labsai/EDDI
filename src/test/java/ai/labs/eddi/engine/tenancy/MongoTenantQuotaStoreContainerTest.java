/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link MongoTenantQuotaStore} against a real MongoDB
 * server (Testcontainers).
 * <p>
 * These exist because {@code MongoTenantQuotaStoreTest} mocks
 * {@code MongoCollection} and is therefore structurally incapable of catching
 * the defect this class pins: {@code tenant_usage} carries a
 * {@code unique(true)} index on {@code tenantId}, and an upsert whose filter
 * also pins a rolling window ({@code dayStart} / {@code minuteStart} /
 * {@code costMonth}) misses the tenant's existing document and attempts a
 * second insert, which the server rejects with an E11000 duplicate-key error.
 * Against a mock, that interaction simply does not exist.
 *
 * @since 6.0.0
 */
class MongoTenantQuotaStoreContainerTest extends MongoTestBase {

    private static final String USAGE_COLLECTION = "tenant_usage";
    private static final String TENANT_ID = "tenant-container";

    private MongoTenantQuotaStore sut;
    private MongoCollection<Document> usage;

    @BeforeEach
    void resetState() {
        dropCollections("tenant_quotas", USAGE_COLLECTION);
        // Re-created per test: the constructor is what (re)builds the unique index
        // that the dropped collection just lost.
        sut = new MongoTenantQuotaStore(getDatabase());
        usage = getDatabase().getCollection(USAGE_COLLECTION);
    }

    private long usageDocumentCount() {
        return usage.countDocuments(Filters.eq("tenantId", TENANT_ID));
    }

    // ─── Single-document accounting ────────────────────────────────────────────

    @Nested
    @DisplayName("single tenant_usage document per tenant")
    class SingleDocument {

        @Test
        @DisplayName("api-call accounting must not collide with conversation accounting")
        void conversationsThenApiCalls() {
            assertTrue(sut.tryIncrementConversations(TENANT_ID, 10).allowed());

            // Before the fix this threw MongoWriteException (E11000 duplicate key):
            // the upsert filter pinned `minuteStart`, missed the document written by
            // tryIncrementConversations, and tried to insert a second one.
            QuotaCheckResult apiResult = assertDoesNotThrow(() -> sut.tryIncrementApiCalls(TENANT_ID, 60));

            assertTrue(apiResult.allowed());
            assertEquals(1, usageDocumentCount(), "both counters must live in one document");

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);
            assertEquals(1, snapshot.conversationsToday());
            assertEquals(1, snapshot.apiCallsThisMinute());
        }

        @Test
        @DisplayName("cost accounting must not collide with conversation accounting")
        void conversationsThenCost() {
            assertTrue(sut.tryIncrementConversations(TENANT_ID, 10).allowed());

            QuotaCheckResult costResult = assertDoesNotThrow(() -> sut.tryAddCost(TENANT_ID, 5.0, 100.0));

            assertTrue(costResult.allowed());
            assertEquals(1, usageDocumentCount());
            assertEquals(5.0, sut.getMonthlyCost(TENANT_ID),
                    "cost must be readable back — it used to be lost with the failed insert");
        }

        @Test
        @DisplayName("all three counters survive interleaved accounting in one document")
        void allThreeCountersInterleaved() {
            assertDoesNotThrow((Executable) () -> {
                sut.tryAddCost(TENANT_ID, 1.0, 100.0);
                sut.tryIncrementApiCalls(TENANT_ID, 60);
                sut.tryIncrementConversations(TENANT_ID, 10);
                sut.tryIncrementApiCalls(TENANT_ID, 60);
                sut.tryAddCost(TENANT_ID, 2.5, 100.0);
                sut.tryIncrementConversations(TENANT_ID, 10);
            });

            assertEquals(1, usageDocumentCount());

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);
            assertEquals(2, snapshot.conversationsToday());
            assertEquals(2, snapshot.apiCallsThisMinute());
            assertEquals(3.5, snapshot.monthlyCostUsd(), 1e-9);
        }
    }

    // ─── Boundary enforcement ──────────────────────────────────────────────────

    @Nested
    @DisplayName("limit boundary")
    class Boundary {

        @Test
        @DisplayName("conversations: allowed up to the limit, denied at the limit")
        void conversationsAtLimit() {
            for (int i = 1; i <= 3; i++) {
                assertTrue(sut.tryIncrementConversations(TENANT_ID, 3).allowed(), "call " + i + " must be allowed");
            }

            QuotaCheckResult denied = sut.tryIncrementConversations(TENANT_ID, 3);

            assertFalse(denied.allowed());
            assertEquals(3, sut.getUsage(TENANT_ID).conversationsToday(), "a denied request must not increment");
        }

        @Test
        @DisplayName("api calls: allowed up to the limit, denied at the limit")
        void apiCallsAtLimit() {
            for (int i = 1; i <= 2; i++) {
                assertTrue(sut.tryIncrementApiCalls(TENANT_ID, 2).allowed(), "call " + i + " must be allowed");
            }

            assertFalse(sut.tryIncrementApiCalls(TENANT_ID, 2).allowed());
            assertEquals(2, sut.getUsage(TENANT_ID).apiCallsThisMinute());
        }

        @Test
        @DisplayName("a limit of zero denies the very first request")
        void zeroLimitDeniesImmediately() {
            assertFalse(sut.tryIncrementConversations(TENANT_ID, 0).allowed());
            assertFalse(sut.tryIncrementApiCalls(TENANT_ID, 0).allowed());
        }

        @Test
        @DisplayName("a negative limit means unlimited and writes nothing")
        void negativeLimitIsUnlimited() {
            assertTrue(sut.tryIncrementConversations(TENANT_ID, -1).allowed());
            assertTrue(sut.tryIncrementApiCalls(TENANT_ID, -1).allowed());
            assertEquals(0, usageDocumentCount());
        }
    }

    // ─── Window rollover ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("window rollover")
    class Rollover {

        @Test
        @DisplayName("an expired day window resets the conversation counter")
        void expiredDayWindowResets() {
            assertTrue(sut.tryIncrementConversations(TENANT_ID, 1).allowed());
            assertFalse(sut.tryIncrementConversations(TENANT_ID, 1).allowed());

            usage.updateOne(Filters.eq("tenantId", TENANT_ID), Updates.set("dayStart", 0L));

            assertTrue(sut.tryIncrementConversations(TENANT_ID, 1).allowed(), "new day, new budget");
            assertEquals(1, sut.getUsage(TENANT_ID).conversationsToday());
            assertEquals(1, usageDocumentCount());
        }

        @Test
        @DisplayName("an expired minute window resets the api-call counter")
        void expiredMinuteWindowResets() {
            assertTrue(sut.tryIncrementApiCalls(TENANT_ID, 1).allowed());
            assertFalse(sut.tryIncrementApiCalls(TENANT_ID, 1).allowed());

            usage.updateOne(Filters.eq("tenantId", TENANT_ID), Updates.set("minuteStart", 0L));

            assertTrue(sut.tryIncrementApiCalls(TENANT_ID, 1).allowed());
            assertEquals(1, sut.getUsage(TENANT_ID).apiCallsThisMinute());
        }

        @Test
        @DisplayName("a stale cost month is reset rather than accumulated")
        void staleCostMonthResets() {
            usage.insertOne(new Document("tenantId", TENANT_ID)
                    .append("conversationsToday", 0)
                    .append("dayStart", 0L)
                    .append("apiCallsThisMinute", 0)
                    .append("minuteStart", 0L)
                    .append("monthlyCostUsd", 40.0)
                    .append("costMonth", YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString()));

            QuotaCheckResult result = assertDoesNotThrow(() -> sut.tryAddCost(TENANT_ID, 5.0, 100.0));

            assertTrue(result.allowed());
            assertEquals(5.0, sut.getMonthlyCost(TENANT_ID), 1e-9, "last month's spend must not carry over");
            assertEquals(1, usageDocumentCount());
        }

        @Test
        @DisplayName("a legacy document missing the counter fields is repaired, not duplicated")
        void legacyDocumentWithoutWindowFields() {
            // Shape written by the pre-fix store when only the api-call path had run:
            // no dayStart, no conversationsToday.
            usage.insertOne(new Document("tenantId", TENANT_ID)
                    .append("apiCallsThisMinute", 4)
                    .append("minuteStart", 0L));

            QuotaCheckResult result = assertDoesNotThrow(() -> sut.tryIncrementConversations(TENANT_ID, 5));

            assertTrue(result.allowed());
            assertEquals(1, usageDocumentCount());
            assertEquals(1, sut.getUsage(TENANT_ID).conversationsToday());
        }
    }

    // ─── Cost budget ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cost budget")
    class CostBudget {

        @Test
        @DisplayName("denies at exactly the limit, matching checkCostBudget")
        void deniesAtExactlyTheLimit() {
            assertTrue(sut.tryAddCost(TENANT_ID, 60.0, 100.0).allowed());

            QuotaCheckResult atLimit = sut.tryAddCost(TENANT_ID, 40.0, 100.0);

            assertFalse(atLimit.allowed(), "$100 spent against a $100 budget is spent");
            assertNotNull(atLimit.reason());
        }

        @Test
        @DisplayName("a removed usage document is re-materialised on the next cost")
        void usageRemovedThenRecreated() {
            assertTrue(sut.tryAddCost(TENANT_ID, 1.0, 100.0).allowed());
            sut.resetUsage(TENANT_ID);

            QuotaCheckResult afterReset = assertDoesNotThrow(() -> sut.tryAddCost(TENANT_ID, 1.0, 100.0));

            assertTrue(afterReset.allowed());
            assertEquals(1.0, sut.getMonthlyCost(TENANT_ID), 1e-9);
            assertEquals(1, usageDocumentCount());
        }
    }
}
