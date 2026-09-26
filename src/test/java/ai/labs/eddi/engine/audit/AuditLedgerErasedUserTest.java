/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * H9b review follow-up: a turn the erasure cancelled keeps running until its
 * LLM or HTTP call returns, then flushes its audit buffer — after the cascade
 * has pseudonymised the stored rows. Those late entries, and entries already
 * queued, must carry the pseudonym too.
 */
class AuditLedgerErasedUserTest {

    private IAuditStore auditStore;
    private AuditLedgerService ledger;

    @BeforeEach
    void setUp() {
        auditStore = mock(IAuditStore.class);
        when(auditStore.maxSequence(anyString())).thenReturn(AuditEntry.UNSEQUENCED);
        ledger = AuditLedgerService.createForTesting(auditStore, true, 60, "master-key", new SimpleMeterRegistry());
        ledger.init();
    }

    private static AuditEntry entryFor(String userId, String id) {
        return new AuditEntry(id, "conv-1", "agent-1", 1, userId, "production", 0, "taskId", "LlmTask", 0, 1L,
                Map.of("text", "hello"), Map.of("text", "response"), null, null, List.of(), 0.0, Instant.now(), null, null);
    }

    @SuppressWarnings("unchecked")
    private List<AuditEntry> flushed() {
        ledger.flush();
        ArgumentCaptor<List<AuditEntry>> batch = ArgumentCaptor.forClass(List.class);
        verify(auditStore, atLeastOnce()).appendBatch(batch.capture());
        return batch.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    void entriesSubmittedAfterTheErasureCarryThePseudonym() {
        ledger.markUserErased("erased-user");

        ledger.submit(entryFor("erased-user", "late"));
        ledger.submit(entryFor("someone-else", "other"));

        var stored = flushed();
        assertEquals(AuditHmac.pseudonymFor("erased-user"), stored.get(0).userId());
        assertEquals("someone-else", stored.get(1).userId());
    }

    @Test
    void entriesQueuedBeforeTheErasureArePseudonymisedWhenTheyAreDrained() {
        ledger.submit(entryFor("erased-user", "queued"));

        ledger.markUserErased("erased-user");

        assertEquals(AuditHmac.pseudonymFor("erased-user"), flushed().getFirst().userId());
    }

    /**
     * The rewrite is what IAuditStore.pseudonymizeByUserId does to stored rows, so
     * the v3 signature — which covers the identity token, not the raw id — must
     * still verify.
     */
    @Test
    void theSignatureStillVerifiesAfterTheRewrite() {
        ledger.markUserErased("erased-user");
        ledger.submit(entryFor("erased-user", "late"));

        var stored = flushed().getFirst();

        assertNotNull(stored.hmac());
        assertEquals(stored.hmac(), AuditHmac.computeHmac(stored, ledger.getHmacKey()));
    }
}
