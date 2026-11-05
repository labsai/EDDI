/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.TenantCostHolds;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The opt-in hard tenant ceiling: a call's maximum cost is held against the
 * monthly budget before the call, so the call that would cross the budget is
 * refused instead of finishing and overshooting.
 */
@DisplayName("Tenant cost reservation (hard ceiling)")
class TenantCostReservationTest {

    private static final String TENANT = "default";
    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private SimpleMeterRegistry meters;
    private InMemoryTenantQuotaStore store;
    private TenantQuotaService service;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        store = new InMemoryTenantQuotaStore(TENANT, true, -1, -1, -1, 10.0);
        service = new TenantQuotaService(store, meters, TENANT);
        service.configureCostReservation(true, Duration.ofMinutes(10), 4096);
    }

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("off by default: nothing is reserved and the store is never asked")
    void offByDefault() {
        ITenantQuotaStore mocked = mock(ITenantQuotaStore.class);
        TenantQuotaService off = new TenantQuotaService(mocked, meters, TENANT);

        CostReservationResult result = off.reserveCost(TENANT, 5.0);

        assertTrue(result.check().allowed());
        assertNull(result.reservation());
        verify(mocked, never()).tryReserveCost(anyString(), anyDouble(), anyDouble(), anyLong());
        verify(mocked, never()).getQuota(anyString());
    }

    @Test
    @DisplayName("no enabled budget (unlimited, or quota disabled): nothing to hold against")
    void noBudgetNoHold() {
        store.setQuota(new TenantQuota(TENANT, -1, -1, -1, -1.0, true));
        assertNull(service.reserveCost(TENANT, 5.0).reservation(), "-1 is unlimited");
        store.setQuota(new TenantQuota(TENANT, -1, -1, -1, 10.0, false));
        service.invalidateQuotaCache(TENANT);
        assertNull(service.reserveCost(TENANT, 5.0).reservation(), "a disabled quota enforces nothing");
    }

    @Test
    @DisplayName("the call that would cross the budget is refused, not run and charged afterwards")
    void crossingCallIsRefused() {
        store.tryAddCost(TENANT, 9.0, 10.0);

        CostReservationResult crossing = service.reserveCost(TENANT, 2.0);

        assertFalse(crossing.check().allowed(), "9 spent + 2 held passes 10");
        assertFalse(crossing.check().accountingUnavailable(), "a budget refusal is not an outage");
        assertEquals(1.0, counter("eddi.tenant.cost.reservation", "outcome", "refused"));
        assertTrue(service.reserveCost(TENANT, 1.0).granted(), "what fits is still granted");
    }

    @Test
    @DisplayName("concurrent callers share the budget: holds of other turns count against a new call")
    void holdsOfOtherTurnsCount() {
        assertTrue(service.reserveCost(TENANT, 6.0).granted());
        assertFalse(service.reserveCost(TENANT, 5.0).granted(), "6 held + 5 passes 10 even though nothing is spent yet");
        assertEquals(0.0, store.getMonthlyCost(TENANT), 1e-12, "a hold is not spend");
    }

    @Test
    @DisplayName("fails closed: a store that cannot answer refuses, flagged as an outage")
    void failsClosedWhenQuotaUnreadable() {
        ITenantQuotaStore broken = mock(ITenantQuotaStore.class);
        when(broken.getQuota(anyString())).thenThrow(new QuotaAccountingUnavailableException(ITenantQuotaStore.ACCOUNTING_UNAVAILABLE));
        TenantQuotaService svc = new TenantQuotaService(broken, meters, TENANT);
        svc.configureCostReservation(true, Duration.ofMinutes(10), 4096);

        CostReservationResult result = svc.reserveCost(TENANT, 1.0);

        assertFalse(result.check().allowed());
        assertTrue(result.check().accountingUnavailable());
        assertEquals(1.0, counter("eddi.tenant.cost.reservation", "outcome", "unavailable"));
    }

    @Test
    @DisplayName("fails closed: an unavailable reserve is a refusal, not a free pass")
    void failsClosedWhenReserveUnavailable() {
        ITenantQuotaStore broken = mock(ITenantQuotaStore.class);
        when(broken.getQuota(anyString())).thenReturn(new TenantQuota(TENANT, -1, -1, -1, 10.0, true));
        when(broken.tryReserveCost(anyString(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(CostReservationResult.refused(ITenantQuotaStore.accountingUnavailable()));
        TenantQuotaService svc = new TenantQuotaService(broken, meters, TENANT);
        svc.configureCostReservation(true, Duration.ofMinutes(10), 4096);

        assertTrue(svc.reserveCost(TENANT, 1.0).check().accountingUnavailable());

        when(broken.tryReserveCost(anyString(), anyDouble(), anyDouble(), anyLong())).thenThrow(new IllegalStateException("boom"));
        CostReservationResult thrown = svc.reserveCost(TENANT, 1.0);
        assertFalse(thrown.check().allowed(), "a throwing store refuses too");
        assertTrue(thrown.check().accountingUnavailable());
    }

    @Test
    @DisplayName("a hold is recorded on the turn, shrunk to the actual cost, and the difference is given back")
    void shrinkGivesTheDifferenceBack() {
        TenantCostHolds holds = new TenantCostHolds();
        assertTrue(service.holdForCall(TENANT, holds, 8.0).allowed());
        assertEquals(8.0, holds.heldUsd(), 1e-12);
        assertFalse(service.reserveCost(TENANT, 3.0).granted(), "8 held leaves 2");

        service.shrinkHolds(TENANT, holds, 1.0);

        assertEquals(1.0, holds.heldUsd(), 1e-12, "the turn keeps only what it spent");
        assertTrue(service.reserveCost(TENANT, 9.0).granted(), "1 kept + 9 fits exactly");
        assertFalse(service.reserveCost(TENANT, 0.5).granted());
    }

    @Test
    @DisplayName("shrinking never raises a hold")
    void shrinkNeverRaises() {
        TenantCostHolds holds = new TenantCostHolds();
        service.holdForCall(TENANT, holds, 2.0);
        service.shrinkHolds(TENANT, holds, 50.0);
        assertEquals(2.0, holds.heldUsd(), 1e-12);
    }

    @Test
    @DisplayName("a hold with nowhere to be recorded is given straight back, not leaked")
    void unrecordableHoldIsReleased() {
        assertTrue(service.holdForCall(TENANT, null, 7.0).allowed());
        assertTrue(service.reserveCost(TENANT, 10.0).granted(), "nothing stays held");
    }

    @Test
    @DisplayName("a failing release never throws and is counted; the hold falls away at its TTL")
    void failingReleaseIsContained() {
        ITenantQuotaStore flaky = mock(ITenantQuotaStore.class);
        doThrow(new QuotaAccountingUnavailableException("down")).when(flaky).releaseCost(anyString(), anyLong(), anyDouble());
        TenantQuotaService svc = new TenantQuotaService(flaky, meters, TENANT);
        svc.configureCostReservation(true, Duration.ofMinutes(10), 4096);

        assertDoesNotThrow(() -> svc.releaseReservation(TENANT, 1L, 1.0));

        assertEquals(1.0, counter("eddi.tenant.cost.reservation", "outcome", "release_failed"));
    }

    @Test
    @DisplayName("settling a turn charges the actual cost first and then gives every hold back")
    void turnSettleChargesThenReleases() {
        TurnCostMeter meter = new TurnCostMeter(service, meters);
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        assertTrue(service.holdForCall(TENANT, memory.getTenantCostHolds(), 6.0).allowed());
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.5));

        assertEquals(0.5, meter.meterSettledStep(memory), 1e-12);

        assertEquals(0.5, store.getMonthlyCost(TENANT), 1e-12, "the actual cost is charged");
        assertEquals(0.0, memory.getTenantCostHolds().heldUsd(), 1e-12, "the hold is gone from the turn");
        assertTrue(service.reserveCost(TENANT, 9.5).granted(), "and from the tenant counter: 0.5 spent + 9.5 fits");
        assertFalse(service.reserveCost(TENANT, 0.01).granted());
    }

    @Test
    @DisplayName("a turn that spent nothing still gives its holds back")
    void emptyTurnStillReleases() {
        TurnCostMeter meter = new TurnCostMeter(service, meters);
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        service.holdForCall(TENANT, memory.getTenantCostHolds(), 9.0);

        assertEquals(0.0, meter.meterSettledStep(memory), 1e-12);

        assertTrue(service.reserveCost(TENANT, 10.0).granted());
    }

    @Test
    @DisplayName("holds count as TenantCostHolds: add, shrink newest first, drain")
    void holdsBookkeeping() {
        TenantCostHolds holds = new TenantCostHolds();
        holds.add(1L, 3.0);
        holds.add(2L, 4.0);
        holds.add(3L, 0.0);
        holds.add(4L, Double.NaN);
        assertEquals(7.0, holds.heldUsd(), 1e-12, "zero and non-finite holds are ignored");

        var given = holds.shrinkTo(5.0);

        assertEquals(1, given.size());
        assertEquals(2L, given.get(0).generation(), "newest first");
        assertEquals(2.0, given.get(0).amountUsd(), 1e-12);
        assertEquals(5.0, holds.heldUsd(), 1e-12);
        assertEquals(2, holds.drain().size());
        assertEquals(0.0, holds.heldUsd(), 1e-12);
    }
}
