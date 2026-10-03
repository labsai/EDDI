/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport.ChainStatus;
import ai.labs.eddi.engine.audit.rest.RestAuditStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GDPR Art. 17 erasure of the audit ledger: the erased user's recorded content
 * must be gone — from the stored rows <em>and</em> from entries still in the
 * write queue — while every row, its chain position and its verifiability
 * survive. Runs against an in-memory store that behaves like the real ones
 * (insert-only plus the two permitted mutations, newest-first paging).
 */
class AuditLedgerErasureRedactionTest {

    private static final String USER = "alice";
    private static final String SSN = "123-45-6789";
    private static final String CONVERSATION = "conv-1";

    private InMemoryAuditStore store;
    private AuditLedgerService ledger;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuditStore();
        // A long flush interval, so entries stay queued until a flush is forced.
        ledger = AuditLedgerService.createForTesting(store, true, 3600, "master-key-for-erasure-tests", new SimpleMeterRegistry());
        ledger.init();
    }

    private static AuditEntry turn(String userId, int step) {
        return new AuditEntry(UUID.randomUUID().toString(), CONVERSATION, "agent-1", 1, userId, "production", step, "ai.labs.llm", "llm", 0, 5L,
                Map.of("input", "my ssn is " + SSN), Map.of("output", "noted " + SSN),
                Map.of("prompt", "user said " + SSN, "response", "ok"), Map.of("tool", Map.of("args", SSN)), List.of("reply"), 0.001,
                Instant.now(), null, null);
    }

    private void submitAndStore(int turns) {
        for (int i = 0; i < turns; i++) {
            ledger.submit(turn(USER, i));
        }
        ledger.flush();
    }

    private static boolean containsSsn(AuditEntry entry) {
        return String.valueOf(entry.input()).contains(SSN) || String.valueOf(entry.output()).contains(SSN)
                || String.valueOf(entry.llmDetail()).contains(SSN) || String.valueOf(entry.toolCalls()).contains(SSN);
    }

    @Test
    @DisplayName("stored rows lose their content and raw id, keep their chain position, and still verify")
    void erasureRedactsStoredRowsAndTheChainStillVerifies() {
        submitAndStore(6);
        assertEquals(6, store.rows().stream().filter(AuditLedgerErasureRedactionTest::containsSsn).count(), "precondition: PII is stored");

        var result = ledger.eraseUser(USER);

        assertTrue(result.complete(), "every row was redacted");
        assertEquals(6, result.redacted());
        assertEquals(6, result.resealed());
        assertEquals(0, result.keptUnverified());
        assertEquals(6, result.pseudonymized());
        for (AuditEntry row : store.rows()) {
            assertFalse(containsSsn(row), "no PII may remain: " + row);
            assertNotEquals(USER, row.userId());
            assertTrue(row.userId().startsWith(AuditHmac.KEYED_PSEUDONYM_PREFIX), row.userId());
            assertTrue(AuditLedgerService.isRedacted(row));
            assertNull(row.output());
            assertNull(row.llmDetail());
            assertNull(row.toolCalls());
            assertEquals(List.of("reply"), row.actions(), "what ran is kept");
            assertEquals(AuditVerificationStatus.VALID, ledger.verifyEntry(row), "a resealed row verifies");
        }
        var report = new RestAuditStore(store, ledger).verifyConversation(CONVERSATION, 0, 1000);
        assertEquals(6, report.valid());
        assertEquals(0, report.invalid());
        assertEquals(ChainStatus.INTACT, report.chainStatus(), "no row was deleted, so the chain stays gap-free");
    }

    @Test
    @DisplayName("entries still in the write queue are flushed and redacted — the race that reported 0 entries")
    void queuedEntriesAreCaughtByTheErasure() {
        for (int i = 0; i < 4; i++) {
            ledger.submit(turn(USER, i));
        }
        assertTrue(store.rows().isEmpty(), "precondition: nothing has reached the store yet");

        var result = ledger.eraseUser(USER);

        assertEquals(4, store.rows().size());
        assertEquals(4, result.redacted(), "the queued entries were stored first, then redacted");
        assertTrue(store.rows().stream().noneMatch(AuditLedgerErasureRedactionTest::containsSsn));
        assertTrue(store.rows().stream().allMatch(row -> ledger.verifyEntry(row) == AuditVerificationStatus.VALID));
    }

    @Test
    @DisplayName("an entry queued when the store was down is redacted when it is finally drained")
    void entriesThatStayQueuedAreRedactedOnDrain() {
        ledger.submit(turn(USER, 0));
        store.failAppends = true;
        ledger.eraseUser(USER);
        store.failAppends = false;

        ledger.flush();

        assertFalse(store.rows().isEmpty(), "the entry was retried and stored");
        for (AuditEntry row : store.rows()) {
            assertFalse(containsSsn(row), "drained after the erasure, so it must land redacted: " + row);
            assertEquals(AuditVerificationStatus.VALID, ledger.verifyEntry(row));
        }
    }

    @Test
    @DisplayName("entries submitted after the erasure (cancelled work unwinding) land redacted and verify")
    void lateEntriesLandRedacted() {
        ledger.eraseUser(USER);

        ledger.submit(turn(USER, 0));
        ledger.flush();

        AuditEntry row = store.rows().getFirst();
        assertFalse(containsSsn(row));
        assertTrue(AuditLedgerService.isRedacted(row));
        assertEquals(AuditVerificationStatus.VALID, ledger.verifyEntry(row));
    }

    @Test
    @DisplayName("a row that failed verification is redacted but never re-signed into a valid one")
    void tamperedRowIsRedactedButStaysInvalid() {
        submitAndStore(2);
        AuditEntry victim = store.rows().getFirst();
        store.replace(victim.withPayload(victim.input(), Map.of("output", "forged " + SSN), victim.llmDetail(), victim.toolCalls()));
        assertEquals(AuditVerificationStatus.INVALID, ledger.verifyEntry(store.byId(victim.id())), "precondition: tampered");

        var result = ledger.eraseUser(USER);

        assertEquals(2, result.redacted());
        assertEquals(1, result.keptUnverified());
        AuditEntry redacted = store.byId(victim.id());
        assertFalse(containsSsn(redacted));
        assertEquals(AuditVerificationStatus.INVALID, ledger.verifyEntry(redacted), "redaction must not launder a tampered row");
        @SuppressWarnings("unchecked")
        var marker = (Map<String, Object>) redacted.input().get(AuditLedgerService.REDACTION_MARKER_KEY);
        assertEquals("INVALID", marker.get("integrityBeforeRedaction"));
    }

    @Test
    @DisplayName("a store that cannot redact makes the erasure incomplete")
    void failedRedactionIsReportedIncomplete() {
        submitAndStore(3);
        store.refuseRedaction = true;

        var result = ledger.eraseUser(USER);

        assertFalse(result.complete());
        assertEquals(3, result.failed());
        assertEquals(0, result.redacted());
    }

    @Test
    @DisplayName("a row the sweep stepped over is caught by the final check — complete is never reported on trust")
    void rowLeftUnredactedIsReportedIncomplete() {
        submitAndStore(3);
        store.ignoreRedactionButAcknowledge = true;

        var result = ledger.eraseUser(USER);

        assertFalse(result.complete());
        assertEquals(1, result.failed());
    }

    @Test
    @DisplayName("pseudonymize mode keeps the content — the operator's explicit legal-hold choice")
    void pseudonymizeModeKeepsContent() {
        submitAndStore(2);
        ledger.setErasureMode(AuditLedgerService.ERASURE_MODE_PSEUDONYMIZE);

        var result = ledger.eraseUser(USER);

        assertFalse(result.contentRedaction());
        assertEquals(0, result.redacted());
        assertTrue(store.rows().stream().allMatch(AuditLedgerErasureRedactionTest::containsSsn));
    }

    @Test
    @DisplayName("rows an older release only pseudonymised are redacted when the erasure is re-run")
    void rowsPseudonymisedByAnOlderReleaseAreRedacted() {
        submitAndStore(2);
        store.pseudonymizeByUserId(USER, AuditHmac.pseudonymFor(USER));
        assertTrue(store.rows().stream().allMatch(AuditLedgerErasureRedactionTest::containsSsn), "precondition: content kept");

        var result = ledger.eraseUser(USER);

        assertEquals(2, result.redacted());
        assertTrue(store.rows().stream().noneMatch(AuditLedgerErasureRedactionTest::containsSsn));
    }

    @Test
    @DisplayName("GDPR compliance and admin-action records are kept as they are")
    void complianceRecordsAreNotRedacted() {
        var compliance = new AuditEntry(UUID.randomUUID().toString(), null, null, null, USER, null, 0, AuditLedgerService.COMPLIANCE_TASK_ID,
                "compliance", 0, 0, Map.of("eventType", "GDPR_EXPORT"), Map.of("conversations", 3), null, null, List.of("GDPR_EXPORT"), 0.0,
                Instant.now(), null, null);
        ledger.submit(compliance);
        ledger.flush();

        var result = ledger.eraseUser(USER);

        assertEquals(0, result.redacted());
        assertEquals(Map.of("eventType", "GDPR_EXPORT"), store.rows().getFirst().input());
    }

    @Test
    @DisplayName("more rows than one page are all redacted")
    void pagesThroughLargeLedgers() {
        int turns = AuditLedgerService.REDACTION_PAGE_SIZE * 2 + 7;
        submitAndStore(turns);

        var result = ledger.eraseUser(USER);

        assertEquals(turns, result.redacted());
        assertTrue(store.rows().stream().noneMatch(AuditLedgerErasureRedactionTest::containsSsn));
    }

    @Nested
    @DisplayName("erasure-mode configuration")
    class ErasureMode {
        @Test
        void unknownValueFallsBackToRedact() {
            ledger.setErasureMode("keep-everything");
            assertTrue(ledger.isRedactingContentOnErasure());
        }

        @Test
        void pseudonymizeIsCaseInsensitive() {
            ledger.setErasureMode(" PSEUDONYMIZE ");
            assertFalse(ledger.isRedactingContentOnErasure());
        }
    }

    /**
     * Insert-only store with the two GDPR mutations, newest-first paging like the
     * real backends.
     */
    static final class InMemoryAuditStore implements IAuditStore {
        private final Map<String, AuditEntry> rows = new LinkedHashMap<>();
        volatile boolean failAppends;
        volatile boolean refuseRedaction;
        volatile boolean ignoreRedactionButAcknowledge;

        synchronized List<AuditEntry> rows() {
            return new ArrayList<>(rows.values());
        }

        synchronized AuditEntry byId(String id) {
            return rows.get(id);
        }

        synchronized void replace(AuditEntry entry) {
            rows.put(entry.id(), entry);
        }

        @Override
        public synchronized void appendEntry(AuditEntry entry) {
            if (failAppends) {
                throw new IllegalStateException("store down");
            }
            rows.putIfAbsent(entry.id(), entry);
        }

        @Override
        public synchronized void appendBatch(List<AuditEntry> entries) {
            if (failAppends) {
                throw new IllegalStateException("store down");
            }
            entries.forEach(e -> rows.putIfAbsent(e.id(), e));
        }

        private synchronized List<AuditEntry> page(Predicate<AuditEntry> filter, int skip, int limit) {
            return rows.values().stream().filter(filter)
                    .sorted(Comparator.comparing(AuditEntry::timestamp).reversed().thenComparing(AuditEntry::id))
                    .skip(skip).limit(limit).toList();
        }

        @Override
        public List<AuditEntry> getEntries(String conversationId, int skip, int limit) {
            return page(e -> Objects.equals(conversationId, e.conversationId()), skip, limit);
        }

        @Override
        public List<AuditEntry> getEntriesByAgent(String agentId, Integer agentVersion, int skip, int limit) {
            return page(e -> Objects.equals(agentId, e.agentId()), skip, limit);
        }

        @Override
        public synchronized long countByConversation(String conversationId) {
            return rows.values().stream().filter(e -> Objects.equals(conversationId, e.conversationId())).count();
        }

        @Override
        public List<AuditEntry> getEntriesByUserId(String userId, int skip, int limit) {
            return page(e -> Objects.equals(userId, e.userId()), skip, limit);
        }

        @Override
        public synchronized long pseudonymizeByUserId(String userId, String pseudonym) {
            long changed = 0;
            for (var row : new ArrayList<>(rows.values())) {
                if (userId.equals(row.userId())) {
                    rows.put(row.id(), row.withUserId(pseudonym));
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public synchronized boolean redactEntry(AuditEntry redacted, String expectedHmac) {
            if (refuseRedaction) {
                throw new IllegalStateException("redaction refused");
            }
            if (ignoreRedactionButAcknowledge) {
                return true; // a store that says yes and writes nothing
            }
            AuditEntry current = rows.get(redacted.id());
            if (current == null || !Objects.equals(current.hmac(), expectedHmac)) {
                return false;
            }
            rows.put(redacted.id(), current.withUserId(redacted.userId())
                    .withPayload(redacted.input(), redacted.output(), redacted.llmDetail(), redacted.toolCalls()).withHmac(redacted.hmac())
                    .withAgentSignature(redacted.agentSignature()));
            return true;
        }

        @Override
        public boolean supportsSequence() {
            return true;
        }

        @Override
        public synchronized long maxSequence(String conversationId) {
            return rows.values().stream().filter(e -> Objects.equals(conversationId, e.conversationId())).mapToLong(AuditEntry::sequence).max()
                    .orElse(AuditEntry.UNSEQUENCED);
        }
    }
}
