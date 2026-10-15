/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The settle-time half of the tenant monthly cost budget: each step's priced
 * spend is charged once, whatever happens around it.
 */
class TurnCostMeterTest {

    private static final String TENANT = "default";
    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private TenantQuotaService quotaService;
    private SimpleMeterRegistry meterRegistry;
    private TurnCostMeter meter;

    @BeforeEach
    void setUp() {
        quotaService = mock(TenantQuotaService.class);
        when(quotaService.getDefaultTenantId()).thenReturn(TENANT);
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        meterRegistry = new SimpleMeterRegistry();
        meter = new TurnCostMeter(quotaService, meterRegistry);
    }

    @Test
    @DisplayName("a priced turn charges its audit:cost to the default tenant and marks the step")
    void pricedTurnIsCharged() {
        IConversationMemory memory = memoryWithCost(0.25);

        assertEquals(0.25, meter.meterSettledStep(memory), 1e-12);

        verify(quotaService).recordCost(TENANT, 0.25);
        assertEquals(0.25, (Double) memory.getCurrentStep().getData(MemoryKeys.AUDIT_METERED_COST).getResult(), 1e-12);
        assertEquals(1.0, meterRegistry.counter(TurnCostMeter.METRIC, "outcome", "recorded").count());
    }

    @Test
    @DisplayName("an unpriced turn writes nothing — not to the store, not to the step")
    void unpricedTurnMetersNothing() {
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");

        assertEquals(0.0, meter.meterSettledStep(memory));

        verify(quotaService, never()).recordCost(anyString(), anyDouble());
        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_METERED_COST));
    }

    @Test
    @DisplayName("settling the same step again charges only what was added since — and nothing when nothing was")
    void secondSettleChargesOnlyTheDelta() {
        IConversationMemory memory = memoryWithCost(0.30);
        meter.meterSettledStep(memory);

        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.50));
        meter.meterSettledStep(memory);
        meter.meterSettledStep(memory);

        verify(quotaService).recordCost(TENANT, 0.30);
        verify(quotaService).recordCost(eq(TENANT), doubleThat(d -> Math.abs(d - 0.20) < 1e-12));
        verify(quotaService, times(2)).recordCost(anyString(), anyDouble());
    }

    @Test
    @DisplayName("pause → persist → resume: the marker survives the store round trip, so the pre-pause cost is charged once")
    void pauseAndResumeChargeOnceInTotal() {
        IConversationMemory paused = memoryWithCost(0.30);
        meter.meterSettledStep(paused); // the pause settles the step part-way

        // Persisted and reloaded, as a resume does.
        IConversationMemory resumed = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(paused));
        // The resume keeps accumulating into the same step.
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.45));
        meter.meterSettledStep(resumed);

        verify(quotaService).recordCost(TENANT, 0.30);
        verify(quotaService).recordCost(eq(TENANT), doubleThat(d -> Math.abs(d - 0.15) < 1e-12));
        verify(quotaService, times(2)).recordCost(anyString(), anyDouble());
    }

    @Test
    @DisplayName("the marker is not read back as the step's cost (step lookups are prefix matches)")
    void markerDoesNotShadowTheCost() {
        IConversationMemory memory = memoryWithCost(0.30);
        meter.meterSettledStep(memory);

        var latest = memory.getCurrentStep().getLatestData(MemoryKeys.AUDIT_COST);
        assertEquals(MemoryKeys.AUDIT_COST, latest.getKey());
    }

    @Test
    @DisplayName("a quota store outage does not fail the turn and leaves the marker, so a later settle retries")
    void accountingUnavailableRetriesLater() {
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(ITenantQuotaStore.accountingUnavailable())
                .thenReturn(QuotaCheckResult.OK);
        IConversationMemory memory = memoryWithCost(0.30);

        assertDoesNotThrow(() -> meter.meterSettledStep(memory));
        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_METERED_COST));
        meter.meterSettledStep(memory);

        verify(quotaService, times(2)).recordCost(TENANT, 0.30);
        assertEquals(1.0, meterRegistry.counter(TurnCostMeter.METRIC, "outcome", "unavailable").count());
    }

    @Test
    @DisplayName("an exception from the quota service is swallowed — the spend already happened")
    void recordCostFailureDoesNotThrow() {
        when(quotaService.recordCost(anyString(), anyDouble())).thenThrow(new IllegalStateException("store exploded"));
        IConversationMemory memory = memoryWithCost(0.30);

        assertEquals(0.0, assertDoesNotThrow(() -> meter.meterSettledStep(memory)));
        assertEquals(1.0, meterRegistry.counter(TurnCostMeter.METRIC, "outcome", "error").count());
    }

    @Test
    @DisplayName("a turn that crosses the budget is still marked: it was charged, and must not be charged again")
    void overBudgetIsStillMarked() {
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.denied("over"));
        IConversationMemory memory = memoryWithCost(0.30);

        meter.meterSettledStep(memory);
        meter.meterSettledStep(memory);

        verify(quotaService, times(1)).recordCost(anyString(), anyDouble());
        assertEquals(1.0, meterRegistry.counter(TurnCostMeter.METRIC, "outcome", "over_budget").count());
    }

    @Test
    @DisplayName("end to end on the in-memory store: two priced turns exhaust the budget and the pre-call gate then refuses")
    void twoPricedTurnsExhaustTheBudget() {
        var store = new InMemoryTenantQuotaStore(TENANT, true, -1, -1, -1, 0.01);
        var service = new TenantQuotaService(store, new SimpleMeterRegistry(), TENANT);
        service.setQuota(new TenantQuota(TENANT, -1, -1, -1, 0.01, true));
        var realMeter = new TurnCostMeter(service, new SimpleMeterRegistry());

        assertTrue(service.checkCostBudget(TENANT).allowed());
        realMeter.meterSettledStep(memoryWithCost(0.006));
        assertTrue(service.checkCostBudget(TENANT).allowed(), "under the budget after the first turn");
        realMeter.meterSettledStep(memoryWithCost(0.006));

        assertFalse(service.checkCostBudget(TENANT).allowed(), "the second turn crossed it");
        assertEquals(0.012, store.getMonthlyCost(TENANT), 1e-12);
    }

    private static IConversationMemory memoryWithCost(double cost) {
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, cost));
        return memory;
    }
}
