/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.TenantCostHolds;
import ai.labs.eddi.engine.tenancy.ITenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The opt-in tenant cost reservation as the spend gate drives it: holds before
 * a model phase or priced tool, shrink when the task is done.
 */
@DisplayName("LlmSpendGate - tenant cost reservation")
class LlmSpendGateReservationTest {

    private TenantQuotaService tenantQuotaService;
    private LlmSpendGate gate;
    private IConversationMemory memory;

    @BeforeEach
    void setUp() {
        tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("default");
        when(tenantQuotaService.costReservationEnabled()).thenReturn(true);
        gate = new LlmSpendGate(tenantQuotaService, new SimpleMeterRegistry());
        memory = new ConversationMemory("0123456789abcdef01234567", "agent", 1, "user");
    }

    @Test
    @DisplayName("off: nothing is held and the tenant service is not asked")
    void offHoldsNothing() {
        when(tenantQuotaService.costReservationEnabled()).thenReturn(false);

        assertNull(gate.holdModelPhase(memory, 5.0));
        assertNull(gate.holdToolCall(memory, 5.0));

        verify(tenantQuotaService, never()).holdForCall(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble());
    }

    @Test
    @DisplayName("a zero amount (an unpriced model, a free tool) holds nothing")
    void zeroHoldsNothing() {
        assertNull(gate.holdModelPhase(memory, 0.0));
        assertNull(gate.holdToolCall(memory, 0.0));
        verify(tenantQuotaService, never()).holdForCall(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble());
    }

    @Test
    @DisplayName("a hold that fits lets the model run, and goes on the turn's holds")
    void fitsAndIsRecorded() {
        when(tenantQuotaService.holdForCall(eq("default"), same(memory.getTenantCostHolds()), eq(2.5))).thenReturn(QuotaCheckResult.OK);

        assertNull(gate.holdModelPhase(memory, 2.5));

        verify(tenantQuotaService).holdForCall("default", memory.getTenantCostHolds(), 2.5);
    }

    @Test
    @DisplayName("a hold the budget cannot take refuses the model call as tenant_budget")
    void refusedAsBudget() {
        when(tenantQuotaService.holdForCall(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble()))
                .thenReturn(QuotaCheckResult.denied("Monthly cost budget ($10.00) cannot take this call's reservation"));

        LlmSpendGate.Refusal refusal = gate.holdModelPhase(memory, 2.5);

        assertNotNull(refusal);
        assertEquals(LlmSpendGate.REASON_TENANT_BUDGET, refusal.reason());
    }

    @Test
    @DisplayName("an accounting outage refuses the call (fail closed) under its own reason")
    void refusedAsOutage() {
        when(tenantQuotaService.holdForCall(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble()))
                .thenReturn(ITenantQuotaStore.accountingUnavailable());

        assertEquals(LlmSpendGate.REASON_TENANT_ACCOUNTING_UNAVAILABLE, gate.holdToolCall(memory, 0.1).reason());
    }

    @Test
    @DisplayName("a reservation that throws is a refusal, not a failed turn")
    void thrownIsRefusal() {
        when(tenantQuotaService.holdForCall(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble()))
                .thenThrow(new IllegalStateException("boom"));

        assertEquals(LlmSpendGate.REASON_TENANT_CHECK_ERROR, gate.holdModelPhase(memory, 1.0).reason());
    }

    @Test
    @DisplayName("when a task is done the holds shrink to what the step has spent and not yet been charged for")
    void settleShrinksToUnmeteredCost() {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.9));
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_METERED_COST, 0.5));

        gate.settleHolds(memory);

        TenantCostHolds holds = memory.getTenantCostHolds();
        verify(tenantQuotaService).shrinkHolds(eq("default"), same(holds), eq(0.4));
    }

    @Test
    @DisplayName("settling does nothing when reservations are off")
    void settleOffIsNoop() {
        when(tenantQuotaService.costReservationEnabled()).thenReturn(false);

        gate.settleHolds(memory);

        verify(tenantQuotaService, never()).shrinkHolds(anyString(), org.mockito.ArgumentMatchers.any(), anyDouble());
    }
}
