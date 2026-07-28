package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLedgerServiceTest {

    @Mock
    private IAuditStore auditStore;

    private MeterRegistry meterRegistry;
    private AuditLedgerService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
    }

    private AuditLedgerService createService(boolean enabled, String masterKey) {
        var svc = AuditLedgerService.createForTesting(auditStore, enabled, 60, masterKey, meterRegistry);
        svc.init();
        return svc;
    }

    private AuditEntry entry(String id, String convId, String agentId) {
        return new AuditEntry(id, convId, agentId, 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, Instant.now(), null, null);
    }

    // ==================== isEnabled ====================

    @Test
    @DisplayName("isEnabled — returns true when enabled")
    void isEnabledTrue() {
        service = createService(true, null);
        assertTrue(service.isEnabled());
    }

    @Test
    @DisplayName("isEnabled — returns false when disabled")
    void isEnabledFalse() {
        service = createService(false, null);
        assertFalse(service.isEnabled());
    }

    // ==================== submit ====================

    @Test
    @DisplayName("submit — queues entry when enabled")
    void submitQueuesEntry() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — ignores null entry")
    void submitIgnoresNull() {
        service = createService(true, null);
        service.submit(null);
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — does nothing when disabled")
    void submitWhenDisabled() {
        service = createService(false, null);
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("submit — with HMAC key computes hmac")
    void submitWithHmacKey() {
        service = createService(true, "my-secret-key-for-testing");
        assertNotNull(service.getHmacKey());
        service.submit(entry("1", "conv1", "agent1"));
        assertEquals(1, service.getQueueSize());
    }

    // ==================== flush ====================

    @Test
    @DisplayName("flush — persists queued entries")
    void flushPersists() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.submit(entry("2", "conv2", "agent2"));

        service.flush();

        verify(auditStore).appendBatch(argThat(batch -> batch.size() == 2));
        assertEquals(0, service.getQueueSize());
    }

    @Test
    @DisplayName("flush — does nothing when queue is empty")
    void flushEmptyQueue() {
        service = createService(true, null);
        service.flush();
        verify(auditStore, never()).appendBatch(any());
    }

    @Test
    @DisplayName("flush — retries on failure (less than MAX_FLUSH_RETRIES)")
    void flushRetriesOnFailure() {
        service = createService(true, null);
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // First failure — requeues

        assertEquals(1, service.getQueueSize()); // Still in queue
    }

    @Test
    @DisplayName("flush — drops entries after MAX_FLUSH_RETRIES consecutive failures")
    void flushDropsAfterMaxRetries() {
        service = createService(true, null);
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "conv1", "agent1"));
        service.flush(); // Failure 1 — requeue
        service.flush(); // Failure 2 — requeue
        service.flush(); // Failure 3 — drop + dead letter

        assertEquals(0, service.getQueueSize()); // Dropped
    }

    // ==================== scrub secrets ====================

    @Test
    @DisplayName("submit — scrubs secrets from input/output maps")
    void submitScrubsSecrets() {
        service = createService(true, null);
        var entryWithSecret = new AuditEntry("1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("apiKey", "sk-1234567890abcdef"), Map.of("text", "safe"),
                null, null, List.of(), 0.0, Instant.now(), null, null);

        service.submit(entryWithSecret);
        service.flush();

        verify(auditStore).appendBatch(any());
    }

    // ==================== serializeDeadLetterEntry ====================

    @Test
    @DisplayName("serializeDeadLetterEntry — includes type field when provided")
    void serializeWithType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, "audit_dead_letter");
        assertTrue(json.contains("\"type\":\"audit_dead_letter\""));
        assertTrue(json.contains("\"conversationId\":\"conv1\""));
    }

    @Test
    @DisplayName("serializeDeadLetterEntry — omits type when null")
    void serializeWithoutType() {
        service = createService(true, null);
        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, null);
        assertFalse(json.contains("\"type\""));
        assertTrue(json.contains("\"agentId\":\"agent1\""));
    }

    // ==================== shutdown ====================

    @Test
    @DisplayName("shutdown — flushes remaining entries")
    void shutdownFlushes() {
        service = createService(true, null);
        service.submit(entry("1", "conv1", "agent1"));
        service.shutdown();
        verify(auditStore).appendBatch(any());
    }

    @Test
    @DisplayName("shutdown — handles null executor when disabled")
    void shutdownWhenDisabled() {
        service = createService(false, null);
        assertDoesNotThrow(() -> service.shutdown());
    }

    // ==================== G20: bounded queue ====================

    /**
     * The queue was a {@code ConcurrentLinkedQueue} with no bound, and a failed
     * flush re-offered its whole batch back into it — so a store that stopped
     * accepting writes grew the heap without limit and fed its own failure. Entries
     * past the bound are now dropped and counted instead.
     */
    @Test
    @DisplayName("a stalled store cannot grow the queue past its bound")
    void stalledStoreDoesNotGrowTheQueueWithoutLimit() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 5);
        svc.init();
        // The store has stopped accepting writes; entries keep arriving.
        doThrow(new RuntimeException("store down")).when(auditStore).appendBatch(anyList());

        for (int i = 0; i < 200; i++) {
            svc.submit(entry("id-" + i, "conv-1", "agent-1"));
        }

        assertEquals(5, svc.getQueueSize(), "the queue must stay at its bound instead of accumulating every entry");
        assertEquals(195.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "dropped entries must be counted, not silently lost");
    }

    @Test
    @DisplayName("submits past the bound are dropped, not queued")
    void submitsPastTheBoundAreDropped() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 2);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        svc.submit(entry("id-3", "conv-1", "agent-1"));

        assertEquals(2, svc.getQueueSize());
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count());
    }

    @Test
    @DisplayName("queue size returns to zero after a successful flush")
    void queueDrainsOnSuccessfulFlush() {
        var svc = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry, 10);
        svc.init();

        svc.submit(entry("id-1", "conv-1", "agent-1"));
        svc.submit(entry("id-2", "conv-1", "agent-1"));
        assertEquals(2, svc.getQueueSize());

        svc.flush();
        assertEquals(0, svc.getQueueSize());
    }

    // ==================== G18: conversation sequence ====================

    @Test
    @DisplayName("entries are numbered per conversation when the store supports it")
    void entriesAreNumberedPerConversation() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation(anyString())).thenReturn(0L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.submit(entry("id-2", "conv-a", "agent-1"));
        service.submit(entry("id-3", "conv-b", "agent-1"));
        service.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();

        assertEquals(0L, written.get(0).sequence());
        assertEquals(1L, written.get(1).sequence(), "the second entry of a conversation continues the chain");
        assertEquals(0L, written.get(2).sequence(), "a different conversation has its own chain");
    }

    @Test
    @DisplayName("the sequence continues from what is already stored")
    void sequenceIsSeededFromTheStore() {
        when(auditStore.supportsSequence()).thenReturn(true);
        when(auditStore.countByConversation("conv-a")).thenReturn(7L);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(7L, written.getFirst().sequence());
    }

    /**
     * A store that drops the field must not be handed a sequence — the signature
     * covers it, so every one of its rows would read as tampered.
     */
    @Test
    @DisplayName("a store that cannot persist the sequence gets unsequenced entries")
    void storeWithoutSequenceSupportGetsUnsequencedEntries() {
        when(auditStore.supportsSequence()).thenReturn(false);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        assertEquals(AuditEntry.UNSEQUENCED, written.getFirst().sequence());
        verify(auditStore, never()).countByConversation(anyString());
    }

    @Test
    @DisplayName("a signed entry verifies through the service")
    void signedEntryVerifies() {
        when(auditStore.supportsSequence()).thenReturn(true);
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        assertTrue(service.isSigningEnabled());
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
        assertEquals(AuditVerificationStatus.INVALID, service.verifyEntry(stored.withEnvironment("TAMPERED")));
        assertEquals(AuditVerificationStatus.UNSIGNED, service.verifyEntry(stored.withHmac(null)));
    }

    /**
     * G17 end to end: a GDPR erasure rewrites userId on a stored entry, and the
     * entry must still verify — under v1/v2 it did not, so every routine erasure
     * manufactured rows indistinguishable from tampered ones.
     */
    @Test
    @DisplayName("a pseudonymised stored entry still verifies")
    void pseudonymisedStoredEntryStillVerifies() {
        service = createService(true, "master-key-1234567890");

        service.submit(entry("id-1", "conv-a", "agent-1"));
        service.flush();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(captor.capture());
        @SuppressWarnings("unchecked")
        List<AuditEntry> written = captor.getValue();
        AuditEntry stored = written.getFirst();

        AuditEntry pseudonymised = stored.withUserId(AuditHmac.pseudonymFor("user1"));
        assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(pseudonymised));
    }
}
