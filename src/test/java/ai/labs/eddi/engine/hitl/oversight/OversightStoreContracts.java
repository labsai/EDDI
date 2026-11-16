/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.ErasedSubjects;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalBackfillService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecisionProjector;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.CATEGORY;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The contracts of the three oversight stores, run against a real database by
 * each implementation's {@code *IT} (MongoDB and PostgreSQL). Each implementing
 * class must hand out an empty store per test.
 */
public final class OversightStoreContracts {

    private OversightStoreContracts() {
    }

    /** {@link IApprovalDecisionStore}. */
    public interface Decisions {

        IApprovalDecisionStore decisions();

        @Test
        default void insertIsIdempotentAndRoundTrips() {
            ApprovalDecision row = decision("APPROVED", NOW);
            assertTrue(decisions().insertIfAbsent(row));
            assertFalse(decisions().insertIfAbsent(row), "the event and a rebuild must store a unit once");
            List<ApprovalDecision> read = decisions().query(DecisionFilter.ofCategory(AGENT, CATEGORY, null, null), 0, 10);
            assertEquals(List.of(row), read);
        }

        @Test
        default void filtersByWindowAndFieldsNewestFirst() {
            ApprovalDecision older = decision("APPROVED", NOW.minusSeconds(3600));
            ApprovalDecision newer = decision("REJECTED", NOW.minusSeconds(60));
            ApprovalDecision outside = decision("APPROVED", NOW.minusSeconds(86_400 * 10));
            decisions().insertIfAbsent(older);
            decisions().insertIfAbsent(newer);
            decisions().insertIfAbsent(outside);

            DecisionFilter window = DecisionFilter.ofCategory(AGENT, CATEGORY, NOW.minusSeconds(86_400), NOW);
            assertEquals(List.of(newer, older), decisions().query(window, 0, 10));
            assertEquals(2, decisions().count(window));
            assertEquals(List.of(older), decisions().query(window, 1, 10), "skip");
            assertEquals(0, decisions().count(new DecisionFilter(AGENT, null, null, null, null, null, null, "SLACK", null)));
            assertEquals(3, decisions().count(new DecisionFilter(AGENT, null, null, null, null, "regular", "TOOL_CALL", "MANAGER", 3)));
        }

        @Test
        default void retentionDeletesOldRows() {
            decisions().insertIfAbsent(decision("APPROVED", NOW.minusSeconds(86_400 * 500L)));
            decisions().insertIfAbsent(decision("APPROVED", NOW));
            assertEquals(1, decisions().deleteDecidedBefore(NOW.minusSeconds(86_400 * 400L)));
            assertEquals(1, decisions().count(new DecisionFilter(null, null, null, null, null, null, null, null, null)));
        }

        @Test
        default void erasureDropsTheUserAndPseudonymizesTheDecider() {
            ApprovalDecision row = decision("APPROVED", NOW);
            decisions().insertIfAbsent(row);
            assertEquals(1, decisions().readByUser("user-1", 10).size());
            assertEquals(1, decisions().readByUser("alice", 10).size());

            decisions().eraseUser("user-1", "gdpr:x");
            decisions().eraseUser("alice", "gdpr:y");

            ApprovalDecision after = decisions().query(DecisionFilter.ofCategory(AGENT, CATEGORY, null, null), 0, 1).getFirst();
            assertNull(after.userId());
            assertEquals("gdpr:y", after.decidedBy());
            assertTrue(decisions().readByUser("user-1", 10).isEmpty());
        }

        @Test
        default void aPendingRowIsHiddenFromEveryReadUntilSettled() {
            ApprovalDecision masked = decision("APPROVED", NOW).withPersonalFields(null, "gdpr:alice");
            DecisionFilter all = new DecisionFilter(null, null, null, null, null, null, null, null, null);
            assertTrue(decisions().insertPendingIfAbsent(masked));
            assertFalse(decisions().insertPendingIfAbsent(masked));
            assertFalse(decisions().insertIfAbsent(masked), "one id, one row, pending or not");

            assertTrue(decisions().query(all, 0, 10).isEmpty(), "analytics never read a pending row");
            assertEquals(0, decisions().count(all));
            assertTrue(decisions().readByUser("gdpr:alice", 10).isEmpty(), "nor does the export");
            assertEquals(List.of(new IApprovalDecisionStore.Pending(masked, IApprovalDecisionStore.INITIAL_VERSION)), decisions().pendingCheck(10));

            // Write the confirmed identity back, still pending: still hidden.
            assertTrue(decisions().updateIdentities(masked.id(), 0, "user-1", "alice", true));
            assertTrue(decisions().query(all, 0, 10).isEmpty());
            assertTrue(decisions().readByUser("alice", 10).isEmpty());

            assertFalse(decisions().updateIdentities(masked.id(), 0, "user-1", "alice", false), "the version moved on");
            assertTrue(decisions().updateIdentities(masked.id(), 1, "user-1", "alice", false));
            assertEquals(List.of(decision("APPROVED", NOW)), decisions().query(all, 0, 10));
            assertEquals(1, decisions().readByUser("alice", 10).size());
            assertTrue(decisions().pendingCheck(10).isEmpty());
        }

        @Test
        default void updateIdentitiesIsCompareAndSet_andTouchesOnlyItsRow() {
            ApprovalDecision first = decision("APPROVED", NOW.minusSeconds(60));
            ApprovalDecision second = decision("REJECTED", NOW);
            decisions().insertIfAbsent(first);
            decisions().insertIfAbsent(second);

            assertFalse(decisions().updateIdentities(first.id(), 7, null, "gdpr:alice", false), "stale version");
            assertTrue(decisions().updateIdentities(first.id(), 0, null, "gdpr:alice", false));

            var rows = decisions().query(DecisionFilter.ofCategory(AGENT, CATEGORY, null, null), 0, 10);
            ApprovalDecision changed = rows.stream().filter(r -> r.id().equals(first.id())).findFirst().orElseThrow();
            ApprovalDecision untouched = rows.stream().filter(r -> r.id().equals(second.id())).findFirst().orElseThrow();
            assertNull(changed.userId());
            assertEquals("gdpr:alice", changed.decidedBy());
            assertEquals("user-1", untouched.userId(), "another row of the same people is not touched");
            assertEquals("alice", untouched.decidedBy());
        }

        @Test
        default void erasureReachesPendingRows_andAStaleSettlementCannotUndoIt() {
            ApprovalDecision raw = decision("APPROVED", NOW);
            decisions().insertPendingIfAbsent(raw);
            decisions().eraseUser("alice", "gdpr:alice");

            assertFalse(decisions().updateIdentities(raw.id(), 0, "user-1", "alice", false),
                    "a settlement that read before the erasure must not restore the raw id");
            ApprovalDecision stored = decisions().pendingCheck(10).getFirst().row();
            assertEquals("gdpr:alice", stored.decidedBy());
        }

        /**
         * Two settlers and an erasure that returns the row to the very values a stale
         * settler expects (owner and decider are the same erased person): the version,
         * not the values, decides, so neither stale write lands.
         */
        @Test
        default void staleSettlersCannotRestoreAnIdentityAfterErasure_evenToTheSameValues() {
            ApprovalDecision raw = decision("APPROVED", NOW).withPersonalFields("bob", "bob");
            ApprovalDecision masked = raw.withPersonalFields(null, "gdpr:bob");
            decisions().insertPendingIfAbsent(masked);
            // Settler A writes bob back (version 0 -> 1) and pauses before confirming.
            assertTrue(decisions().updateIdentities(masked.id(), 0, "bob", "bob", true));
            // Erasure masks the row again, to exactly the pair settler B read.
            decisions().eraseUser("bob", "gdpr:bob");

            assertFalse(decisions().updateIdentities(masked.id(), 0, "bob", "bob", true), "settler B's stale write-back");
            assertFalse(decisions().updateIdentities(masked.id(), 1, "bob", "bob", false), "settler A's stale confirmation");

            IApprovalDecisionStore.Pending stored = decisions().pendingCheck(10).getFirst();
            assertNull(stored.row().userId());
            assertEquals("gdpr:bob", stored.row().decidedBy());
            assertTrue(stored.version() > 1, "erasure moved the version on");
            DecisionFilter all = new DecisionFilter(null, null, null, null, null, null, null, null, null);
            assertTrue(decisions().readByUser("bob", 10).isEmpty());
            assertTrue(decisions().query(all, 0, 10).isEmpty(), "still pending, so no read sees it");

            // The settlement that runs next reads the current version and settles the
            // erased form.
            assertTrue(decisions().updateIdentities(masked.id(), stored.version(), null, "gdpr:bob", false));
            List<ApprovalDecision> settled = decisions().query(all, 0, 10);
            assertEquals(1, settled.size());
            assertNull(settled.getFirst().userId());
            assertEquals("gdpr:bob", settled.getFirst().decidedBy());
            assertTrue(decisions().readByUser("bob", 10).isEmpty(), "no read returns the erased identity");
        }

        /**
         * The projector and reconciliation against this store: interruption after the
         * insert, a failed confirmation, reads before reconciliation, and
         * reconciliation upgrading a person who was not erased.
         */
        @Test
        default void projectionInterruptedOrFailed_isHiddenAndReconciled() {
            Map<String, Instant> erased = new HashMap<>();
            IErasedSubjectStore erasedStore = new IErasedSubjectStore() {
                @Override
                public void record(String token, Instant at) {
                    erased.merge(token, at, (a, b) -> a.isAfter(b) ? a : b);
                }

                @Override
                public Optional<Instant> erasedAt(Collection<String> tokens) {
                    return tokens.stream().map(erased::get).filter(Objects::nonNull).max(Instant::compareTo);
                }
            };
            GdprPseudonymizer pseudonymizer = mock(GdprPseudonymizer.class);
            when(pseudonymizer.stableTokenFor(anyString())).thenAnswer(inv -> "tok:" + inv.getArgument(0));
            AuditLedgerService ledger = mock(AuditLedgerService.class);
            when(ledger.pseudonymOf(anyString())).thenAnswer(inv -> "gdpr-erased:k1:" + inv.getArgument(0));
            ErasedSubjects subjects = new ErasedSubjects(erasedStore, pseudonymizer, ledger);
            @SuppressWarnings("unchecked")
            Instance<AutonomyService> autonomy = mock(Instance.class);
            var projector = new ApprovalDecisionProjector(decisions(), mock(OversightMetrics.class), autonomy, subjects);

            AuditEntry carolEntry = legacyEntry("e-carol", "carol");
            AuditEntry bobEntry = legacyEntry("e-bob", "bob");
            IAuditStore auditStore = mock(IAuditStore.class);
            when(auditStore.getEntries(eq("conv-e-carol"), anyInt(), anyInt())).thenReturn(List.of(carolEntry));
            when(auditStore.getEntries(eq("conv-e-bob"), anyInt(), anyInt())).thenReturn(List.of(bobEntry));
            var backfill = new ApprovalBackfillService(auditStore, projector);
            ApprovalDecision carol = ApprovalDecision.of(HitlApprovalAudit.parse(carolEntry).getFirst(), 0);
            ApprovalDecision bob = ApprovalDecision.of(HitlApprovalAudit.parse(bobEntry).getFirst(), 0);

            // Interrupted right after the insert: only the masked row rests in the store.
            assertTrue(decisions().insertPendingIfAbsent(carol.withPersonalFields(null, "gdpr-erased:k1:carol")));
            // Failed between write-back and confirmation: the identity is back, still
            // pending.
            assertTrue(decisions().insertPendingIfAbsent(bob.withPersonalFields(null, "gdpr-erased:k1:bob")));
            assertTrue(decisions().updateIdentities(bob.id(), 0, "user-1", "bob", true));

            DecisionFilter all = new DecisionFilter(null, null, null, null, null, null, null, null, null);
            assertTrue(decisions().query(all, 0, 10).isEmpty(), "reads before reconciliation see neither row");
            assertEquals(0, decisions().count(all));
            assertTrue(decisions().readByUser("bob", 10).isEmpty(), "nor does the export");
            assertTrue(decisions().readByUser("user-1", 10).isEmpty());

            subjects.record("bob", NOW.plusSeconds(3600));
            assertEquals(2, backfill.reconcilePending());

            List<ApprovalDecision> settled = decisions().query(all, 0, 10);
            ApprovalDecision carolNow = settled.stream().filter(r -> r.id().equals(carol.id())).findFirst().orElseThrow();
            ApprovalDecision bobNow = settled.stream().filter(r -> r.id().equals(bob.id())).findFirst().orElseThrow();
            assertEquals("carol", carolNow.decidedBy(), "not erased: upgraded to the identity in the ledger");
            assertEquals("user-1", carolNow.userId());
            assertEquals("gdpr-erased:k1:bob", bobNow.decidedBy(), "erased meanwhile: reverted to the erased form");
            assertTrue(decisions().pendingCheck(10).isEmpty());
            assertTrue(decisions().readByUser("bob", 10).isEmpty());
        }

        private static AuditEntry legacyEntry(String id, String decider) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("verdict", "APPROVED");
            output.put("decidedBy", decider);
            output.put("automated", false);
            return new AuditEntry(id, "conv-" + id, AGENT, 2, "user-1", "production", -1, HitlApprovalAudit.TASK_ID, "hitl", -1, 0L, Map.of(),
                    output, null, null, List.of(), 0.0, NOW, null, null);
        }
    }

    /** {@link IAuditSampleStore}. */
    public interface Samples {

        IAuditSampleStore samples();

        @Test
        default void insertAndReadRoundTrip() {
            AuditSample s = sample("s1");
            samples().insert(s);
            assertEquals(Optional.of(s), samples().read("s1"));
            assertEquals(Optional.empty(), samples().read("missing"));
        }

        @Test
        default void reviewIsCompareAndSetFromPending() {
            AuditSample s = sample("s1");
            samples().insert(s);
            AuditSample flagged = s.reviewed(AuditSample.Status.FLAGGED, "TONE", "rude", "erin", NOW);

            assertTrue(samples().review(flagged, 0L).isEmpty(), "a stale version must not win");
            AuditSample stored = samples().review(flagged, s.version()).orElseThrow();
            assertEquals(AuditSample.Status.FLAGGED, stored.status());
            assertEquals("TONE", stored.reasonCode());
            assertEquals("erin", stored.reviewedBy());
            assertEquals(s.version() + 1, stored.version());
            assertTrue(samples().review(flagged, stored.version()).isEmpty(), "a reviewed sample is no longer PENDING");
        }

        @Test
        default void pendingQueueIsOrderedByDueDateAndExpires() {
            AuditSample late = withDue(sample("late"), NOW.plusSeconds(7200));
            AuditSample early = withDue(sample("early"), NOW.plusSeconds(3600));
            AuditSample overdue = withDue(sample("overdue"), NOW.minusSeconds(60));
            samples().insert(late);
            samples().insert(early);
            samples().insert(overdue);

            assertEquals(List.of("overdue", "early", "late"),
                    samples().query(SampleFilter.pending(AGENT), 0, 10).stream().map(AuditSample::id).toList());
            assertEquals(1, samples().expireOverdue(NOW));
            assertEquals(2, samples().count(SampleFilter.pending(AGENT)));
            assertEquals(AuditSample.Status.EXPIRED, samples().read("overdue").orElseThrow().status());
            assertEquals(1, samples().count(new SampleFilter(AGENT, CATEGORY, AuditSample.Status.EXPIRED, null, null)));
        }

        @Test
        default void retentionAndErasure() {
            samples().insert(withSampledAt(sample("old"), NOW.minusSeconds(86_400 * 200L)));
            AuditSample reviewed = sample("mine");
            samples().insert(reviewed);
            samples().review(reviewed.reviewed(AuditSample.Status.OK, null, null, "erin", NOW), reviewed.version());

            assertEquals(1, samples().deleteSampledBefore(NOW.minusSeconds(86_400 * 180L)));
            assertEquals(1, samples().pseudonymizeReviewer("erin", "gdpr:e"));
            assertEquals("gdpr:e", samples().read("mine").orElseThrow().reviewedBy());
            assertEquals(1, samples().readByUser("user-1", 10).size());
            assertEquals(1, samples().deleteByUser("user-1"));
            assertTrue(samples().read("mine").isEmpty());
        }

        private static AuditSample withDue(AuditSample s, Instant due) {
            return new AuditSample(s.id(), s.agentId(), s.agentVersion(), s.conversationId(), s.userId(), s.callId(), s.toolName(), s.source(),
                    s.endpoint(), s.category(), s.rulePattern(), s.exemptPattern(), s.argumentsRedacted(), s.argsDigest(), s.resultRedacted(),
                    s.sampledAt(), due, s.status(), s.reasonCode(), s.note(), s.reviewedBy(), s.reviewedAt(), s.version());
        }

        private static AuditSample withSampledAt(AuditSample s, Instant at) {
            return new AuditSample(s.id(), s.agentId(), s.agentVersion(), s.conversationId(), s.userId(), s.callId(), s.toolName(), s.source(),
                    s.endpoint(), s.category(), s.rulePattern(), s.exemptPattern(), s.argumentsRedacted(), s.argsDigest(), s.resultRedacted(),
                    at, s.reviewDueAt(), s.status(), s.reasonCode(), s.note(), s.reviewedBy(), s.reviewedAt(), s.version());
        }
    }

    /** {@link IAutonomyStateStore}. */
    public interface States {

        IAutonomyStateStore states();

        @Test
        default void insertThenCompareAndSet() {
            AutonomyState initial = AutonomyState.initial(AGENT, CATEGORY);
            AutonomyState stored = states().save(initial, 0L).orElseThrow();
            assertEquals(1L, stored.version());
            assertTrue(states().save(initial, 0L).isEmpty(), "a second insert must lose");

            AutonomyState next = stored.withEvidenceResetAt(NOW);
            assertEquals(2L, states().save(next, 1L).orElseThrow().version());
            assertTrue(states().save(next, 1L).isEmpty(), "a stale version must lose");
            assertEquals(NOW, states().read(AGENT, CATEGORY).orElseThrow().evidenceResetAt());
        }

        @Test
        default void suspensionsAreListedAndCounted() {
            states().save(AutonomyState.initial(AGENT, CATEGORY)
                    .withSuspension(new Suspension(true, "p", NOW, Suspension.Trigger.MANUAL, null, null, "bob", null, null, "n")), 0L);
            states().save(AutonomyState.initial(AGENT, "tool:other"), 0L);
            states().save(AutonomyState.initial("agent-2", CATEGORY), 0L);

            assertEquals(List.of(CATEGORY), states().listSuspended(AGENT).stream().map(AutonomyState::category).toList());
            assertEquals(1, states().countSuspended());
            assertEquals(2, states().list(AGENT, 10).size());
            assertEquals(3, states().list(null, 10).size());

            AutonomyState suspended = states().read(AGENT, CATEGORY).orElseThrow();
            states().save(suspended.withSuspension(suspended.suspension().lifted("bob", NOW, "ok")), suspended.version());
            assertTrue(states().listSuspended(AGENT).isEmpty());
            assertEquals(0, states().countSuspended());
        }
    }
}
