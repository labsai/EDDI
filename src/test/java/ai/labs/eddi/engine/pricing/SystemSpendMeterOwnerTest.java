/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.documents.spend.DocumentSpendGate;
import ai.labs.eddi.engine.documents.spend.DocumentSpendLimits;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.documents.spend.InMemoryDocumentSpendStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner-keyed variant of {@link SystemSpendMeter#charge}: one call prices,
 * adds to the daily user and team buckets, charges the owner's tenant and
 * reports a {@code DOCUMENT} event. The group variant is covered, unchanged, by
 * {@code SystemSpendMeterTest}.
 */
@DisplayName("SystemSpendMeter — owner-keyed document spend")
class SystemSpendMeterOwnerTest {

    private static final String USER = "5f9a1c2b3d4e5f6a7b8c9d0e";
    private static final String TEAM = "team:/acme/research";
    private static final long TODAY = LocalDate.of(2026, 11, 22).toEpochDay();
    private static final SpendOwner OWNER = new SpendOwner("space-tenant-1", USER, TEAM, null);

    private final List<SpendEvent> events = new ArrayList<>();
    private TenantQuotaService quota;
    private InMemoryDocumentSpendStore store;
    private DocumentSpendGate gate;

    @BeforeEach
    void setUp() {
        quota = mock(TenantQuotaService.class);
        when(quota.getDefaultTenantId()).thenReturn("default");
        when(quota.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(quota.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        store = new InMemoryDocumentSpendStore();
        gate = spy(new DocumentSpendGate(quota, store, DocumentSpendLimits::defaults, Clock.fixed(Instant.parse("2026-11-22T08:00:00Z"),
                ZoneOffset.UTC)));
        SystemSpendMeter.install(new SystemSpendMeter(quota, new SpendSinks(List.of(events::add)), gate));
    }

    @AfterEach
    void tearDown() {
        SystemSpendMeter.install(null);
    }

    private static Map<String, Object> usage(int in, int out) {
        return Map.of("inputTokens", in, "outputTokens", out);
    }

    @Test
    @DisplayName("one charge reaches the buckets, the tenant and the sinks")
    void oneChargeDoesEverything() {
        double cost = SystemSpendMeter.charge(OWNER, "sum:leaf:u3", PriceOverride.of(1.0, 2.0), usage(1_000_000, 500_000), "summary:leaf");

        assertEquals(2.0, cost, 1e-12);
        assertEquals(2.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12);
        assertEquals(2.0, store.spent(SubjectKind.TEAM, TEAM, TODAY), 1e-12);
        verify(quota).recordCost("default", 2.0);
        assertEquals(1, events.size());
        SpendEvent event = events.getFirst();
        assertEquals(SpendEvent.Kind.DOCUMENT, event.kind());
        assertEquals(USER, event.userId());
        assertEquals("default", event.tenantId());
        assertEquals("summary:leaf", event.detail());
        assertNull(event.groupId());
        assertNull(event.conversationId());
        assertEquals(1_000_000, event.inputTokens());
    }

    @Test
    @DisplayName("every call adds its full cost: there is no ledger to charge by replacement")
    void repeatedChargesAdd() {
        SystemSpendMeter.charge(OWNER, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), null);
        SystemSpendMeter.charge(OWNER, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), null);
        assertEquals(2.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12);
        assertEquals(2, events.size());
        assertEquals("k", events.getFirst().detail(), "the key labels the event when no detail is given");
    }

    @Test
    @DisplayName("the owner's tenant is charged, not the default")
    void ownersTenant() {
        SystemSpendMeter.charge(new SpendOwner("k", USER, null, "tenant-b"), "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "d");
        verify(quota).recordCost("tenant-b", 1.0);
        verify(quota, never()).recordCost("default", 1.0);
        assertEquals("tenant-b", events.getFirst().tenantId());
    }

    @Test
    @DisplayName("an unpriced call charges no tenant and invents no price, but counts the call in the buckets")
    void unpriced() {
        assertEquals(0.0, SystemSpendMeter.charge(OWNER, "k", PriceOverride.NONE, usage(10, 10), "d"));
        verify(quota, never()).recordCost(anyString(), anyDouble());
        assertEquals(0, events.size());
        assertEquals(0.0, store.spent(SubjectKind.USER, USER, TODAY));
        assertEquals(1L, store.findBySubject(SubjectKind.USER, USER, 1).getFirst().calls());
    }

    @Test
    @DisplayName("an unavailable tenant counter carries the charge; the buckets get it exactly once")
    void carriedChargeIsNotAddedToTheBucketsTwice() {
        when(quota.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.unavailable("down"), QuotaCheckResult.OK);

        SystemSpendMeter.charge(OWNER, "a", PriceOverride.of(1.0, null), usage(2_000_000, 0), "d");
        assertEquals(0, events.size(), "the tenant counter did not take it, so no sink sees it");
        assertEquals(2.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12, "the daily ceiling sees it anyway");

        SystemSpendMeter.charge(OWNER, "b", PriceOverride.of(1.0, null), usage(1_000_000, 0), "d");

        verify(quota).recordCost("default", 3.0);
        assertEquals(2, events.size(), "the carried charge and the new one");
        assertEquals(3.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12, "not 5: the replay is not added again");
    }

    @Test
    @DisplayName("carried charges of different tenants are charged to their own tenants, never summed into one")
    void carriedChargesStayWithTheirTenant() {
        when(quota.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.unavailable("down"), QuotaCheckResult.OK);
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("team-a");

        SystemSpendMeter.charge(new SpendOwner("k", USER, null, "tenant-b"), "a", PriceOverride.of(1.0, null), usage(2_000_000, 0), "d");
        SystemSpendMeter.charge(gc, "system:summarizer:full:1", PriceOverride.of(1.0, null), usage(1_000_000, 0), "w");

        verify(quota, times(2)).recordCost("tenant-b", 2.0); // refused once, then retried alone
        verify(quota, never()).recordCost("default", 3.0);
        verify(quota).recordCost("default", 1.0);
        assertEquals(2, events.size());
        assertEquals(SpendEvent.Kind.DOCUMENT, events.get(0).kind());
        assertEquals("tenant-b", events.get(0).tenantId());
        assertEquals(SpendEvent.Kind.GROUP_SYSTEM, events.get(1).kind());
        assertEquals("default", events.get(1).tenantId());
    }

    @Test
    @DisplayName("never throws, even when the daily counters fail")
    void neverThrows() {
        doThrow(new IllegalStateException("boom")).when(gate).record(OWNER, 1.0);
        assertEquals(1.0, assertDoesNotThrow(() -> SystemSpendMeter.charge(OWNER, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "d")),
                1e-12);
    }

    @Test
    @DisplayName("without a meter or an owner the call is only priced")
    void pricesOnly() {
        assertEquals(1.0, SystemSpendMeter.charge((SpendOwner) null, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "d"), 1e-12);
        SystemSpendMeter.install(null);
        assertEquals(1.0, SystemSpendMeter.charge(OWNER, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "d"), 1e-12);
        verify(quota, never()).recordCost(anyString(), anyDouble());
        assertEquals(0.0, store.spent(SubjectKind.USER, USER, TODAY));
    }
}
