/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.pricing.PriceOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doubleThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Group machinery spend: ledger, tenant charge and sinks, without double
 * counting.
 */
class SystemSpendMeterTest {

    private final List<SpendEvent> events = new ArrayList<>();
    private TenantQuotaService quota;
    private GroupConversation gc;

    @BeforeEach
    void setUp() {
        quota = mock(TenantQuotaService.class);
        when(quota.getDefaultTenantId()).thenReturn("default");
        when(quota.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(quota.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        SystemSpendMeter.install(new SystemSpendMeter(quota, new SpendSinks(List.of(events::add))));
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("team-a");
    }

    @AfterEach
    void tearDown() {
        SystemSpendMeter.install(null);
    }

    private static Map<String, Object> usage(int in, int out) {
        return Map.of("inputTokens", in, "outputTokens", out);
    }

    @Test
    void aWindowSummaryIsChargedToTheTenantAndTheLedger() {
        double cost = SystemSpendMeter.charge(gc, "system:summarizer:full:10", PriceOverride.of(1.0, 2.0), usage(1_000_000, 500_000), "w");
        assertEquals(2.0, cost, 1e-12);
        assertEquals(2.0, gc.getMemberCosts().get("system:summarizer:full:10"), 1e-12);
        verify(quota).recordCost("default", 2.0);
        assertEquals(1, events.size());
        assertEquals("team-a", events.getFirst().groupId());
        assertEquals(SpendEvent.Kind.GROUP_SYSTEM, events.getFirst().kind());
    }

    @Test
    void aReRunOfTheSameOperationChargesOnlyTheIncrease() {
        SystemSpendMeter.charge(gc, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "w");
        SystemSpendMeter.charge(gc, "k", PriceOverride.of(1.0, null), usage(1_000_000, 0), "w");
        SystemSpendMeter.charge(gc, "k", PriceOverride.of(1.0, null), usage(1_500_000, 0), "w");
        verify(quota).recordCost("default", 1.0);
        verify(quota).recordCost(eq("default"), doubleThat(d -> Math.abs(d - 0.5) < 1e-12));
        verify(quota, times(2)).recordCost(anyString(), anyDouble());
        assertEquals(2, events.size());
    }

    @Test
    void anUnpricedCallChargesNothing() {
        assertEquals(0.0, SystemSpendMeter.charge(gc, "k", PriceOverride.NONE, usage(10, 10), "w"));
        verify(quota, never()).recordCost(anyString(), anyDouble());
    }

    @Test
    void admissionFollowsTheTenantBudgetAndFailsClosed() {
        assertTrue(SystemSpendMeter.admit());
        when(quota.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.denied("over"));
        assertFalse(SystemSpendMeter.admit());
        when(quota.checkCostBudget(anyString(), anyDouble())).thenThrow(new IllegalStateException("down"));
        assertFalse(SystemSpendMeter.admit());
        SystemSpendMeter.install(null);
        assertTrue(SystemSpendMeter.admit(), "without a meter nothing is gated, as before");
    }
}
