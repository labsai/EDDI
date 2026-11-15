/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
