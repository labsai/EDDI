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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private ApprovalDecisionProjector projector;
    private AutonomyService autonomyService;

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
        autonomyService = mock(AutonomyService.class);
        when(autonomy.isResolvable()).thenReturn(true);
        when(autonomy.get()).thenReturn(autonomyService);
        projector = new ApprovalDecisionProjector(decisions, metrics, autonomy, erasedSubjects);

        IAuditStore auditStore = mock(IAuditStore.class);
        when(auditStore.getEntriesByTask(eq(HitlApprovalAudit.TASK_ID), isNull(), anyInt(), anyInt())).thenAnswer(inv -> {
            int skip = inv.getArgument(2);
            int limit = inv.getArgument(3);
            return ledger.subList(Math.min(skip, ledger.size()), Math.min(skip + limit, ledger.size()));
        });
        when(auditStore.getEntries(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            String conversationId = inv.getArgument(0);
            List<AuditEntry> mine = ledger.stream().filter(e -> conversationId.equals(e.conversationId())).toList();
            int skip = inv.getArgument(1);
            int limit = inv.getArgument(2);
            return mine.subList(Math.min(skip, mine.size()), Math.min(skip + limit, mine.size()));
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

    /**
     * A live projection of one entry, without the rebuild's closing reconciliation.
     */
    private void projectLive(AuditEntry entry) {
        projector.project(HitlApprovalAudit.parse(entry), false);
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
        decisions.clear();
        var result = backfill.rebuild(null, null);

        assertEquals(3, result.unitsStored(), "every decision is counted again");
        assertTrue(!anyRowNames("bob"), "no rebuilt row names the erased decider: " + decisions.rows.values());
        assertEquals(2, decisions.rows.values().stream().filter(r -> pseudonym("bob").equals(r.decidedBy())).count(),
                "the erased decider is the same pseudonym erasure writes, so per-decider counts stay correct");
        assertEquals("carol", decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy(), "another decider is untouched");
        assertEquals("alice", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).userId(), "a conversation owner who was not erased is kept");
        assertEquals("dave", decisions.rows.get(ApprovalDecision.idOf("d3", 0)).userId());
        assertTrue(decisions.readByUser("bob", 100).isEmpty(), "an export of the erased user finds nothing");
        assertTrue(decisions.pending.isEmpty(), "every row was settled");
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
    @DisplayName("when the record cannot be read the row rests masked and hidden, and reconciliation settles it later")
    void unreadableRecordRestsMasked() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        erasedStore.failReads = true;

        backfill.rebuild(null, null);

        ApprovalDecision stored = decisions.rows.get(ApprovalDecision.idOf("d1", 0));
        assertNull(stored.userId(), "what rests in the store is the erased form");
        assertEquals(pseudonym("bob"), stored.decidedBy());
        assertTrue(decisions.query(null, 0, 10).isEmpty(), "and no read sees it");
        verify(metrics).projectionFailed();

        erasedStore.failReads = false;
        assertEquals(1, backfill.reconcilePending());
        ApprovalDecision settled = decisions.rows.get(ApprovalDecision.idOf("d1", 0));
        assertEquals("alice", settled.userId(), "neither was erased: the identities are restored from the ledger");
        assertEquals("bob", settled.decidedBy());
        assertEquals(List.of(settled), decisions.query(null, 0, 10));
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
        assertEquals("system:timeout", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy());
    }

    @Test
    @DisplayName("an erasure landing between the insert and the settlement: the row stays erased, and a later decision of the "
            + "returning user keeps its id")
    void erasureBetweenInsertAndSettlement() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        ledger.add(entry("d2", "bob", "carol", DECIDED));
        ApprovalDecision afterErasure = ApprovalDecision.of(HitlApprovalAudit.parse(entry("d3", "alice", "bob", ERASED.plusSeconds(60)))
                .getFirst(), 0);
        decisions.afterInsert = d -> {
            if (d.id().startsWith("d1#") && erasedStore.rows.isEmpty()) {
                erase("bob", ERASED);
                decisions.rows.put(afterErasure.id(), afterErasure);
            }
        };

        backfill.rebuild(null, null);

        assertEquals(pseudonym("bob"), decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy());
        assertEquals("alice", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).userId());
        assertNull(decisions.rows.get(ApprovalDecision.idOf("d2", 0)).userId());
        assertEquals("carol", decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy());
        assertEquals("bob", decisions.rows.get(afterErasure.id()).decidedBy(), "only the settled row is written");
        assertTrue(decisions.pending.isEmpty());
    }

    @Test
    @DisplayName("an erasure recorded between the write-back and the confirmation: the confirming check reverts the row")
    void erasureBetweenWriteBackAndConfirmationReverts() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        decisions.afterWriteBack = d -> erasedSubjects.record("bob", ERASED);

        backfill.rebuild(null, null);

        ApprovalDecision row = decisions.rows.get(ApprovalDecision.idOf("d1", 0));
        assertEquals(pseudonym("bob"), row.decidedBy(), "reverted by the second check");
        assertEquals("alice", row.userId());
        assertTrue(decisions.pending.isEmpty());
    }

    @Test
    @DisplayName("an erasure whose own pass runs between the write-back and the confirmation: the confirmation loses its "
            + "compare-and-set and the erased form stays")
    void erasurePassBetweenWriteBackAndConfirmation() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        decisions.afterWriteBack = d -> erase("bob", ERASED);

        projectLive(ledger.getFirst());

        assertTrue(!anyRowNames("bob"), decisions.rows.values().toString());
        assertEquals(Set.of(ApprovalDecision.idOf("d1", 0)), decisions.pending, "the lost compare-and-set leaves it pending");
        decisions.afterWriteBack = d -> {
        };
        assertEquals(1, backfill.reconcilePending(), "settled in its erased form");
        assertTrue(!anyRowNames("bob"));
        assertTrue(decisions.pending.isEmpty());
    }

    @Test
    @DisplayName("interrupted right after the insert: the row rests masked and hidden; reconciliation upgrades a person who "
            + "was not erased and keeps the erased one masked")
    void interruptedAfterInsert() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        ledger.add(entry("d2", "alice", "carol", DECIDED));
        ledger.add(entry("d3", "alice", "bob", ERASED.plusSeconds(60)));
        decisions.afterInsert = d -> {
            throw new ProcessDied();
        };
        for (AuditEntry e : ledger) {
            try {
                projectLive(e);
            } catch (ProcessDied expected) {
                // the process stops right after its insert
            }
        }
        decisions.afterInsert = d -> {
        };
        assertEquals(3, decisions.pending.size());
        assertTrue(!anyRowNames("bob") && !anyRowNames("carol") && !anyRowNames("alice"), "only masked rows rest in the store");
        assertTrue(decisions.query(null, 0, 10).isEmpty(), "reads before reconciliation see nothing");
        assertTrue(decisions.readByUser("alice", 10).isEmpty());

        erase("bob", ERASED);
        assertEquals(3, backfill.reconcilePending());

        assertEquals(pseudonym("bob"), decisions.rows.get(ApprovalDecision.idOf("d1", 0)).decidedBy(), "erased: kept masked");
        assertEquals("alice", decisions.rows.get(ApprovalDecision.idOf("d1", 0)).userId(), "the owner was not erased: restored");
        assertEquals("carol", decisions.rows.get(ApprovalDecision.idOf("d2", 0)).decidedBy(), "not erased: upgraded");
        assertEquals("bob", decisions.rows.get(ApprovalDecision.idOf("d3", 0)).decidedBy(), "decided after the erasure");
        assertTrue(decisions.pending.isEmpty());
        assertEquals(3, decisions.query(null, 0, 10).size());
        assertEquals(0, backfill.reconcilePending(), "idempotent");
    }

    @Test
    @DisplayName("a failed confirmation leaves the row pending and hidden, and reconciliation settles it; erased meanwhile, "
            + "it ends masked")
    void failedConfirmationReconciled() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        decisions.failConfirmations = 1;

        projectLive(ledger.getFirst());
        assertEquals(Set.of(ApprovalDecision.idOf("d1", 0)), decisions.pending);
        assertTrue(decisions.query(null, 0, 10).isEmpty(), "the unconfirmed identity is never read");
        assertTrue(decisions.readByUser("bob", 10).isEmpty());
        verify(metrics).projectionFailed();

        erase("bob", ERASED);
        assertEquals(1, backfill.reconcilePending());

        assertTrue(!anyRowNames("bob"), decisions.rows.values().toString());
        assertTrue(decisions.pending.isEmpty());
    }

    @Test
    @DisplayName("a pending row whose ledger entry is gone settles in its masked form")
    void pendingWithoutLedgerEntrySettlesMasked() {
        ApprovalDecision raw = ApprovalDecision.of(HitlApprovalAudit.parse(entry("d9", "alice", "bob", DECIDED)).getFirst(), 0);
        decisions.insertPendingIfAbsent(raw.withPersonalFields(null, pseudonym("bob")));

        assertEquals(1, backfill.reconcilePending());

        assertEquals(pseudonym("bob"), decisions.rows.get(raw.id()).decidedBy());
        assertTrue(decisions.pending.isEmpty());
    }

    @Test
    @DisplayName("a pending row whose ledger lookup stops at its page bound stays pending, instead of settling masked")
    void truncatedLedgerLookupStaysPending() {
        ApprovalDecision raw = ApprovalDecision.of(HitlApprovalAudit.parse(entry("d9", "alice", "bob", DECIDED)).getFirst(), 0);
        decisions.insertPendingIfAbsent(raw.withPersonalFields(null, pseudonym("bob")));
        // The conversation holds more entries than one lookup reads, none of them the
        // row's (its entry would come after the bound).
        Map<String, Object> noise = Map.of("note", "x");
        for (int i = 0; i < ApprovalBackfillService.LEDGER_LOOKUP_PAGES * ApprovalBackfillService.PAGE_SIZE; i++) {
            ledger.add(new AuditEntry("n" + i, raw.conversationId(), "agent-1", 2, "alice", "production", -1, "other.task", "x", -1, 0L,
                    Map.of(), noise, null, null, List.of(), 0.0, DECIDED, null, null));
        }

        assertEquals(0, backfill.reconcilePending());

        assertEquals(Set.of(raw.id()), decisions.pending, "unknown is not \"not found\"");
    }

    @Test
    @DisplayName("a rejection settled by reconciliation reaches earned autonomy, so its demote code still resets the evidence")
    void reconciledRowsReachEarnedAutonomy() {
        ledger.add(entry("d1", "alice", "bob", DECIDED));
        decisions.failConfirmations = 1;
        projectLive(ledger.getFirst());
        verify(autonomyService, never()).onDecisions(anyList());

        assertEquals(1, backfill.reconcilePending());

        verify(autonomyService).onDecisions(List.of(decisions.rows.get(ApprovalDecision.idOf("d1", 0))));
    }

    @Test
    @DisplayName("a stale settler cannot restore an identity after an erasure masked the row back to the values it expects")
    void staleSettlerAfterErasureToTheSameValues() {
        // Owner and decider are the same person, so erasure returns the row to its
        // masked pair exactly.
        ledger.add(entry("d1", "bob", "bob", DECIDED));
        ApprovalDecision raw = ApprovalDecision.of(HitlApprovalAudit.parse(ledger.getFirst()).getFirst(), 0);
        ApprovalDecision masked = raw.withPersonalFields(null, pseudonym("bob"));
        decisions.insertPendingIfAbsent(masked);
        assertTrue(decisions.updateIdentities(masked.id(), 0, "bob", "bob", true), "settler A writes bob back");
        erase("bob", ERASED);

        assertTrue(!decisions.updateIdentities(masked.id(), 0, "bob", "bob", true), "settler B, stale");
        assertTrue(!decisions.updateIdentities(masked.id(), 1, "bob", "bob", false), "settler A's confirmation, stale");
        assertEquals(1, backfill.reconcilePending());

        assertTrue(!anyRowNames("bob"), decisions.rows.values().toString());
        assertTrue(decisions.pending.isEmpty());
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
        decisions.clear();
        backfill.rebuild(null, null);

        assertTrue(!anyRowNames("bob"), decisions.rows.values().toString());
    }

    /** Thrown by a hook to stop a projection the way a dying process would. */
    private static final class ProcessDied extends Error {
    }

    /**
     * The approval-decision store, with the semantics of the real ones: pending
     * rows hidden from reads, compare-and-set identity updates, erasure over every
     * row.
     */
    static final class InMemoryDecisions implements IApprovalDecisionStore {
        final Map<String, ApprovalDecision> rows = new LinkedHashMap<>();
        final Set<String> pending = new LinkedHashSet<>();
        final Map<String, Long> versions = new LinkedHashMap<>();
        Consumer<ApprovalDecision> afterInsert = d -> {
        };
        Consumer<ApprovalDecision> afterWriteBack = d -> {
        };
        int failConfirmations;

        void clear() {
            rows.clear();
            pending.clear();
            versions.clear();
        }

        @Override
        public boolean insertIfAbsent(ApprovalDecision decision) {
            if (rows.containsKey(decision.id())) {
                return false;
            }
            rows.put(decision.id(), decision);
            versions.put(decision.id(), INITIAL_VERSION);
            afterInsert.accept(decision);
            return true;
        }

        @Override
        public boolean insertPendingIfAbsent(ApprovalDecision decision) {
            if (rows.containsKey(decision.id())) {
                return false;
            }
            rows.put(decision.id(), decision);
            versions.put(decision.id(), INITIAL_VERSION);
            pending.add(decision.id());
            afterInsert.accept(decision);
            return true;
        }

        @Override
        public boolean updateIdentities(String id, long expectedVersion, String userId, String decidedBy, boolean stillPending) {
            if (!stillPending && failConfirmations > 0) {
                failConfirmations--;
                throw new IllegalStateException("store down");
            }
            ApprovalDecision r = rows.get(id);
            if (r == null || versions.getOrDefault(id, INITIAL_VERSION) != expectedVersion) {
                return false;
            }
            ApprovalDecision next = r.withPersonalFields(userId, decidedBy);
            rows.put(id, next);
            versions.put(id, expectedVersion + 1);
            if (stillPending) {
                pending.add(id);
                afterWriteBack.accept(next);
            } else {
                pending.remove(id);
            }
            return true;
        }

        @Override
        public List<Pending> pendingCheck(int limit) {
            return pending.stream().map(id -> new Pending(rows.get(id), versions.getOrDefault(id, INITIAL_VERSION))).limit(limit).toList();
        }

        private List<ApprovalDecision> settled() {
            return rows.values().stream().filter(r -> !pending.contains(r.id())).toList();
        }

        @Override
        public List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit) {
            return settled();
        }

        @Override
        public long count(DecisionFilter filter) {
            return settled().size();
        }

        @Override
        public long deleteDecidedBefore(Instant cutoff) {
            return 0;
        }

        @Override
        public List<ApprovalDecision> readByUser(String userId, int limit) {
            return settled().stream().filter(r -> userId.equals(r.userId()) || userId.equals(r.decidedBy())).limit(limit).toList();
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
                    versions.merge(e.getKey(), 1L, Long::sum);
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
