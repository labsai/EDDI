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

        restAuditStore.verifyConversation("conv-2", 0, 0);
        verify(auditStore).getEntries("conv-2", 0, RestAuditStore.MAX_VERIFY_LIMIT);
    }
}
