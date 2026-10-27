/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.SpendReservations;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.tenancy.ITenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyDouble;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_COST;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_METERED_COST;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmSpendGate#admit} — the check of every paid call after a task's
 * first — and {@link TaskSpend}, the phase spend it is asked with.
 */
@DisplayName("LlmSpendGate.admit — spend checks for every attempt")
class LlmSpendGateAdmitTest {

    private static final double EPS = 1e-12;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private TenantQuotaService tenantQuotaService;
    private LlmSpendGate gate;
    private IConversationMemory memory;
    private SpendReservations reservations;

    @BeforeEach
    void setUp() {
        tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("default");
        when(tenantQuotaService.checkCostBudget(eq("default"), anyDouble())).thenReturn(QuotaCheckResult.OK);
        gate = new LlmSpendGate(tenantQuotaService, meterRegistry);
        memory = mock(IConversationMemory.class);
        reservations = new SpendReservations();
        when(memory.getSpendReservations()).thenReturn(reservations);
        when(memory.getConversationCostCeilingUsd()).thenReturn(OptionalDouble.empty());
        spent(0.0);
    }

    @SuppressWarnings("unchecked")
    private void spent(double usd) {
        IConversationMemory.IConversationStepStack steps = mock(IConversationMemory.IConversationStepStack.class);
        IData<Object> cost = mock(IData.class);
        when(cost.getResult()).thenReturn(usd);
        when(steps.getExactDataPerStep(AUDIT_COST)).thenReturn(List.of(cost));
        when(memory.getAllSteps()).thenReturn(steps);
    }

    private void ceiling(double usd) {
        when(memory.getConversationCostCeilingUsd()).thenReturn(OptionalDouble.of(usd));
    }

    private double refusals(String reason) {
        return meterRegistry.counter(LlmSpendGate.METRIC, "reason", reason).count();
    }

    @Test
    @DisplayName("a scheduled fire whose maxCostPerFire is spent is refused by admit(), though the agent's ceiling is not reached")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fireAllowanceSpentRefusesEveryAttempt() {
        ceiling(5.00);
        // $1.00 before the fire, $0.60 by its first call; allowance $0.50.
        IConversationMemory.IWritableConversationStep step = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        IData<Object> allowance = mock(IData.class);
        when(allowance.getResult()).thenReturn(new Context(Context.ContextType.object, 0.5));
        when(step.getData("context:" + ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE)).thenReturn((IData) allowance);
        IData<Object> prior = mock(IData.class);
        when(prior.getResult()).thenReturn(1.0);
        IData<Object> current = mock(IData.class);
        when(current.getResult()).thenReturn(0.6);
        when(step.getData(AUDIT_COST)).thenReturn((IData) current);
        IConversationMemory.IConversationStepStack steps = mock(IConversationMemory.IConversationStepStack.class);
        when(steps.getExactDataPerStep(AUDIT_COST)).thenReturn((List) List.of(prior, current));
        when(memory.getAllSteps()).thenReturn(steps);

        try (var admission = gate.admit(memory, 0.0, 0.0)) {
            assertFalse(admission.allowed());
            assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, admission.refusal().reason());
        }
    }

    @Nested
    @DisplayName("the conversation ceiling")
    class Ceiling {

        @Test
        @DisplayName("what the task has spent but not recorded counts: recorded 0.40 + pending 0.60 reaches 1.00")
        void pendingCounts() {
            ceiling(1.00);
            spent(0.40);

            try (var admission = gate.admit(memory, 0.60, 0.0)) {
                assertFalse(admission.allowed());
                assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, admission.refusal().reason());
            }
            assertEquals(1.0, refusals(LlmSpendGate.REASON_CONVERSATION_CEILING));
            verify(tenantQuotaService, never()).checkCostBudget(anyString(), anyDouble());
        }

        @Test
        @DisplayName("below the ceiling the call is admitted and holds its expected cost until closed")
        void admittedHolds() {
            ceiling(1.00);
            spent(0.40);

            var admission = gate.admit(memory, 0.10, 0.25);

            assertTrue(admission.allowed());
            assertNotNull(admission.reservation());
            assertEquals(0.25, reservations.heldUsd(), EPS);
            admission.close();
            assertEquals(0.0, reservations.heldUsd(), EPS);
        }

        @Test
        @DisplayName("a call in flight is seen by the next one: two admissions cannot both spend the last of the budget")
        void inFlightIsSeen() {
            ceiling(1.00);
            spent(0.50);

            var first = gate.admit(memory, 0.0, 0.50);
            var second = gate.admit(memory, 0.0, 0.10);

            assertTrue(first.allowed());
            assertFalse(second.allowed());
            first.close();
            assertTrue(gate.admit(memory, 0.0, 0.10).allowed(), "released once the first call is over");
        }

        @Test
        @DisplayName("check() — the primary call's gate — sees calls in flight too")
        void checkSeesInFlight() {
            ceiling(1.00);
            spent(0.50);
            var inFlight = gate.admit(memory, 0.0, 0.50);

            assertNotNull(gate.check(memory));
            inFlight.close();
            assertNull(gate.check(memory));
        }

        @Test
        @DisplayName("a memory without reservations still gets the plain ceiling check")
        void withoutReservations() {
            when(memory.getSpendReservations()).thenReturn(null);
            ceiling(1.00);
            spent(0.40);

            assertTrue(gate.admit(memory, 0.50, 5.0).allowed(), "0.90 < 1.00: the crossing call completes");
            assertFalse(gate.admit(memory, 0.60, 0.0).allowed());
        }

        @Test
        @DisplayName("no ceiling: nothing is reserved and the steps are never summed")
        void noCeiling() {
            var admission = gate.admit(memory, 100.0, 5.0);

            assertTrue(admission.allowed());
            assertNull(admission.reservation());
            verify(memory, never()).getAllSteps();
        }

        @Test
        @DisplayName("a negative or NaN pending figure counts as nothing")
        void badPending() {
            ceiling(1.00);
            spent(0.40);

            assertTrue(gate.admit(memory, -5.0, 0.0).allowed());
            assertTrue(gate.admit(memory, Double.NaN, 0.0).allowed());
        }
    }

    @Nested
    @DisplayName("the tenant budget")
    class Tenant {

        @Test
        @DisplayName("a spent tenant budget refuses and releases the reservation the ceiling granted")
        void tenantRefusalReleases() {
            ceiling(1.00);
            when(tenantQuotaService.checkCostBudget(eq("default"), anyDouble())).thenReturn(QuotaCheckResult.denied("Monthly cost budget exceeded"));

            var admission = gate.admit(memory, 0.0, 0.30);

            assertFalse(admission.allowed());
            assertEquals(LlmSpendGate.REASON_TENANT_BUDGET, admission.refusal().reason());
            assertEquals(0.0, reservations.heldUsd(), EPS);
            assertEquals(0, reservations.openReservations());
            assertEquals(1.0, refusals(LlmSpendGate.REASON_TENANT_BUDGET));
        }

        @Test
        @DisplayName("the tenant check sees what this turn spent and the counter has not been charged yet")
        @SuppressWarnings("unchecked")
        void tenantSeesUnsettledTurnSpend() {
            // The monthly counter is charged when the turn settles: this step has cost
            // $0.30, of which $0.10 was metered at an earlier settle (a HITL pause), and
            // the running task has $0.25 not yet in audit:cost.
            IConversationMemory.IWritableConversationStep step = mock(IConversationMemory.IWritableConversationStep.class);
            IData<Object> cost = mock(IData.class);
            when(cost.getResult()).thenReturn(0.30);
            IData<Object> metered = mock(IData.class);
            when(metered.getResult()).thenReturn(0.10);
            when(step.getData(AUDIT_COST)).thenReturn((IData) cost);
            when(step.getData(AUDIT_METERED_COST)).thenReturn((IData) metered);
            when(memory.getCurrentStep()).thenReturn(step);

            gate.admit(memory, 0.25, 0.0);
            gate.check(memory);

            verify(tenantQuotaService).checkCostBudget(eq("default"), doubleThat(v -> Math.abs(v - 0.45) < EPS));
            verify(tenantQuotaService).checkCostBudget(eq("default"), doubleThat(v -> Math.abs(v - 0.20) < EPS));
        }

        @Test
        @DisplayName("a quota store that cannot answer refuses (fail closed)")
        void accountingUnavailable() {
            when(tenantQuotaService.checkCostBudget(eq("default"), anyDouble())).thenReturn(ITenantQuotaStore.accountingUnavailable());

            var admission = gate.admit(memory, 0.0, 0.0);

            assertEquals(LlmSpendGate.REASON_TENANT_ACCOUNTING_UNAVAILABLE, admission.refusal().reason());
        }
    }

    @Nested
    @DisplayName("TaskSpend — the phase's unrecorded spend")
    class PhaseSpend {

        @Test
        @DisplayName("what the phase added and what admitted calls spent are the pending figure of the next call")
        void pendingAccumulates() {
            ceiling(1.00);
            spent(0.40);
            var phase = TaskSpend.of(gate, memory);
            phase.add(0.30);

            try (PaidCallGate.PaidCall call = phase.begin(0.0)) {
                assertTrue(call.admitted());
                call.spent(0.30);
            }

            assertEquals(0.60, phase.pendingUsd(), EPS);
            try (PaidCallGate.PaidCall refused = phase.begin(0.0)) {
                assertFalse(refused.admitted(), "0.40 recorded + 0.60 pending reaches 1.00");
                assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, refused.refusalReason());
            }
            assertEquals(0.0, reservations.heldUsd(), EPS);
        }

        @Test
        @DisplayName("without a gate every call is admitted and the figure is still kept")
        void ungated() {
            var phase = TaskSpend.of(null, memory);

            try (PaidCallGate.PaidCall call = phase.begin(1.0)) {
                assertTrue(call.admitted());
                call.spent(0.5);
            }

            assertEquals(0.5, phase.pendingUsd(), EPS);
        }

        @Test
        @DisplayName("negative and NaN amounts are ignored")
        void badAmounts() {
            var phase = TaskSpend.of(gate, memory);
            phase.add(-1.0);
            phase.add(Double.NaN);

            assertEquals(0.0, phase.pendingUsd(), EPS);
        }
    }
}
