/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.ErasedSubjects;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.hitl.oversight.OversightGdprParticipant;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A rebuild of approval analytics from the audit ledger after a GDPR erasure.
 * The ledger entries are filed under the conversation owner and name the
 * decider in {@code output.decidedBy}; erasing a decider who is not the owner
 * leaves those entries as they were, so the rebuild must consult the
 * erased-subject record rather than the entry.
 */
@DisplayName("Approval analytics rebuild after a GDPR erasure")
class ApprovalRebuildAfterErasureTest {

    private static final Instant DECIDED = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant ERASED = DECIDED.plusSeconds(3600);

    private final InMemoryDecisions decisions = new InMemoryDecisions();
    private final InMemoryErasedSubjects erasedStore = new InMemoryErasedSubjects();
    private final List<AuditEntry> ledger = new ArrayList<>();
    private OversightMetrics metrics;
    private ErasedSubjects erasedSubjects;
    private OversightGdprParticipant participant;
    private ApprovalBackfillService backfill;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GdprPseudonymizer pseudonymizer = mock(GdprPseudonymizer.class);
        when(pseudonymizer.stableTokenFor(anyString())).thenAnswer(inv -> "gdpr-erased:s1:token(" + inv.getArgument(0) + ")");
        AuditLedgerService auditLedger = mock(AuditLedgerService.class);
        when(auditLedger.pseudonymOf(anyString())).thenAnswer(inv -> pseudonym(inv.getArgument(0)));
        erasedSubjects = new ErasedSubjects(erasedStore, pseudonymizer, auditLedger);

        metrics = mock(OversightMetrics.class);
        Instance<AutonomyService> autonomy = mock(Instance.class);
        var projector = new ApprovalDecisionProjector(decisions, metrics, autonomy, erasedSubjects);

        IAuditStore auditStore = mock(IAuditStore.class);
        when(auditStore.getEntriesByTask(eq(HitlApprovalAudit.TASK_ID), isNull(), anyInt(), anyInt())).thenAnswer(inv -> {
            int skip = inv.getArgument(2);
            int limit = inv.getArgument(3);
            return ledger.subList(Math.min(skip, ledger.size()), Math.min(skip + limit, ledger.size()));
        });
        backfill = new ApprovalBackfillService(auditStore, projector);

        IAutonomyStateStore stateStore = mock(IAutonomyStateStore.class);
        when(stateStore.list(any(), anyInt())).thenReturn(List.of());
        participant = new OversightGdprParticipant(decisions, mock(IAuditSampleStore.class), stateStore, auditLedger);
    }

    private static String pseudonym(String userId) {
        return "gdpr-erased:k1:" + userId;
    }

    /** What the GDPR cascade does for this store: record, then the participant. */
    private void erase(String userId, Instant at) {
        erasedSubjects.record(userId, at);
        participant.erase(userId);
    }

    /**
     * A pre-6.7 hitl.approval entry: filed under {@code owner}, decided by
     * {@code decider}.
     */
    private static AuditEntry entry(String id, String owner, String decider, Instant at) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("verdict", "APPROVED");
        output.put("decidedBy", decider);
        output.put("automated", false);
        return new AuditEntry(id, "conv-" + id, "agent-1", 2, owner, "production", -1, HitlApprovalAudit.TASK_ID, "hitl", -1, 0L, Map.of(),
                output, null, null, List.of(), 0.0, at, null, null);
    }

    private boolean anyRowNames(String userId) {
        return decisions.rows.values().stream().anyMatch(r -> userId.equals(r.userId()) || userId.equals(r.decidedBy()));
    }

    @Test
    @DisplayName("rows lost after the erasure come back from the ledger without the erased decider, who stays counted as a pseudonym")
    void lostRowsRebuiltWithoutErasedDecider() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        ledger.add(entry("d2", "alice", "carol", DECIDED));
        ledger.add(entry("d3", "dave", "bob", DECIDED));
        backfill.rebuild(null, null);
        assertEquals(3, decisions.rows.size());

        erase("bob", ERASED);
        assertTrue(!anyRowNames("bob"), "the erasure pseudonymised the stored rows");
        // The rows are lost (retention, a dropped collection, a failed write) and
        // rebuilt.
        decisions.rows.clear();
        var result = backfill.rebuild(null, null);

        assertEquals(3, result.unitsStored(), "every decision is counted again");
        assertTrue(!anyRowNames("bob"), "no rebuilt row names the erased decider: " + decisions.rows.values());
        assertEquals(2, decisions.rows.values().stream().filter(r -> pseudonym("bob").equals(r.decidedBy())).count(),
                "the erased decider is the same pseudonym erasure writes, so per-decider counts stay correct");
        assertEquals("carol", decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy(), "another decider is untouched");
        assertEquals("alice", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).userId(), "a conversation owner who was not erased is kept");
        assertEquals("dave", decisions.rows.get(ApprovalDecision.idOf("d3", 0)).userId());
        assertTrue(decisions.readByUser("bob", 100).isEmpty(), "an export of the erased user finds nothing");
    }

    @Test
    @DisplayName("an erased owner who decided their own pause: the rebuilt row has no userId and the pseudonym as decider")
    void erasedOwnerAndDecider() {
        // erasure-mode=pseudonymize keeps output.decidedBy; the entry's userId is
        // already the pseudonym.
        ledger.add(entry("d1", pseudonym("alice"), "alice", DECIDED));
        erase("alice", ERASED);

        backfill.rebuild(null, null);

        ApprovalDecision row = decisions.rows.get(ApprovalDecision.idOf("d1", 0));
        assertEquals(pseudonym("alice"), row.decidedBy());
        assertTrue(!anyRowNames("alice"));
    }

    @Test
    @DisplayName("a raw owner id in a ledger entry erasure did not reach is dropped, as erasure drops it from stored rows")
    void erasedOwnerRawId() {
        ledger.add(entry("d1", "alice", "carol", DECIDED));
        erase("alice", ERASED);

        backfill.rebuild(null, null);

        ApprovalDecision row = decisions.rows.get(ApprovalDecision.idOf("d1", 0));
        assertNull(row.userId());
        assertEquals("carol", row.decidedBy());
    }

    @Test
    @DisplayName("erase → rebuild → erase → rebuild converges: nothing is stored twice and the record keeps the later time")
    void idempotent() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        erase("bob", ERASED);
        backfill.rebuild(null, null);
        Map<String, ApprovalDecision> first = new LinkedHashMap<>(decisions.rows);

        erase("bob", ERASED.minusSeconds(60));
        var second = backfill.rebuild(null, null);

        assertEquals(0, second.unitsStored());
        assertEquals(first, decisions.rows);
        assertEquals(ERASED, erasedSubjects.erasedAt("bob").orElseThrow(), "an earlier time never narrows the window");
        assertEquals(1, erasedStore.rows.size());
    }

    @Test
    @DisplayName("a decision made after the erasure belongs to a returning user and keeps their id")
    void decisionAfterErasureKeepsId() {
        erase("bob", ERASED);
        ledger.add(entry("d1", "alice", "bob", ERASED.plusSeconds(60)));
        ledger.add(entry("d2", "alice", "bob", ERASED));

        backfill.rebuild(null, null);

        assertEquals("bob", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy());
        assertEquals(pseudonym("bob"), decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy(),
                "a decision at the erasure instant is covered");
    }

    @Test
    @DisplayName("when the record cannot be read the unit is not stored (a later rebuild repairs it), never stored with the raw id")
    void unreadableRecordFailsClosed() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        erasedStore.failReads = true;

        var result = backfill.rebuild(null, null);

        assertEquals(0, result.unitsStored());
        assertTrue(decisions.rows.isEmpty());
        verify(metrics).projectionFailed();

        erasedStore.failReads = false;
        assertEquals(1, backfill.rebuild(null, null).unitsStored());
    }

    @Test
    @DisplayName("system and unverified deciders are never looked up")
    void nonPersonalIdentitiesNotLookedUp() {
        ledger.add(entry("d1", "alice", "system:timeout", DECIDED));
        ledger.add(entry("d2", "alice", "unknown", DECIDED));

        backfill.rebuild(null, null);

        assertEquals(2, decisions.rows.size());
        assertTrue(erasedStore.queried.stream().allMatch("gdpr-erased:s1:token(alice)"::equals), erasedStore.queried.toString());
        assertTrue(!erasedStore.queried.isEmpty(), "the owner was looked up");
    }

    @Test
    @DisplayName("an erasure landing between the check and the insert: the row is scrubbed by the re-check after the insert")
    void erasureBetweenCheckAndInsert() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        ledger.add(entry("d2", "bob", "carol", DECIDED));
        // The erasure runs completely (record + participant pass) after the projector
        // checked and before it inserted, so the participant pass finds nothing to
        // scrub.
        decisions.beforeInsert = () -> {
            if (erasedStore.rows.isEmpty()) {
                erase("bob", ERASED);
            }
        };

        backfill.rebuild(null, null);

        assertTrue(!anyRowNames("bob"), "no row keeps the identity erased in the window: " + decisions.rows.values());
        assertEquals(pseudonym("bob"), decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy());
        assertNull(decisions.rows.get(ApprovalDecision.idOf("d2", 0)).userId());
        assertEquals("alice", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).userId());
        assertEquals("carol", decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy());
    }

    @Test
    @DisplayName("a node that answered \"not erased\" before another node's erasure does not store the raw id afterwards")
    void otherNodesErasureSeenAtOnce() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        backfill.rebuild(null, null);
        assertEquals("bob", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy(), "not erased yet");

        // Another node erases bob: it records him and pseudonymises the stored rows.
        // This node's ErasedSubjects instance is not told.
        erasedStore.record("gdpr-erased:s1:token(bob)", ERASED);
        participant.erase("bob");
        decisions.rows.clear();
        backfill.rebuild(null, null);

        assertTrue(!anyRowNames("bob"), decisions.rows.values().toString());
    }

    /** The approval-decision store, with the erasure semantics of the real ones. */
    static final class InMemoryDecisions implements IApprovalDecisionStore {
        final Map<String, ApprovalDecision> rows = new LinkedHashMap<>();
        Runnable beforeInsert = () -> {
        };

        @Override
        public boolean insertIfAbsent(ApprovalDecision decision) {
            if (rows.containsKey(decision.id())) {
                return false;
            }
            beforeInsert.run();
            rows.put(decision.id(), decision);
            return true;
        }

        @Override
        public List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public long count(DecisionFilter filter) {
            return rows.size();
        }

        @Override
        public long deleteDecidedBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public List<ApprovalDecision> readByUser(String userId, int limit) {
            return rows.values().stream().filter(r -> userId.equals(r.userId()) || userId.equals(r.decidedBy())).limit(limit).toList();
        }

        @Override
        public long eraseUser(String userId, String pseudonym) {
            long changed = 0;
            for (var e : rows.entrySet()) {
                ApprovalDecision r = e.getValue();
                String user = Objects.equals(r.userId(), userId) ? null : r.userId();
                String decider = Objects.equals(r.decidedBy(), userId) ? pseudonym : r.decidedBy();
                if (!Objects.equals(user, r.userId()) || !Objects.equals(decider, r.decidedBy())) {
                    e.setValue(r.withPersonalFields(user, decider));
                    changed++;
                }
            }
            return changed;
        }
    }

    /** The erased-subject store, with the max-time upsert of the real ones. */
    static final class InMemoryErasedSubjects implements IErasedSubjectStore {
        final Map<String, Instant> rows = new LinkedHashMap<>();
        final List<String> queried = new ArrayList<>();
        boolean failReads;

        @Override
        public void record(String token, Instant erasedAt) {
            rows.merge(token, erasedAt, (a, b) -> a.isAfter(b) ? a : b);
        }

        @Override
        public Optional<Instant> erasedAt(Collection<String> tokens) {
            queried.addAll(tokens);
            if (failReads) {
                throw new IllegalStateException("store down");
            }
            return tokens.stream().map(rows::get).filter(Objects::nonNull).max(Instant::compareTo);
        }
    }
}
