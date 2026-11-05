/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void aFailedGroupReadRefusesInsteadOfAdmitting() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("db down")).when(t.groupStore).read(eq("studio"), anyInt());

        var decision = t.service.decide("studio");

        assertFalse(decision.allowed(), "a read that failed cannot tell a budgeted team from an unbudgeted one");
        assertEquals("unavailable", decision.reason());
    }

    @Test
    void aGroupThatDoesNotExistIsStillAdmittedWhenTheStoreSaysSo() throws Exception {
        doThrow(new IResourceStore.ResourceNotFoundException("no such group")).when(t.groupStore).getCurrentResourceId("gone");

        assertTrue(t.service.decide("gone").allowed());
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
    void theKillSwitchAdmitsAnExhaustedTeamToo() {
        t.store.addSpend("radar", MONTH, 30.0, NOW);
        assertFalse(t.service.decide("radar").allowed(), "precondition: exhausted team is refused");

        t.service.meteringEnabled = false;

        assertTrue(t.service.decide("radar").allowed());
    }

    @Test
    void aRetriedFinishedTransferChangesNothingAndIsNotAuditedOrCountedTwice() {
        t.service.transfer("radar", "studio", 5.0, "x", "alice", "once");
        var again = t.service.transfer("radar", "studio", 5.0, "x", "alice", "once");

        assertEquals(25.0, again.from().capUsd());
        assertEquals(40.0, again.to().capUsd());
        assertEquals(1.0, t.counter("eddi.team.budget.adjustments", "kind", "transfer"));
        verify(t.audit, times(1)).transferred("alice", "radar", "studio", MONTH, 5.0, "x", "once", "APPLIED");
    }

    @Test
    void aRetryAfterAFailedCreditWasReversedIsRefusedAndNeverCreditsTheTarget() {
        var failFirstCredit = new AtomicBoolean(true);
        var flaky = new InMemoryTeamBudgetStore() {
            @Override
            public synchronized AdjustOutcome applyAdjustment(String g, String m, double d, double b, TeamBudgetMonth.Adjustment e, String id,
                                                              Instant x) {
                if (g.equals("studio") && d > 0 && failFirstCredit.getAndSet(false)) {
                    throw new TeamBudgetAccountingUnavailableException("boom");
                }
                return super.applyAdjustment(g, m, d, b, e, id, x);
            }
        };
        var service = new TeamBudgetService(flaky, t.groupStore, t.tenant, t.registry, t.audit, TeamBudgetTestSupport.CLOCK);
        assertThrows(TeamBudgetAccountingUnavailableException.class, () -> service.transfer("radar", "studio", 5.0, "x", "alice", "rev"));

        var e = assertThrows(IllegalArgumentException.class, () -> service.transfer("radar", "studio", 5.0, "x", "alice", "rev"));

        assertTrue(e.getMessage().contains("reversed"), e.getMessage());
        assertEquals(30.0, service.snapshot("radar").capUsd());
        assertEquals(35.0, service.snapshot("studio").capUsd(), "no money appeared");
    }

    @Test
    void aRetryAfterTheDebitSurvivedButTheCreditNeverHappenedFinishesTheCredit() {
        // the first attempt died between debit and credit: debit recorded, no credit,
        // no reversal
        t.store.applyAdjustment("radar", MONTH, -5.0, 30.0,
                new TeamBudgetMonth.Adjustment(NOW, "alice", "TRANSFER_OUT", -5.0, 25.0, "x", "studio", "crash"),
                TeamBudgetService.stepKey("crash", "radar", "studio", 5.0, "out"),
                NOW.plusSeconds(86_400));

        var result = t.service.transfer("radar", "studio", 5.0, "x", "alice", "crash");

        assertEquals(40.0, result.to().capUsd());
        assertEquals(25.0, result.from().capUsd());
    }

    @Test
    void aRetriedKeyWithAnotherAmountOrTargetIsRefusedAndCreditsNothing() {
        // the first attempt debited radar 5.0 for studio and died before the credit
        t.store.applyAdjustment("radar", MONTH, -5.0, 30.0,
                new TeamBudgetMonth.Adjustment(NOW, "alice", "TRANSFER_OUT", -5.0, 25.0, "x", "studio", "k"),
                TeamBudgetService.stepKey("k", "radar", "studio", 5.0, "out"), NOW.plusSeconds(86_400));
        t.group("ops", "Ops", 20.0, null);

        var amount = assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "studio", 9.0, "x", "alice", "k"));
        assertTrue(amount.getMessage().contains("different transfer"), amount.getMessage());
        assertThrows(IllegalArgumentException.class, () -> t.service.transfer("radar", "ops", 5.0, "x", "alice", "k"));

        assertEquals(35.0, t.service.snapshot("studio").capUsd(), "no money appeared");
        assertEquals(20.0, t.service.snapshot("ops").capUsd());
        assertEquals(25.0, t.service.snapshot("radar").capUsd(), "nothing debited twice");
        // the original request still completes
        assertEquals(40.0, t.service.transfer("radar", "studio", 5.0, "x", "alice", "k").to().capUsd());
    }

    @Test
    void aReversalIsRecognisedFromTheAppliedIdsWhenItsHistoryEntryIsGone() {
        String key = "evicted";
        t.store.applyAdjustment("radar", MONTH, -5.0, 30.0,
                new TeamBudgetMonth.Adjustment(NOW, "alice", "TRANSFER_OUT", -5.0, 25.0, "x", "studio", key),
                TeamBudgetService.stepKey(key, "radar", "studio", 5.0, "out"), NOW.plusSeconds(86_400));
        t.store.applyAdjustment("radar", MONTH, 5.0, 30.0,
                new TeamBudgetMonth.Adjustment(NOW, "alice", "TRANSFER_OUT_REVERSED", 5.0, 30.0, "r", "studio", key),
                TeamBudgetService.stepKey(key, "radar", "studio", 5.0, "rev"), NOW.plusSeconds(86_400));
        // the bounded history has dropped both entries; the applied ids remain
        var trimmed = new InMemoryTeamBudgetStore() {
            @Override
            public synchronized Optional<TeamBudgetMonth> read(String g, String m) {
                return t.store.read(g, m).map(x -> new TeamBudgetMonth(x.groupId(), x.month(), x.spentUsd(), x.adjustmentUsd(),
                        x.appliedTransferIds(), List.of()));
            }

            @Override
            public synchronized AdjustOutcome applyAdjustment(String g, String m, double d, double b, TeamBudgetMonth.Adjustment e, String id,
                                                              Instant x) {
                return t.store.applyAdjustment(g, m, d, b, e, id, x);
            }
        };
        var service = new TeamBudgetService(trimmed, t.groupStore, t.tenant, t.registry, t.audit, TeamBudgetTestSupport.CLOCK);

        var e = assertThrows(IllegalArgumentException.class, () -> service.transfer("radar", "studio", 5.0, "x", "alice", key));

        assertTrue(e.getMessage().contains("reversed"), e.getMessage());
        assertEquals(35.0, t.service.snapshot("studio").capUsd(), "no money appeared");
    }

    @Test
    void anUnreadableGroupIsAnOutageNotAMissingGroup() throws Exception {
        doThrow(new IResourceStore.ResourceStoreException("db down")).when(t.groupStore).read(eq("radar"), anyInt());
        assertThrows(TeamBudgetAccountingUnavailableException.class, () -> t.service.setCurrentMonthCap("radar", 50.0, "x", "alice"));
        assertThrows(TeamBudgetAccountingUnavailableException.class, () -> t.service.readConfig("radar"));
        assertNull(t.service.config("radar"), "the lenient read stays lenient");
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
