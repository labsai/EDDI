/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MongoTenantQuotaStore}. Mocks the MongoDB chain:
 * MongoDatabase → MongoCollection → FindIterable/Document.
 */
@SuppressWarnings("unchecked")
class MongoTenantQuotaStoreTest {

    private static final String TENANT_ID = "tenant-xyz";

    private MongoDatabase database;
    private MongoCollection<Document> quotasCollection;
    private MongoCollection<Document> usageCollection;
    private FindIterable<Document> findIterable;

    private MongoTenantQuotaStore sut;

    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        quotasCollection = mock(MongoCollection.class);
        usageCollection = mock(MongoCollection.class);
        findIterable = mock(FindIterable.class);

        when(database.getCollection("tenant_quotas")).thenReturn(quotasCollection);
        when(database.getCollection("tenant_usage")).thenReturn(usageCollection);

        // Stub index creation (called in constructor)
        lenient().when(quotasCollection.createIndex(any(Document.class), any(IndexOptions.class))).thenReturn("tenantId_1");
        lenient().when(usageCollection.createIndex(any(Document.class), any(IndexOptions.class))).thenReturn("tenantId_1");

        sut = new MongoTenantQuotaStore(database);
    }

    // ─── getQuota ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getQuota")
    class GetQuota {

        @Test
        @DisplayName("should return TenantQuota when document exists")
        void found() {
            Document doc = new Document()
                    .append("tenantId", TENANT_ID)
                    .append("maxConversationsPerDay", 100)
                    .append("maxAgentsPerTenant", 5)
                    .append("maxApiCallsPerMinute", 60)
                    .append("maxMonthlyCostUsd", 500.0)
                    .append("enabled", true);

            when(quotasCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            TenantQuota quota = sut.getQuota(TENANT_ID);

            assertNotNull(quota);
            assertEquals(TENANT_ID, quota.tenantId());
            assertEquals(100, quota.maxConversationsPerDay());
            assertEquals(5, quota.maxAgentsPerTenant());
            assertEquals(60, quota.maxApiCallsPerMinute());
            assertEquals(500.0, quota.maxMonthlyCostUsd());
            assertTrue(quota.enabled());
        }

        @Test
        @DisplayName("should return null when document not found")
        void notFound() {
            when(quotasCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(null);

            TenantQuota quota = sut.getQuota(TENANT_ID);

            assertNull(quota);
        }

        @Test
        @DisplayName("should use defaults when fields missing in document")
        void missingFields() {
            Document doc = new Document()
                    .append("tenantId", TENANT_ID);
            // No optional fields — defaults should be used

            when(quotasCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            TenantQuota quota = sut.getQuota(TENANT_ID);

            assertNotNull(quota);
            assertEquals(-1, quota.maxConversationsPerDay());
            assertEquals(-1, quota.maxAgentsPerTenant());
            assertEquals(-1, quota.maxApiCallsPerMinute());
            assertEquals(-1.0, quota.maxMonthlyCostUsd());
            assertFalse(quota.enabled());
        }
    }

    // ─── setQuota ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setQuota")
    class SetQuota {

        @Test
        @DisplayName("should upsert quota document")
        void setQuota_success() {
            TenantQuota quota = new TenantQuota(TENANT_ID, 100, 5, 60, 500.0, true);

            sut.setQuota(quota);

            verify(quotasCollection).findOneAndUpdate(any(Bson.class), any(Bson.class), any());
        }
    }

    // ─── listQuotas ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listQuotas")
    class ListQuotas {

        @Test
        @DisplayName("should return empty list when no quotas")
        void empty() {
            FindIterable<Document> emptyIterable = mock(FindIterable.class);
            when(quotasCollection.find()).thenReturn(emptyIterable);
            com.mongodb.client.MongoCursor<Document> emptyCursor = mock(com.mongodb.client.MongoCursor.class);
            when(emptyCursor.hasNext()).thenReturn(false);
            doReturn(emptyCursor).when(emptyIterable).iterator();

            List<TenantQuota> result = sut.listQuotas();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all quotas")
        void withDocs() {
            Document doc1 = new Document()
                    .append("tenantId", "t1").append("maxConversationsPerDay", 10)
                    .append("maxAgentsPerTenant", -1).append("maxApiCallsPerMinute", -1)
                    .append("maxMonthlyCostUsd", -1.0).append("enabled", true);
            Document doc2 = new Document()
                    .append("tenantId", "t2").append("maxConversationsPerDay", 20)
                    .append("maxAgentsPerTenant", -1).append("maxApiCallsPerMinute", -1)
                    .append("maxMonthlyCostUsd", -1.0).append("enabled", false);

            FindIterable<Document> iter = mock(FindIterable.class);
            when(quotasCollection.find()).thenReturn(iter);
            com.mongodb.client.MongoCursor<Document> cursor = mock(com.mongodb.client.MongoCursor.class);
            Iterator<Document> docIter = List.of(doc1, doc2).iterator();
            when(cursor.hasNext()).thenAnswer(inv -> docIter.hasNext());
            when(cursor.next()).thenAnswer(inv -> docIter.next());
            doReturn(cursor).when(iter).iterator();

            List<TenantQuota> result = sut.listQuotas();

            assertEquals(2, result.size());
            assertEquals("t1", result.get(0).tenantId());
            assertEquals("t2", result.get(1).tenantId());
        }
    }

    // ─── deleteQuota ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteQuota")
    class DeleteQuota {

        @Test
        @DisplayName("should delete from both collections")
        void success() {
            sut.deleteQuota(TENANT_ID);

            verify(quotasCollection).deleteOne(any(Bson.class));
            verify(usageCollection).deleteOne(any(Bson.class));
        }
    }

    // ─── tryIncrementConversations ─────────────────────────────────────────────

    @Nested
    @DisplayName("tryIncrementConversations")
    class TryIncrementConversations {

        @Test
        @DisplayName("should return OK when limit < 0 (unlimited)")
        void unlimited() {
            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, -1);

            assertEquals(QuotaCheckResult.OK, result);
        }

        @Test
        @DisplayName("should return OK when findOneAndUpdate returns document (within limit)")
        void withinLimit() {
            Document updated = new Document("conversationsToday", 5);
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any())).thenReturn(updated);

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should try stale window reset when first update returns null")
        void staleWindowReset() {
            // First findOneAndUpdate: null (not in window or at limit)
            // Second findOneAndUpdate: returns document (stale window reset succeeded)
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any()))
                    .thenReturn(null)
                    .thenReturn(new Document("conversationsToday", 1));

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return denied when both attempts return null")
        void limitReached() {
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any()))
                    .thenReturn(null)
                    .thenReturn(null);

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("Daily conversation limit"));
            assertTrue(result.reason().contains("10"));
        }
    }

    // ─── tryIncrementApiCalls ──────────────────────────────────────────────────

    @Nested
    @DisplayName("tryIncrementApiCalls")
    class TryIncrementApiCalls {

        @Test
        @DisplayName("should return OK when limit < 0 (unlimited)")
        void unlimited() {
            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, -1);

            assertEquals(QuotaCheckResult.OK, result);
        }

        @Test
        @DisplayName("should return OK when within rate limit")
        void withinLimit() {
            Document updated = new Document("apiCallsThisMinute", 3);
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any())).thenReturn(updated);

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 60);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should try stale window reset and succeed")
        void staleWindowReset() {
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any()))
                    .thenReturn(null)
                    .thenReturn(new Document("apiCallsThisMinute", 1));

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 60);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return denied when rate limit reached")
        void limitReached() {
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any()))
                    .thenReturn(null)
                    .thenReturn(null);

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 60);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("API rate limit"));
            assertTrue(result.reason().contains("60/min"));
        }
    }

    // ─── tryAddCost ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tryAddCost")
    class TryAddCost {

        @Test
        @DisplayName("should return OK when within budget")
        void withinBudget() {
            Document result = new Document("monthlyCostUsd", 50.0);
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any())).thenReturn(result);

            QuotaCheckResult qResult = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertTrue(qResult.allowed());
        }

        @Test
        @DisplayName("should return denied when budget exceeded")
        void overBudget() {
            Document result = new Document("monthlyCostUsd", 150.0);
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any())).thenReturn(result);

            QuotaCheckResult qResult = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertFalse(qResult.allowed());
            assertTrue(qResult.reason().contains("Monthly cost budget exceeded"));
        }

        @Test
        @DisplayName("should return OK when limit is negative (unlimited)")
        void unlimitedBudget() {
            Document result = new Document("monthlyCostUsd", 9999.0);
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any())).thenReturn(result);

            QuotaCheckResult qResult = sut.tryAddCost(TENANT_ID, 10.0, -1.0);

            assertTrue(qResult.allowed());
        }

        @Test
        @DisplayName("should handle stale month (null result) by resetting")
        void staleMonth() {
            when(usageCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any()))
                    .thenReturn(null);

            QuotaCheckResult qResult = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertTrue(qResult.allowed());
            // Two findOneAndUpdate calls: first returns null, second resets
            verify(usageCollection, times(2)).findOneAndUpdate(any(Bson.class), any(Bson.class), any());
        }
    }

    // ─── getUsage ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getUsage")
    class GetUsage {

        @Test
        @DisplayName("should return snapshot when usage document exists")
        void found() {
            long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
            long dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();
            String costMonth = YearMonth.now(ZoneOffset.UTC).toString();

            Document doc = new Document()
                    .append("tenantId", TENANT_ID)
                    .append("conversationsToday", 5)
                    .append("apiCallsThisMinute", 3)
                    .append("monthlyCostUsd", 42.0)
                    .append("minuteStart", minuteStart)
                    .append("dayStart", dayStart)
                    .append("costMonth", costMonth);

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(TENANT_ID, snapshot.tenantId());
            assertEquals(5, snapshot.conversationsToday());
            assertEquals(3, snapshot.apiCallsThisMinute());
            assertEquals(42.0, snapshot.monthlyCostUsd());
        }

        @Test
        @DisplayName("should return empty snapshot when no usage document")
        void notFound() {
            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(null);

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(TENANT_ID, snapshot.tenantId());
            assertEquals(0, snapshot.conversationsToday());
        }

        @Test
        @DisplayName("should handle missing optional fields with defaults")
        void missingFields() {
            Document doc = new Document()
                    .append("tenantId", TENANT_ID);
            // No minuteStart, dayStart, costMonth, monthlyCostUsd

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(0, snapshot.conversationsToday());
            assertEquals(0, snapshot.apiCallsThisMinute());
            assertEquals(0.0, snapshot.monthlyCostUsd());
            assertNotNull(snapshot.minuteWindowStart());
            assertNotNull(snapshot.dayStart());
            assertNotNull(snapshot.costMonth());
        }
    }

    // ─── getMonthlyCost ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMonthlyCost")
    class GetMonthlyCost {

        @Test
        @DisplayName("should return cost when current month matches")
        void currentMonth() {
            Document doc = new Document()
                    .append("costMonth", YearMonth.now(ZoneOffset.UTC).toString())
                    .append("monthlyCostUsd", 123.45);

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(123.45, cost);
        }

        @Test
        @DisplayName("should return 0.0 when month is stale")
        void staleMonth() {
            Document doc = new Document()
                    .append("costMonth", "2020-01")
                    .append("monthlyCostUsd", 99.0);

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 when costMonth is null")
        void nullCostMonth() {
            Document doc = new Document().append("costMonth", null);

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 when no document found")
        void noDoc() {
            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(null);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 when monthlyCostUsd is null in document")
        void nullCost() {
            Document doc = new Document()
                    .append("costMonth", YearMonth.now(ZoneOffset.UTC).toString())
                    .append("monthlyCostUsd", null);

            when(usageCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(doc);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }
    }

    // ─── resetUsage ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resetUsage")
    class ResetUsage {

        @Test
        @DisplayName("should delete usage document")
        void success() {
            sut.resetUsage(TENANT_ID);

            verify(usageCollection).deleteOne(any(Bson.class));
        }
    }

    // ─── Bootstrap ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bootstrap (CDI constructor)")
    class Bootstrap {

        @Test
        @DisplayName("should create default quota when none exists")
        void bootstrapsWhenEmpty() {
            // getQuota returns null → no existing quota
            when(quotasCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(null);

            // setQuota uses findOneAndUpdate
            lenient().when(quotasCollection.findOneAndUpdate(
                    any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(null);

            var bootstrapStore = new MongoTenantQuotaStore(
                    database, "default", false, -1, -1, -1, -1.0);

            // Verify setQuota was called (findOneAndUpdate for upsert)
            verify(quotasCollection, atLeastOnce()).findOneAndUpdate(
                    any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
        }

        @Test
        @DisplayName("should not overwrite existing quota")
        void doesNotOverwriteExisting() {
            // getQuota returns an existing doc
            Document existingDoc = new Document()
                    .append("tenantId", "default")
                    .append("maxConversationsPerDay", 5000)
                    .append("maxAgentsPerTenant", 100)
                    .append("maxApiCallsPerMinute", 500)
                    .append("maxMonthlyCostUsd", 2500.0)
                    .append("enabled", true);
            when(quotasCollection.find(any(Bson.class))).thenReturn(findIterable);
            when(findIterable.first()).thenReturn(existingDoc);

            var bootstrapStore = new MongoTenantQuotaStore(
                    database, "default", false, -1, -1, -1, -1.0);

            // setQuota (findOneAndUpdate with upsert) should NOT have been called
            // beyond the index creation calls
            verify(quotasCollection, never()).findOneAndUpdate(
                    any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
        }
    }
}
