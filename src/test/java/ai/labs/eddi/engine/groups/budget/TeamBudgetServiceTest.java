/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamBudgetServiceTest {

    private static final String MONTH = "2026-11";
    private static final Instant NOW = TeamBudgetTestSupport.CLOCK.instant();

    private TeamBudgetTestSupport t;

    @BeforeEach
    void setUp() {
        t = new TeamBudgetTestSupport();
        t.group("studio", "Content Studio", 35.0, CostPolicy.SYNTHESIZE_NOW);
        t.group("radar", "Signal Radar", 30.0, null);
    }

    @Test
    void snapshotShowsCapSpendRemainingAndTheResetInstant() {
        t.store.addSpend("studio", MONTH, 10.0, NOW);
        TeamBudgetSnapshot s = t.service.snapshot("studio");
        assertEquals(35.0, s.capUsd());
        assertEquals(10.0, s.spentUsd());
        assertEquals(25.0, s.remainingUsd());
        assertFalse(s.exhausted());
        assertEquals("TEAM", s.limitedBy());
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), s.resetsAt());
        assertEquals("Content Studio", s.groupName());
    }

    @Test
    void monthKeyIsTheUtcMonth() {
        assertEquals(MONTH, t.service.currentMonth());
    }

    @Test
    void theMonthRollsOverAtUtcMidnightNotLocalMidnight() {
        for (var entry : Map.of("2026-11-30T23:59:59Z", "2026-11", "2026-12-01T00:00:00Z", "2026-12", "2026-11-01T00:00:00Z", "2026-11")
                .entrySet()) {
            var clock = Clock.fixed(Instant.parse(entry.getKey()), ZoneOffset.ofHours(-10));
            var service = new TeamBudgetService(t.store, t.groupStore, t.tenant, t.registry, t.audit, clock);
            assertEquals(entry.getValue(), service.currentMonth(), entry.getKey());
        }
    }

    @Test
    void equalityRefuses() {
        t.store.addSpend("studio", MONTH, 35.0, NOW);
        var decision = t.service.decide("studio");
        assertFalse(decision.allowed());
        assertEquals("exhausted", decision.reason());
        assertTrue(decision.message().contains("Content Studio"), decision.message());
        assertTrue(decision.message().contains("$35.00"), decision.message());
        assertTrue(decision.message().contains("/groupstore/groups/studio/budget/current-month"), decision.message());
    }

    @Test
    void justUnderTheCapIsAdmitted() {
        t.store.addSpend("studio", MONTH, 34.99, NOW);
        assertTrue(t.service.decide("studio").allowed());
    }

    @Test
    void aSpentTenantRefusesWithTenantAsTheReason() {
        when(t.tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.of(0.0));
        var decision = t.service.decide("studio");
        assertFalse(decision.allowed());
        assertEquals("tenant", decision.reason());
        assertEquals("TENANT", decision.snapshot().limitedBy());
    }

    @Test
    void aTightTenantMakesTheTenantTheLimit() {
        when(t.tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.of(4.0));
        assertEquals("TENANT", t.service.snapshot("studio").limitedBy());
        assertEquals(4.0, t.service.snapshot("studio").headroomUsd());
    }

    @Test
    void anOutageRefusesFailClosedAndIsCounted() {
        t.store.down = true;
        var decision = t.service.decide("studio");
        assertFalse(decision.allowed());
        assertEquals("unavailable", decision.reason());
        var e = assertThrows(TeamBudgetRefusedException.class, () -> t.service.admit("studio", TeamBudgetService.Surface.DISCUSSION));
        assertEquals("unavailable", e.reason());
        assertEquals(1.0, t.counter("eddi.team.budget.refusals", "surface", "discussion", "reason", "unavailable"));
    }

    @Test
    void aTenantCounterOutageAlsoRefuses() {
        when(t.tenant.remainingCostUsd(anyString())).thenThrow(new QuotaAccountingUnavailableException("down"));
        assertEquals("unavailable", t.service.decide("studio").reason());
    }

    @Test
    void anUnbudgetedGroupIsAdmittedWithoutAnyStoreRead() {
        t.group("free", "Free", null, null);
        assertTrue(t.service.decide("free").allowed());
        assertEquals(0, t.store.calls.get());
    }

    @Test
    void anUnknownGroupIsAdmitted() {
        assertTrue(t.service.decide("nope").allowed());
    }

    @Test
    void admitThrowsTheActionableRefusalAndCountsIt() {
        t.store.addSpend("studio", MONTH, 36.0, NOW);
        var e = assertThrows(TeamBudgetRefusedException.class, () -> t.service.admit("studio", TeamBudgetService.Surface.FOLLOWUP));
        assertEquals("exhausted", e.reason());
        assertEquals(36.0, e.snapshot().spentUsd());
        assertEquals(1.0, t.counter("eddi.team.budget.refusals", "surface", "followup", "reason", "exhausted"));
    }

    // ---- set current month ----

    @Test
    void raisingTheCapAdjustsTheDeltaOverTheBaseAndWritesAnAuditEntry() {
        var s = t.service.setCurrentMonthCap("studio", 50.0, "launch week", "alice");
        assertEquals(50.0, s.capUsd());
        assertEquals(15.0, s.adjustmentUsd());
        verify(t.audit).adjusted(eq("alice"), eq("studio"), eq(MONTH), eq(15.0), eq(35.0), eq(50.0), eq("launch week"));
        assertEquals("SET", t.store.read("studio", MONTH).orElseThrow().adjustments().get(0).kind());
    }

    @Test
    void aCapBelowWhatIsSpentIsRejected() {
        t.store.addSpend("studio", MONTH, 20.0, NOW);
        var e = assertThrows(IllegalArgumentException.class, () -> t.service.setCurrentMonthCap("studio", 19.0, "cut", "alice"));
        assertTrue(e.getMessage().contains("already been spent"), e.getMessage());
        assertDoesNotThrow(() -> t.service.setCurrentMonthCap("studio", 20.0, "cut to spend", "alice"));
    }

    @Test
    void aReasonIsRequiredAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> t.service.setCurrentMonthCap("studio", 50.0, " ", "a"));
        assertThrows(IllegalArgumentException.class, () -> t.service.setCurrentMonthCap("studio", 50.0, "x".repeat(1001), "a"));
        assertThrows(IllegalArgumentException.class, () -> t.service.setCurrentMonthCap("studio", Double.NaN, "ok", "a"));
    }

    @Test
    void settingTheCapOfAnUnbudgetedGroupIsRejected() {
        t.group("free", "Free", null, null);
        assertThrows(IllegalArgumentException.class, () -> t.service.setCurrentMonthCap("free", 5.0, "x", "a"));
        verify(t.audit, never()).adjusted(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(), anyString());
    }

    @Test
    void aMidMonthBaseEditSurvivesAnAdjustment() {
        t.service.setCurrentMonthCap("studio", 45.0, "raise", "a"); // adjustment +10
        t.group("studio", "Content Studio", 40.0, CostPolicy.SYNTHESIZE_NOW); // base edited 35 -> 40
        assertEquals(50.0, t.service.snapshot("studio").capUsd(), "40 + 10");
    }

    // ---- transfer ----

    @Test
    void aTransferMovesCapFromOneTeamToTheOther() {
        var result = t.service.transfer("radar", "studio", 5.0, "studio needs it", "alice", "tid-1");
        assertEquals(25.0, result.from().capUsd());
        assertEquals(40.0, result.to().capUsd());
        verify(t.audit).transferred("alice", "radar", "studio", MONTH, 5.0, "studio needs it", "tid-1", "APPLIED");
        assertEquals(1.0, t.counter("eddi.team.budget.adjustments", "kind", "transfer"));
    }

    @Test
    void aTransferBeyondTheSourcesRemainderIsRefusedAndMovesNothing() {
        t.store.addSpend("radar", MONTH, 28.0, NOW);
        var e = assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "studio", 5.0, "x", "alice", "tid-2"));
        assertTrue(e.getMessage().contains("only $2.00"), e.getMessage());
        assertEquals(30.0, t.service.snapshot("radar").capUsd());
        assertEquals(35.0, t.service.snapshot("studio").capUsd());
    }

    @Test
    void aTransferExactlyToTheSourcesSpendIsAllowed() {
        t.store.addSpend("radar", MONTH, 25.0, NOW);
        assertDoesNotThrow(() -> t.service.transfer("radar", "studio", 5.0, "x", "alice", "tid-3"));
        assertEquals(25.0, t.service.snapshot("radar").capUsd());
    }

    @Test
    void aRetriedTransferIdDoesNotMoveMoneyTwice() {
        t.service.transfer("radar", "studio", 5.0, "x", "alice", "same");
        t.service.transfer("radar", "studio", 5.0, "x", "alice", "same");
        assertEquals(25.0, t.service.snapshot("radar").capUsd());
        assertEquals(40.0, t.service.snapshot("studio").capUsd());
    }

    @Test
    void aFailedCreditReversesTheDebit() {
        // credit target "studio" fails: wrap the store so only studio's adjustment
        // throws
        var failing = new InMemoryTeamBudgetStore() {
            @Override
            public synchronized AdjustOutcome applyAdjustment(String g, String m, double d, double b, TeamBudgetMonth.Adjustment e, String id,
                                                              Instant x) {
                if (g.equals("studio") && d > 0) {
                    throw new TeamBudgetAccountingUnavailableException("boom");
                }
                return super.applyAdjustment(g, m, d, b, e, id, x);
            }
        };
        var service = new TeamBudgetService(failing, t.groupStore, t.tenant, t.registry, t.audit, TeamBudgetTestSupport.CLOCK);
        assertThrows(TeamBudgetAccountingUnavailableException.class, () -> service.transfer("radar", "studio", 5.0, "x", "alice", "tid-9"));
        assertEquals(30.0, service.snapshot("radar").capUsd(), "the debit was reversed");
        verify(t.audit).transferred("alice", "radar", "studio", MONTH, 5.0, "x", "tid-9", "REVERSED");
    }

    @Test
    void transferValidatesItsArguments() {
        assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "radar", 1.0, "x", "a", null));
        assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "studio", -1.0, "x", "a", null));
        assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "studio", 1.0, "", "a", null));
        t.group("free", "Free", null, null);
        assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "free", 1.0, "x", "a", null));
    }

    @Test
    void historyIsBoundedByRetention() {
        t.store.addSpend("studio", MONTH, 1.0, NOW);
        assertEquals(1, t.service.history("studio", 6).size());
    }
}
