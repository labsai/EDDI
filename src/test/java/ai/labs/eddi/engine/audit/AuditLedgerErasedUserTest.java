/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.cluster.KvKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        assertKeyedPseudonym(stored.get(0).userId());
        assertEquals("someone-else", stored.get(1).userId());
    }

    /**
     * The peer's gdpr-stop reply lets the erasing node sweep the stored rows: it
     * must not go out while a batch drained before the mark is still being
     * committed.
     */
    @Test
    void theClusterStopReplyWaitsForABatchThatIsBeingCommitted() throws Exception {
        var committing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            committing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(auditStore).appendBatch(anyList());
        ledger.submit(entryFor("erased-user", "queued"));
        var flusher = new Thread(ledger::flush);
        flusher.start();
        assertTrue(committing.await(10, TimeUnit.SECONDS));

        var replied = new AtomicBoolean();
        var stop = new Thread(() -> {
            ledger.stopInFlightWorkByHash(KvKeys.sha256("erased-user"));
            replied.set(true);
        });
        stop.start();
        // No timing: the stop thread is either blocked on the flush (the fix) or has
        // already finished (the regression), and state polling tells the two apart.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stop.getState() != Thread.State.BLOCKED && stop.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, stop.getState(), "the stop thread must be waiting for the batch being written");
        assertFalse(replied.get(), "the reply went out while the batch was still being written");

        release.countDown();
        stop.join(10_000);
        flusher.join(10_000);
        assertTrue(replied.get());
    }

    @Test
    void entriesQueuedBeforeTheErasureArePseudonymisedWhenTheyAreDrained() {
        ledger.submit(entryFor("erased-user", "queued"));

        ledger.markUserErased("erased-user");

        assertKeyedPseudonym(flushed().getFirst().userId());
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
        assertEquals(AuditVerificationStatus.VALID, ledger.verifyEntry(stored));
    }

    /**
     * The ledger signs, so the late entry carries the keyed pseudonym the stores
     * write into v5 rows — never the raw id, never the unkeyed hash.
     */
    private static void assertKeyedPseudonym(String userId) {
        assertTrue(userId.startsWith(AuditHmac.KEYED_PSEUDONYM_PREFIX), userId);
        assertNotEquals(AuditHmac.pseudonymFor("erased-user"), userId);
    }
}
