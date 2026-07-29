/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.AuditVerificationStatus;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport.ChainStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAuditStore} — verifies correct delegation to
 * {@link IAuditStore}.
 */
class RestAuditStoreTest {

    private IAuditStore auditStore;
    private AuditLedgerService auditLedgerService;
    private RestAuditStore restAuditStore;

    private AuditEntry sampleEntry() {
        return new AuditEntry("id-1", "conv-1", "agent-1", 1, "user-1", "production", 0, "task-1", "test-type", 0, 42L, Map.of("userInput", "hello"),
                Map.of("output", List.of("world")), null, null, List.of("greet"), 0.0, Instant.now(), "hmac-abc", null);
    }

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class);
        auditLedgerService = mock(AuditLedgerService.class);
        when(auditLedgerService.isSigningEnabled()).thenReturn(true);
        when(auditLedgerService.verifyEntry(any())).thenReturn(AuditVerificationStatus.VALID);
        restAuditStore = new RestAuditStore(auditStore, auditLedgerService);
    }

    @Test
    @DisplayName("getAuditTrail delegates to auditStore.getEntries")
    void getAuditTrail_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntries("conv-1", 5, 50)).thenReturn(expected);

        var result = restAuditStore.getAuditTrail("conv-1", 5, 50);

        assertEquals(expected, result);
        verify(auditStore).getEntries("conv-1", 5, 50);
    }

    @Test
    @DisplayName("getAuditTrailByAgent delegates to auditStore.getEntriesByAgent")
    void getAuditTrailByAgent_delegatesToStore() {
        var expected = List.of(sampleEntry());
        when(auditStore.getEntriesByAgent("agent-1", 1, 0, 100)).thenReturn(expected);

        var result = restAuditStore.getAuditTrailByAgent("agent-1", 1, 0, 100);

        assertEquals(expected, result);
        verify(auditStore).getEntriesByAgent("agent-1", 1, 0, 100);
    }

    @Test
    @DisplayName("getAuditTrailByAgent with null version delegates correctly")
    void getAuditTrailByAgent_nullVersion_delegatesToStore() {
        when(auditStore.getEntriesByAgent("agent-1", null, 0, 100)).thenReturn(List.of());

        var result = restAuditStore.getAuditTrailByAgent("agent-1", null, 0, 100);

        assertEquals(0, result.size());
        verify(auditStore).getEntriesByAgent("agent-1", null, 0, 100);
    }

    @Test
    @DisplayName("getEntryCount delegates to auditStore.countByConversation")
    void getEntryCount_delegatesToStore() {
        when(auditStore.countByConversation("conv-1")).thenReturn(42L);

        long count = restAuditStore.getEntryCount("conv-1");

        assertEquals(42L, count);
        verify(auditStore).countByConversation("conv-1");
    }

    // ==================== G16/G18: integrity sweep ====================

    private static AuditEntry entryAt(String id, long sequence) {
        return new AuditEntry(id, "conv-1", "agent-1", 1, "user-1", "production", 0, "task-1", "test-type", 0, 42L, null, null, null, null,
                List.of("greet"), 0.0, Instant.now(), "v3:deadbeef", null, sequence);
    }

    /**
     * The point of the endpoint: {@code AuditHmac.verifyHmac} previously had no
     * production caller at all, so a tampered row shipped undetected. A row whose
     * HMAC no longer recomputes must be named in the report.
     */
    @Test
    @DisplayName("a tampered row is reported")
    void tamperedRowIsReported() {
        var good = entryAt("id-1", 0);
        var tampered = entryAt("id-2", 1);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(good, tampered));
        when(auditLedgerService.verifyEntry(good)).thenReturn(AuditVerificationStatus.VALID);
        when(auditLedgerService.verifyEntry(tampered)).thenReturn(AuditVerificationStatus.INVALID);

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(2, report.entriesChecked());
        assertEquals(1, report.valid());
        assertEquals(1, report.invalid());
        assertFalse(report.intact());
        assertEquals(1, report.problems().size());
        assertEquals("id-2", report.problems().getFirst().entryId());
        assertEquals(AuditVerificationStatus.INVALID, report.problems().getFirst().status());
    }

    @Test
    @DisplayName("an untouched trail reports intact")
    void untouchedTrailIsIntact() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-2", 1), entryAt("id-3", 2)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.intact());
        assertTrue(report.missingSequences().isEmpty());
    }

    /**
     * A per-entry HMAC cannot see a DELETED entry — nothing is left to fail
     * verification. The signed per-conversation sequence is what makes the hole
     * visible.
     */
    @Test
    @DisplayName("deleting a middle entry is detected")
    void deletedMiddleEntryIsDetected() {
        // entry with sequence 1 has been removed from the ledger
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(0, report.invalid(), "every surviving row still verifies — that is exactly the problem");
        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(1L), report.missingSequences());
        assertFalse(report.intact());
    }

    @Test
    @DisplayName("unsequenced rows report the chain as unavailable, not broken")
    void unsequencedRowsReportUnavailable() {
        when(auditStore.getEntries("conv-1", 0, 1000))
                .thenReturn(List.of(entryAt("id-1", AuditEntry.UNSEQUENCED), entryAt("id-2", AuditEntry.UNSEQUENCED)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.UNAVAILABLE, report.chainStatus());
        assertFalse(report.intact());
    }

    @Test
    @DisplayName("without a signing key the sweep proves nothing")
    void withoutSigningKeyNothingIsProven() {
        when(auditLedgerService.isSigningEnabled()).thenReturn(false);
        when(auditLedgerService.verifyEntry(any())).thenReturn(AuditVerificationStatus.SIGNING_DISABLED);
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertFalse(report.signingEnabled());
        assertEquals(0, report.valid());
        assertFalse(report.intact());
        assertEquals(AuditVerificationStatus.SIGNING_DISABLED, report.problems().getFirst().status());
    }

    @Test
    @DisplayName("an agent sweep checks HMACs but not the chain")
    void agentSweepSkipsChainCheck() {
        when(auditStore.getEntriesByAgent("agent-1", 1, 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));

        var report = restAuditStore.verifyAgent("agent-1", 1, 0, 1000);

        assertEquals(ChainStatus.NOT_APPLICABLE, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty(), "sequences interleave across conversations — a gap here means nothing");
    }

    @Test
    @DisplayName("the verification limit is clamped")
    void verificationLimitIsClamped() {
        restAuditStore.verifyConversation("conv-1", 0, Integer.MAX_VALUE);
        verify(auditStore).getEntries("conv-1", 0, RestAuditStore.MAX_VERIFY_LIMIT);
    }

    /**
     * {@code ?limit=0} used to mean "give me the hard maximum" — ten times the
     * documented default, from a caller who asked for nothing. A non-positive limit
     * falls back to the declared {@code @DefaultValue}.
     */
    @Test
    @DisplayName("a non-positive limit falls back to the documented default, not the hard ceiling")
    void nonPositiveLimitFallsBackToTheDefault() {
        restAuditStore.verifyConversation("conv-2", 0, 0);
        verify(auditStore).getEntries("conv-2", 0, RestAuditStore.DEFAULT_VERIFY_LIMIT);

        restAuditStore.verifyConversation("conv-3", 0, -5);
        verify(auditStore).getEntries("conv-3", 0, RestAuditStore.DEFAULT_VERIFY_LIMIT);

        assertTrue(RestAuditStore.DEFAULT_VERIFY_LIMIT < RestAuditStore.MAX_VERIFY_LIMIT);
    }

    /**
     * Deriving the expected run from the surviving rows made head deletion free:
     * dropping sequence 0 from {@code [0,1,2]} left {@code [1,2]}, whose own range
     * is gap-free. When the swept page provably starts at the beginning of the
     * conversation, the run is anchored at 0 instead.
     */
    @Test
    @DisplayName("deleting the first entry of a conversation is detected")
    void deletedHeadEntryIsDetected() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-2", 1), entryAt("id-3", 2)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(0L), report.missingSequences());
        assertTrue(report.tamperingSuspected());
    }

    /**
     * The head anchor may only be applied when the page provably covers the start
     * of the conversation. A paginated sweep (non-zero skip) starts wherever the
     * store's newest-first window lands, so anchoring at 0 there would report the
     * whole preceding history as deleted.
     */
    @Test
    @DisplayName("a paginated sweep does not anchor the run at zero")
    void paginatedSweepDoesNotAnchorAtZero() {
        when(auditStore.getEntries("conv-1", 10, 1000)).thenReturn(List.of(entryAt("id-11", 10), entryAt("id-12", 11)));

        var report = restAuditStore.verifyConversation("conv-1", 10, 1000);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty());
    }

    @Test
    @DisplayName("a full page does not anchor the run at zero either")
    void fullPageDoesNotAnchorAtZero() {
        when(auditStore.getEntries("conv-1", 0, 2)).thenReturn(List.of(entryAt("id-8", 8), entryAt("id-9", 9)));

        var report = restAuditStore.verifyConversation("conv-1", 0, 2);

        assertEquals(ChainStatus.INTACT, report.chainStatus());
        assertTrue(report.missingSequences().isEmpty());
    }

    /**
     * The G18/G20 collision: the ledger's own back-pressure drops consume chain
     * positions, and without attribution the resulting hole reads exactly like a
     * deleted row. A gap the ledger admits to is {@code INCOMPLETE}, not
     * {@code BROKEN} — the report must not accuse the deployment of tampering for
     * entries the deployment never got to write.
     */
    @Test
    @DisplayName("a gap the ledger itself caused is reported as INCOMPLETE, not BROKEN")
    void selfInflictedGapIsNotReportedAsTampering() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-3", 2)));
        when(auditLedgerService.undeliveredSequences("conv-1")).thenReturn(Set.of(1L));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.INCOMPLETE, report.chainStatus());
        assertEquals(List.of(1L), report.undeliveredSequences());
        assertTrue(report.missingSequences().isEmpty(), "an entry the ledger dropped is not a deleted entry");
        assertFalse(report.tamperingSuspected());
        assertFalse(report.intact(), "the record is still incomplete");
    }

    @Test
    @DisplayName("an unattributed gap alongside an attributed one still reports BROKEN")
    void unattributedGapStillBreaksTheChain() {
        when(auditStore.getEntries("conv-1", 0, 1000)).thenReturn(List.of(entryAt("id-1", 0), entryAt("id-4", 3)));
        when(auditLedgerService.undeliveredSequences("conv-1")).thenReturn(Set.of(1L));

        var report = restAuditStore.verifyConversation("conv-1", 0, 1000);

        assertEquals(ChainStatus.BROKEN, report.chainStatus());
        assertEquals(List.of(2L), report.missingSequences());
        assertEquals(List.of(1L), report.undeliveredSequences());
        assertTrue(report.tamperingSuspected());
    }
}
