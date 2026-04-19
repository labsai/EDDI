package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTenantQuotaStoreTest {

    private InMemoryTenantQuotaStore store;

    @BeforeEach
    void setUp() {
        var quota = new TenantQuota("test-tenant", 10, 5, 100, 50.0, true);
        store = new InMemoryTenantQuotaStore(quota);
    }

    // --- Quota CRUD ---

    @Test
    void getQuota_existingTenant_returnsQuota() {
        TenantQuota q = store.getQuota("test-tenant");
        assertNotNull(q);
        assertEquals("test-tenant", q.tenantId());
        assertEquals(10, q.maxConversationsPerDay());
    }

    @Test
    void getQuota_unknownTenant_returnsNull() {
        assertNull(store.getQuota("unknown"));
    }

    @Test
    void setQuota_addsNewTenant() {
        store.setQuota(new TenantQuota("new-tenant", 20, 10, 200, 100.0, true));
        assertNotNull(store.getQuota("new-tenant"));
    }

    @Test
    void listQuotas_returnsCopy() {
        var list = store.listQuotas();
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());
    }

    @Test
    void deleteQuota_removesQuota() {
        store.deleteQuota("test-tenant");
        assertNull(store.getQuota("test-tenant"));
    }

    // --- Conversation Limits ---

    @Test
    void tryIncrementConversations_underLimit_allowed() {
        var result = store.tryIncrementConversations("test-tenant", 10);
        assertTrue(result.allowed());
    }

    @Test
    void tryIncrementConversations_atLimit_denied() {
        for (int i = 0; i < 10; i++) {
            store.tryIncrementConversations("test-tenant", 10);
        }
        var result = store.tryIncrementConversations("test-tenant", 10);
        assertFalse(result.allowed());
        assertNotNull(result.reason());
    }

    @Test
    void tryIncrementConversations_negativeLimit_unlimitedAlways() {
        for (int i = 0; i < 1000; i++) {
            assertTrue(store.tryIncrementConversations("test-tenant", -1).allowed());
        }
    }

    // --- API Call Rate Limiting ---

    @Test
    void tryIncrementApiCalls_underLimit_allowed() {
        var result = store.tryIncrementApiCalls("test-tenant", 100);
        assertTrue(result.allowed());
    }

    @Test
    void tryIncrementApiCalls_atLimit_denied() {
        for (int i = 0; i < 100; i++) {
            store.tryIncrementApiCalls("test-tenant", 100);
        }
        var result = store.tryIncrementApiCalls("test-tenant", 100);
        assertFalse(result.allowed());
    }

    // --- Cost Budget ---

    @Test
    void tryAddCost_underBudget_allowed() {
        var result = store.tryAddCost("test-tenant", 10.0, 50.0);
        assertTrue(result.allowed());
    }

    @Test
    void tryAddCost_exceedsBudget_denied() {
        store.tryAddCost("test-tenant", 49.0, 50.0);
        var result = store.tryAddCost("test-tenant", 2.0, 50.0);
        assertFalse(result.allowed());
    }

    @Test
    void tryAddCost_negativeLimit_unlimitedAlways() {
        assertTrue(store.tryAddCost("test-tenant", 999999.0, -1.0).allowed());
    }

    // --- Usage Reporting ---

    @Test
    void getUsage_unknownTenant_returnsEmpty() {
        UsageSnapshot snap = store.getUsage("nonexistent");
        assertNotNull(snap);
        assertEquals("nonexistent", snap.tenantId());
        assertEquals(0, snap.conversationsToday());
    }

    @Test
    void getUsage_afterIncrements_reflectsUsage() {
        store.tryIncrementConversations("test-tenant", 100);
        store.tryIncrementConversations("test-tenant", 100);
        store.tryIncrementApiCalls("test-tenant", 100);

        UsageSnapshot snap = store.getUsage("test-tenant");
        assertEquals(2, snap.conversationsToday());
        assertEquals(1, snap.apiCallsThisMinute());
    }

    @Test
    void getMonthlyCost_noCost_returns0() {
        assertEquals(0.0, store.getMonthlyCost("test-tenant"));
    }

    @Test
    void getMonthlyCost_afterAddCost_reflectsTotal() {
        store.tryAddCost("test-tenant", 5.0, -1);
        store.tryAddCost("test-tenant", 3.0, -1);
        assertEquals(8.0, store.getMonthlyCost("test-tenant"), 0.01);
    }

    @Test
    void getMonthlyCost_unknownTenant_returns0() {
        assertEquals(0.0, store.getMonthlyCost("unknown"));
    }

    // --- Reset ---

    @Test
    void resetUsage_clearsCounters() {
        store.tryIncrementConversations("test-tenant", 100);
        store.tryIncrementApiCalls("test-tenant", 100);
        store.tryAddCost("test-tenant", 10.0, -1);

        store.resetUsage("test-tenant");

        UsageSnapshot snap = store.getUsage("test-tenant");
        assertEquals(0, snap.conversationsToday());
        assertEquals(0, snap.apiCallsThisMinute());
    }

    @Test
    void resetUsage_unknownTenant_noOp() {
        assertDoesNotThrow(() -> store.resetUsage("nonexistent"));
    }

    // --- New tenant counters auto-created ---

    @Test
    void tryIncrementConversations_newTenant_autoCreatesCounters() {
        var result = store.tryIncrementConversations("brand-new", 5);
        assertTrue(result.allowed());
        assertEquals(1, store.getUsage("brand-new").conversationsToday());
    }
}
