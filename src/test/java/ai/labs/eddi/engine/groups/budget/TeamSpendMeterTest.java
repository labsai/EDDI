/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class TeamSpendMeterTest {

    private static final String MONTH = "2026-11";

    private TeamBudgetTestSupport t;
    private AgentGroupConfiguration config;

    @BeforeEach
    void setUp() {
        t = new TeamBudgetTestSupport();
        config = t.group("g1", "Content Studio", 35.0, CostPolicy.SYNTHESIZE_NOW);
    }

    private GroupConversation gc(int depth, double totalCost) {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        gc.setGroupId("g1");
        gc.setDepth(depth);
        gc.setTotalCost(totalCost);
        return gc;
    }

    private double spent() {
        return t.store.read("g1", MONTH).map(TeamBudgetMonth::spentUsd).orElse(0.0);
    }

    @Test
    void chargesOnlyTheIncreaseAndADuplicateCallIsANoOp() {
        GroupConversation gc = gc(0, 2.0);
        t.meter.charge(gc);
        t.meter.charge(gc);
        assertEquals(2.0, spent(), 1e-9);
        gc.setTotalCost(3.5);
        t.meter.charge(gc);
        t.meter.charge(gc);
        assertEquals(3.5, spent(), 1e-9);
        assertEquals(3.5, gc.getTeamBudgetState().getChargedUsd(), 1e-9);
    }

    @Test
    void aNestedChildChargesNothing() {
        GroupConversation child = gc(1, 4.0);
        t.meter.charge(child);
        t.meter.beginLeg(child, config);
        t.meter.endLeg(child);
        assertEquals(0, t.store.calls.get(), "no store round trip for a child");
        assertEquals(0.0, spent());
        assertNull(child.getTeamBudgetState());
    }

    @Test
    void parallelCallsChargeOnce() throws Exception {
        GroupConversation gc = gc(0, 5.0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                t.meter.charge(gc);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(5.0, spent(), 1e-9);
    }

    @Test
    void anOutageCarriesTheAmountForwardAndNeverThrows() {
        GroupConversation gc = gc(0, 2.0);
        t.store.down = true;
        assertDoesNotThrow(() -> t.meter.charge(gc));
        assertEquals(2.0, gc.getTeamBudgetState().getPendingUsd(), 1e-9);
        assertEquals(1.0, t.counter("eddi.team.budget.metering", "outcome", "unavailable"));
        t.store.down = false;
        gc.setTotalCost(2.5);
        t.meter.charge(gc);
        assertEquals(2.5, spent(), 1e-9, "the held-back 2.0 and the new 0.5");
        assertEquals(0.0, gc.getTeamBudgetState().getPendingUsd(), 1e-9);
    }

    @Test
    void aNonFiniteOrNegativeTotalIsIgnored() {
        GroupConversation gc = gc(0, Double.NaN);
        t.meter.charge(gc);
        gc.setTotalCost(-1.0);
        t.meter.charge(gc);
        assertEquals(0.0, spent());
    }

    @Test
    void meteringCanBeSwitchedOff() {
        t.meter.meteringEnabled = false;
        t.meter.charge(gc(0, 3.0));
        assertEquals(0.0, spent());
    }

    @Test
    void theCeilingIsAnchoredToTheRunsOwnLedger() {
        t.store.addSpend("g1", MONTH, 30.0, TeamBudgetTestSupport.CLOCK.instant());
        GroupConversation gc = gc(0, 1.0);
        t.meter.beginLeg(gc, config);
        // charged 1.0 -> team spent 31.0, remaining 4.0 -> ceiling 1.0 + 4.0
        assertEquals(5.0, gc.getTeamBudgetState().getCeilingUsd(), 1e-9);
        assertEquals("TEAM", gc.getTeamBudgetState().getLimitedBy());
        assertEquals(CostPolicy.SYNTHESIZE_NOW, gc.getTeamBudgetState().getPolicy());
        // the run spends 1.5 more; another discussion spent 2 meanwhile
        t.store.addSpend("g1", MONTH, 2.0, TeamBudgetTestSupport.CLOCK.instant());
        gc.setTotalCost(2.5);
        t.meter.charge(gc);
        // team spent 31 + 2 + 1.5 = 34.5, remaining 0.5 -> ceiling 2.5 + 0.5
        assertEquals(3.0, gc.getTeamBudgetState().getCeilingUsd(), 1e-9);
    }

    @Test
    void anExhaustedTeamClampsTheRemainingAtZeroNotNegative() {
        t.store.addSpend("g1", MONTH, 40.0, TeamBudgetTestSupport.CLOCK.instant());
        GroupConversation gc = gc(0, 2.0);
        t.meter.beginLeg(gc, config);
        assertEquals(2.0, gc.getTeamBudgetState().getCeilingUsd(), 1e-9, "charged + max(0, remaining)");
    }

    @Test
    void theTenantRemainderCanBind() {
        when(t.tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.of(3.0));
        GroupConversation gc = gc(0, 1.0);
        t.meter.beginLeg(gc, config);
        // team remaining is 34.0 (after the 1.0 charge), tenant 3.0
        assertEquals(4.0, gc.getTeamBudgetState().getCeilingUsd(), 1e-9);
        assertEquals("TENANT", gc.getTeamBudgetState().getLimitedBy());
    }

    @Test
    void anUnbudgetedGroupIsMeteredButHasNoCeiling() {
        t.group("g2", "Free", null, null);
        GroupConversation gc = new GroupConversation();
        gc.setId("gc2");
        gc.setGroupId("g2");
        gc.setTotalCost(1.25);
        t.meter.beginLeg(gc, t.service.config("g2"));
        assertNull(gc.getTeamBudgetState().getCeilingUsd());
        assertEquals(1.25, t.store.read("g2", MONTH).orElseThrow().spentUsd(), 1e-9);
    }

    @Test
    void anUnreadableBudgetAtLegStartKeepsTheLastKnownCeiling() {
        GroupConversation gc = gc(0, 0.0);
        t.meter.beginLeg(gc, config);
        double before = gc.getTeamBudgetState().getCeilingUsd();
        t.store.down = true;
        assertDoesNotThrow(() -> t.meter.beginLeg(gc, config));
        assertEquals(before, gc.getTeamBudgetState().getCeilingUsd(), 1e-9);
    }

    @Test
    void policyFallsBackToTheProtocolWhenNotSet() {
        AgentGroupConfiguration c = t.group("g3", "NoPolicy", 10.0, null);
        c.setProtocol(new AgentGroupConfiguration.ProtocolConfig(60, null, 1, null, 0, null, CostPolicy.ABORT));
        GroupConversation gc = new GroupConversation();
        gc.setId("gc3");
        gc.setGroupId("g3");
        t.meter.beginLeg(gc, c);
        assertEquals(CostPolicy.ABORT, gc.getTeamBudgetState().getPolicy());
    }
}
