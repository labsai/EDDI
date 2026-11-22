/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.spend.DocumentSpendGate.Admission;
import ai.labs.eddi.engine.documents.spend.DocumentSpendGate.Refusal;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.pricing.SpendOwner;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DocumentSpendGate — daily ceilings per user and team")
class DocumentSpendGateTest {

    private static final String USER = "5f9a1c2b3d4e5f6a7b8c9d0e";
    private static final String TEAM = "team:/acme/research";
    private static final Instant NOON = Instant.parse("2026-11-22T12:00:00Z");
    private static final long TODAY = LocalDate.of(2026, 11, 22).toEpochDay();
    private static final SpendOwner OWNER = new SpendOwner("space-tenant-1", USER, TEAM, null);

    private TenantQuotaService tenants;
    private InMemoryDocumentSpendStore store;
    private final AtomicReference<DocumentSpendLimits> limits = new AtomicReference<>(DocumentSpendLimits.defaults());
    private final AtomicReference<Instant> now = new AtomicReference<>(NOON);
    private DocumentSpendGate gate;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantQuotaService.class);
        when(tenants.getDefaultTenantId()).thenReturn("default");
        when(tenants.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        store = new InMemoryDocumentSpendStore();
        gate = new DocumentSpendGate(tenants, store, limits::get, new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });
    }

    private void ceilings(Double user, Double team) {
        limits.set(new DocumentSpendLimits(user == null ? OptionalDouble.empty() : OptionalDouble.of(user),
                team == null ? OptionalDouble.empty() : OptionalDouble.of(team), 35));
    }

    @Nested
    @DisplayName("admission")
    class Admit {

        @Test
        @DisplayName("without ceilings everything the tenant admits is admitted, and no counter is read")
        void noCeilings() {
            store.add(SubjectKind.USER, USER, TODAY, 1_000.0, TODAY - 34);
            Admission admission = gate.admit(OWNER, 50.0);
            assertTrue(admission.admitted());
            assertNull(admission.refusal());
            assertNull(admission.message());
            assertEquals(50.0, admission.estimateUsd());
        }

        @Test
        @DisplayName("the user's daily ceiling refuses with the spend so far and the ceiling")
        void userCeilingRefusesWithBothNumbers() {
            ceilings(5.0, null);
            store.add(SubjectKind.USER, USER, TODAY, 4.0, TODAY - 34);

            Admission admission = gate.admit(OWNER, 1.5);

            assertFalse(admission.admitted());
            assertEquals(Refusal.USER_DAILY_CEILING, admission.refusal());
            assertEquals(4.0, admission.spentUsd(), 1e-12);
            assertEquals(5.0, admission.ceilingUsd(), 1e-12);
            assertEquals(1.5, admission.estimateUsd(), 1e-12);
            assertTrue(admission.message().contains("$4.00") && admission.message().contains("$5.00") && admission.message().contains("$1.50"),
                    admission.message());
            assertFalse(admission.message().contains(USER), "no user id in a message a model may read");
        }

        @Test
        @DisplayName("an estimate that fits under the ceiling is admitted; one that reaches past it is not")
        void estimateAgainstTheCeiling() {
            ceilings(5.0, null);
            store.add(SubjectKind.USER, USER, TODAY, 4.0, TODAY - 34);
            assertTrue(gate.admit(OWNER, 1.0).admitted(), "4 + 1 = 5 is not over 5");
            assertFalse(gate.admit(OWNER, 1.01).admitted());
        }

        @Test
        @DisplayName("an unpriced call (estimate 0) passes a ceiling not yet reached and is refused once it is reached")
        void unpricedEstimate() {
            ceilings(5.0, null);
            store.add(SubjectKind.USER, USER, TODAY, 4.99, TODAY - 34);
            assertTrue(gate.admit(OWNER, 0.0).admitted());
            assertTrue(gate.admit(OWNER, Double.NaN).admitted(), "a non-finite estimate counts as 0");
            store.add(SubjectKind.USER, USER, TODAY, 0.01, TODAY - 34);
            Admission reached = gate.admit(OWNER, 0.0);
            assertFalse(reached.admitted());
            assertEquals(Refusal.USER_DAILY_CEILING, reached.refusal());
        }

        @Test
        @DisplayName("a ceiling of 0 refuses all document work")
        void zeroCeiling() {
            ceilings(0.0, null);
            assertFalse(gate.admit(OWNER, 0.0).admitted());
        }

        @Test
        @DisplayName("the team's daily ceiling refuses when the user's admits")
        void teamCeilingRefuses() {
            ceilings(100.0, 10.0);
            store.add(SubjectKind.TEAM, TEAM, TODAY, 9.5, TODAY - 34);

            Admission admission = gate.admit(OWNER, 1.0);

            assertEquals(Refusal.TEAM_DAILY_CEILING, admission.refusal());
            assertEquals(9.5, admission.spentUsd(), 1e-12);
            assertEquals(10.0, admission.ceilingUsd(), 1e-12);
            assertTrue(admission.message().contains("this team"), admission.message());
            assertFalse(admission.message().contains(TEAM), "no team path in the message");
        }

        @Test
        @DisplayName("order: the tenant budget refuses before the daily ceilings are read")
        void tenantFirst() {
            ceilings(5.0, 5.0);
            store.add(SubjectKind.USER, USER, TODAY, 10.0, TODAY - 34);
            when(tenants.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.denied("over"));

            Admission admission = gate.admit(OWNER, 1.0);

            assertEquals(Refusal.TENANT_BUDGET, admission.refusal());
            verify(tenants).checkCostBudget("default", 1.0);
        }

        @Test
        @DisplayName("order: the user's ceiling is reported before the team's when both are reached")
        void userBeforeTeam() {
            ceilings(5.0, 5.0);
            store.add(SubjectKind.USER, USER, TODAY, 5.0, TODAY - 34);
            store.add(SubjectKind.TEAM, TEAM, TODAY, 5.0, TODAY - 34);
            assertEquals(Refusal.USER_DAILY_CEILING, gate.admit(OWNER, 0.0).refusal());
        }

        @Test
        @DisplayName("the owner's tenant is the one asked; null means the default tenant")
        void ownersTenant() {
            gate.admit(new SpendOwner("k", USER, null, "tenant-b"), 2.0);
            verify(tenants).checkCostBudget("tenant-b", 2.0);
            verify(tenants, never()).checkCostBudget(eq("default"), anyDouble());
        }

        @Test
        @DisplayName("fails closed: an unreadable tenant budget refuses")
        void tenantUnavailable() {
            when(tenants.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.unavailable("down"));
            assertEquals(Refusal.ACCOUNTING_UNAVAILABLE, gate.admit(OWNER, 1.0).refusal());
            when(tenants.checkCostBudget(anyString(), anyDouble())).thenThrow(new IllegalStateException("down"));
            assertEquals(Refusal.ACCOUNTING_UNAVAILABLE, gate.admit(OWNER, 1.0).refusal());
        }

        @Test
        @DisplayName("fails closed: an unreadable daily counter refuses, but only when a ceiling needs it")
        void counterUnavailable() {
            IDocumentSpendStore broken = mock(IDocumentSpendStore.class);
            when(broken.spent(any(), anyString(), anyLong()))
                    .thenThrow(new DocumentSpendStoreException("down", null));
            var brokenGate = new DocumentSpendGate(tenants, broken, limits::get, Clock.fixed(NOON, ZoneOffset.UTC));
            assertTrue(brokenGate.admit(OWNER, 1.0).admitted(), "no ceiling, no read");
            ceilings(5.0, null);
            Admission admission = brokenGate.admit(OWNER, 1.0);
            assertEquals(Refusal.ACCOUNTING_UNAVAILABLE, admission.refusal());
            assertEquals(5.0, admission.ceilingUsd(), 1e-12);
        }

        @Test
        @DisplayName("an owner without user or team is checked against the tenant only")
        void ownerWithoutSubjects() {
            ceilings(0.0, 0.0);
            assertTrue(gate.admit(new SpendOwner("conv-1", null, " ", null), 1.0).admitted());
            assertTrue(gate.admit(null, 1.0).admitted());
        }

        @Test
        @DisplayName("the day is the UTC day: yesterday's spend no longer counts after midnight UTC")
        void utcRollover() {
            ceilings(5.0, null);
            now.set(Instant.parse("2026-11-22T23:59:59Z"));
            gate.record(OWNER, 5.0);
            assertFalse(gate.admit(OWNER, 0.0).admitted());

            now.set(Instant.parse("2026-11-23T00:00:00Z"));
            assertTrue(gate.admit(OWNER, 0.0).admitted(), "a new UTC day starts at 0");
            assertEquals(5.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12);
        }
    }

    @Nested
    @DisplayName("recording")
    class Record {

        @Test
        @DisplayName("one charge adds to the user's and the team's bucket for today")
        void addsToBothBuckets() {
            gate.record(OWNER, 0.75);
            gate.record(OWNER, 0.25);
            assertEquals(1.0, store.spent(SubjectKind.USER, USER, TODAY), 1e-12);
            assertEquals(1.0, store.spent(SubjectKind.TEAM, TEAM, TODAY), 1e-12);
            assertEquals(2L, store.findBySubject(SubjectKind.USER, USER, 1).getFirst().calls());
        }

        @Test
        @DisplayName("a recorded charge is what the next admission sees")
        void recordThenAdmit() {
            ceilings(1.0, null);
            gate.record(OWNER, 1.0);
            assertEquals(Refusal.USER_DAILY_CEILING, gate.admit(OWNER, 0.0).refusal());
        }

        @Test
        @DisplayName("the retention reaches the store: buckets older than retention-days are deleted on write")
        void retention() {
            store.add(SubjectKind.USER, USER, TODAY - 35, 1.0, TODAY - 70);
            store.add(SubjectKind.USER, USER, TODAY - 34, 1.0, TODAY - 70);
            gate.record(OWNER, 1.0);
            assertEquals(0.0, store.spent(SubjectKind.USER, USER, TODAY - 35), "35 days ago is outside 35 retained days");
            assertEquals(1.0, store.spent(SubjectKind.USER, USER, TODAY - 34), 1e-12);
        }

        @Test
        @DisplayName("never throws: a store failure is logged and the work carries on")
        void storeFailureIsSwallowed() {
            IDocumentSpendStore broken = mock(IDocumentSpendStore.class);
            when(broken.add(any(), anyString(), anyLong(), anyDouble(),
                    anyLong())).thenThrow(new DocumentSpendStoreException("down", null));
            var brokenGate = new DocumentSpendGate(tenants, broken, limits::get, Clock.fixed(NOON, ZoneOffset.UTC));
            assertDoesNotThrow(() -> brokenGate.record(OWNER, 1.0));
            verify(broken).add(SubjectKind.USER, USER, TODAY, 1.0, TODAY - 34);
            verify(broken).add(SubjectKind.TEAM, TEAM, TODAY, 1.0, TODAY - 34);
        }

        @Test
        @DisplayName("an owner without user or team records nothing")
        void nothingToRecord() {
            gate.record(new SpendOwner("conv-1", null, "", null), 1.0);
            gate.record(null, 1.0);
            assertTrue(store.findBySubject(SubjectKind.USER, USER, 10).isEmpty());
        }
    }

    @Test
    @DisplayName("amounts in messages keep small figures readable")
    void formatting() {
        assertEquals("$0.00", DocumentSpendGate.usd(0.0));
        assertEquals("$0.0042", DocumentSpendGate.usd(0.0042));
        assertEquals("$12.35", DocumentSpendGate.usd(12.346));
    }
}
