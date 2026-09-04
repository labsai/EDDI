/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link AuditLedgerService} — scrub paths, agent
 * signing, dead-letter NATS/file paths, serialization fallback.
 */
@DisplayName("AuditLedgerService — Branch Coverage")
class AuditLedgerServiceBranchTest {

    @Mock
    private IAuditStore auditStore;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
    }

    private AuditEntry entry(String id, String convId, String agentId) {
        return new AuditEntry(id, convId, agentId, 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                Map.of("text", "hello"), Map.of("text", "response"),
                null, null, List.of("action1"), 0.0, Instant.now(), null, null);
    }

    private AuditEntry entryWithMaps(Map<String, Object> input, Map<String, Object> output,
                                     Map<String, Object> llmDetail, Map<String, Object> toolCalls) {
        return new AuditEntry("id1", "conv1", "agent1", 1, "user1", "production",
                0, "taskId", "LlmTask", 0, 100L,
                input, output, llmDetail, toolCalls, List.of(), 0.0, Instant.now(), null, null);
    }

    private AuditLedgerService createSimple(boolean enabled, String masterKey) {
        var svc = AuditLedgerService.createForTesting(auditStore, enabled, 60, masterKey, meterRegistry);
        svc.init();
        return svc;
    }

    /**
     * A store that is genuinely unavailable refuses the batch <em>and</em> the
     * per-entry retry the ledger falls back to. Stubbing only {@code appendBatch}
     * leaves {@code appendEntry} answering successfully on the mock, so the entry
     * is quietly stored by the recovery path and the re-queue / dead-letter branch
     * these tests exist to cover is never reached.
     */
    private void storeIsDown() {
        doThrow(new RuntimeException("db error")).when(auditStore).appendBatch(anyList());
        doThrow(new RuntimeException("db error")).when(auditStore).appendEntry(any());
    }

    // ==================== scrubSecrets — null maps ====================

    @Test
    @DisplayName("submit — null input/output/llmDetail/toolCalls maps are handled gracefully")
    void submitNullMaps() throws Exception {
        var service = createSimple(true, null);
        var entry = new AuditEntry("id1", "conv1", "agent1", 1, "user1", "prod",
                0, "task", "type", 0, 100L,
                null, null, null, null, List.of(), 0.0, Instant.now(), null, null);

        service.submit(entry);
        assertEquals(1, service.getQueueSize());

        service.flush();
        verify(auditStore).appendBatch(argThat(batch -> {
            AuditEntry e = batch.getFirst();
            assertNull(e.input());
            assertNull(e.output());
            assertNull(e.llmDetail());
            assertNull(e.toolCalls());
            return true;
        }));
    }

    // ==================== scrubSecrets — empty maps ====================

    @Test
    @DisplayName("submit — empty maps are returned as-is")
    void submitEmptyMaps() throws Exception {
        var service = createSimple(true, null);
        var entry = entryWithMaps(Map.of(), Map.of(), Map.of(), Map.of());

        service.submit(entry);
        service.flush();

        verify(auditStore).appendBatch(argThat(batch -> {
            AuditEntry e = batch.getFirst();
            assertTrue(e.input().isEmpty());
            assertTrue(e.output().isEmpty());
            return true;
        }));
    }

    // ==================== scrubSecrets — nested map ====================

    @Test
    @DisplayName("submit — nested maps are recursively scrubbed")
    void submitNestedMaps() throws Exception {
        var service = createSimple(true, null);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "value");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nested", nested);
        var entry = entryWithMaps(input, Map.of(), null, null);

        service.submit(entry);
        service.flush();

        verify(auditStore).appendBatch(any());
    }

    // ==================== scrubValue — List values ====================

    @Test
    @DisplayName("submit — list values in maps are scrubbed per element")
    void submitListValues() throws Exception {
        var service = createSimple(true, null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", List.of("item1", "item2"));
        var entry = entryWithMaps(input, Map.of(), null, null);

        service.submit(entry);
        service.flush();

        verify(auditStore).appendBatch(any());
    }

    // ==================== scrubValue — non-String/Map/List returns as-is
    // ====================

    @Test
    @DisplayName("submit — integer values in maps are returned as-is")
    void submitIntegerValues() throws Exception {
        var service = createSimple(true, null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", 42);
        input.put("active", true);
        var entry = entryWithMaps(input, Map.of(), null, null);

        service.submit(entry);
        service.flush();

        verify(auditStore).appendBatch(argThat(batch -> {
            AuditEntry e = batch.getFirst();
            assertEquals(42, e.input().get("count"));
            assertEquals(true, e.input().get("active"));
            return true;
        }));
    }

    // ==================== submit — null agentId skips agent signing
    // ====================

    @Test
    @DisplayName("submit — null agentId skips agent signature")
    void submitNullAgentIdSkipsSigning() throws Exception {
        var service = createSimple(true, null);
        var entry = new AuditEntry("id1", "conv1", null, 1, "user1", "prod",
                0, "task", "type", 0, 100L,
                Map.of(), Map.of(), null, null, List.of(), 0.0, Instant.now(), null, null);

        service.submit(entry);
        assertEquals(1, service.getQueueSize());
    }

    // ==================== applyAgentSignature — hmac non-null ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("applyAgentSignature signs using hmac when available")
    void applyAgentSignatureWithHmac() throws Exception {
        AgentSigningService signingService = mock(AgentSigningService.class);
        doReturn("sig123").when(signingService).sign(anyString(), anyString(), anyString());

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.of("master-key"), "deadletter.jsonl", true, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, signingService, new ObjectMapper());
        service.init();

        var entry = entry("id1", "conv1", "agent1");
        service.submit(entry);
        service.flush();

        // The entry should have hmac (from master key) and agent signature
        verify(auditStore).appendBatch(argThat(batch -> {
            AuditEntry e = batch.getFirst();
            assertNotNull(e.hmac());
            assertEquals("sig123", e.agentSignature());
            return true;
        }));
        // Verify sign was called with hmac (not entry id)
        verify(signingService).sign(eq("default"), eq("agent1"), argThat(payload -> payload != null && !payload.equals("id1")));

        service.shutdown();
    }

    // ==================== applyAgentSignature — AgentSigningException
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("applyAgentSignature returns original entry on AgentSigningException")
    void applyAgentSignatureException() throws Exception {
        AgentSigningService signingService = mock(AgentSigningService.class);
        doThrow(new AgentSigningService.AgentSigningException("no key", new RuntimeException("missing")))
                .when(signingService).sign(anyString(), anyString(), anyString());

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", true, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, signingService, new ObjectMapper());
        service.init();

        var entry = entry("id1", "conv1", "agent1");
        service.submit(entry);
        service.flush();

        // Entry should have no agent signature (exception was caught)
        verify(auditStore).appendBatch(argThat(batch -> {
            AuditEntry e = batch.getFirst();
            assertNull(e.agentSignature());
            return true;
        }));

        service.shutdown();
    }

    // ==================== applyAgentSignature — null hmac uses entry.id()
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("applyAgentSignature signs using entry.id() when hmac is null")
    void applyAgentSignatureNullHmac() throws Exception {
        AgentSigningService signingService = mock(AgentSigningService.class);
        doReturn("sig-from-id").when(signingService).sign(anyString(), anyString(), anyString());

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        // No master key → no hmac
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", true, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, signingService, new ObjectMapper());
        service.init();

        var entry = entry("my-id", "conv1", "agent1");
        service.submit(entry);
        service.flush();

        // Should sign with entry.id() since hmac is null
        verify(signingService).sign(eq("default"), eq("agent1"), eq("my-id"));

        service.shutdown();
    }

    // ==================== flush — success after failure resets counter
    // ====================

    @Test
    @DisplayName("flush — success after failure resets consecutiveFailures")
    void flushSuccessResetsFailures() throws Exception {
        var service = createSimple(true, null);

        // First flush fails on BOTH write paths — the batch and the per-entry retry
        // it falls back to — which is what an unavailable store looks like. The
        // second flush finds the batch path healthy again and never reaches the
        // per-entry stub.
        doThrow(new RuntimeException("fail")).doNothing().when(auditStore).appendBatch(any());
        doThrow(new RuntimeException("fail")).when(auditStore).appendEntry(any());
        service.submit(entry("1", "c1", "a1"));
        service.flush(); // fail 1

        assertEquals(1, service.getQueueSize()); // re-queued

        // Second flush succeeds
        service.flush();
        assertEquals(0, service.getQueueSize());
        verify(auditStore, times(2)).appendBatch(any());
        assertEquals(0.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "a recovered store must not have dropped anything");
    }

    // ==================== writeToDeadLetter — NATS path ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("writeToDeadLetter publishes to NATS when available")
    void writeToDeadLetterNats() throws Exception {
        Connection conn = mock(Connection.class);
        doReturn(Connection.Status.CONNECTED).when(conn).getStatus();
        JetStream js = mock(JetStream.class);
        doReturn(js).when(conn).jetStream();

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(true).when(natsInstance).isResolvable();
        doReturn(conn).when(natsInstance).get();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        // Make flush fail 3 times to trigger dead letter
        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush(); // fail 1
        service.flush(); // fail 2
        service.flush(); // fail 3 → drop → writeToDeadLetter

        verify(js).publish(eq("eddi.deadletter.audit"), any(byte[].class));
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the abandoned entry must be counted as dropped, not silently stored by the per-entry retry");

        service.shutdown();
    }

    // ==================== writeToDeadLetter — NATS fails, fallback to file
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("writeToDeadLetter falls back to file when NATS publish fails")
    void writeToDeadLetterNatsFails() throws Exception {
        Connection conn = mock(Connection.class);
        doReturn(Connection.Status.CONNECTED).when(conn).getStatus();
        JetStream js = mock(JetStream.class);
        doReturn(js).when(conn).jetStream();
        doThrow(new RuntimeException("nats fail")).when(js).publish(anyString(), any(byte[].class));

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(true).when(natsInstance).isResolvable();
        doReturn(conn).when(natsInstance).get();

        // Use a temp file path that likely fails (to cover the file-fallback error
        // path)
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "Z:\\nonexistent\\path\\deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        verify(js).publish(eq("eddi.deadletter.audit"), any(byte[].class));
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the dead-letter path must actually have been reached");

        // Should not throw even though both NATS and file fail
        service.shutdown();
    }

    /**
     * Finding 26. {@code StandardOpenOption.CREATE} creates the file, never its
     * parent — and the default {@code eddi.audit.dead-letter-path} lives under
     * {@code /opt/eddi/data}, which the shipped image does not create and UID 185
     * cannot create at runtime. So on the documented docker quick start every
     * dead-letter write threw {@code NoSuchFileException} into a swallowed catch
     * and the entries the ledger abandoned were gone outright, while the class's
     * own Javadoc rests its INCOMPLETE-not-BROKEN verdict on that sink being
     * "durable evidence". {@code init()} now creates the directory up front and the
     * write re-creates it if it went away in between.
     */
    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("writeToDeadLetter creates the parent directory rather than silently writing nothing")
    void deadLetterWriteCreatesItsMissingParentDirectory(@TempDir Path tempDir) throws Exception {
        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        Path deadLetterFile = tempDir.resolve("opt").resolve("eddi").resolve("data").resolve("audit-deadletter.jsonl");
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), deadLetterFile.toString(), false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        assertTrue(Files.isDirectory(deadLetterFile.getParent()),
                "startup must report — and provision — the sink before the incident that needs it");
        // Take it away again, so the write itself has to cope.
        Files.delete(deadLetterFile.getParent());

        storeIsDown();
        service.submit(entry("dl-1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        assertTrue(Files.exists(deadLetterFile), "the abandoned entry must actually reach the sink");
        assertTrue(Files.readString(deadLetterFile).contains("\"conversationId\":\"c1\""),
                "and it must be the entry that was abandoned");

        service.shutdown();
    }

    // ==================== writeToDeadLetter — no NATS, file path
    // ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("writeToDeadLetter uses file when NATS not available")
    void writeToDeadLetterFileOnly() throws Exception {
        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        // Use a nonexistent path to test error handling
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "Z:\\nonexistent\\deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        verify(natsInstance).isResolvable();
        verify(natsInstance, never()).get(); // unresolvable → straight to the file branch
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the dead-letter path must actually have been reached");

        // Should handle file write failure gracefully
        service.shutdown();
    }

    // ==================== serializeDeadLetterEntry — Jackson failure
    // ====================

    @Test
    @DisplayName("serializeDeadLetterEntry returns error JSON when Jackson fails")
    void serializeDeadLetterEntryJacksonFail() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        doThrow(mock(JsonProcessingException.class)).when(failingMapper).writeValueAsString(any());

        @SuppressWarnings("unchecked")
        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(false).when(natsInstance).isResolvable();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, failingMapper);
        service.init();

        var e = entry("1", "conv1", "agent1");
        String json = service.serializeDeadLetterEntry(e, null);
        assertEquals("{\"error\":\"serialization_failed\"}", json);

        service.shutdown();
    }

    // ==================== init — blank master key ====================

    @Test
    @DisplayName("init with blank master key does not set hmacKey")
    void initBlankMasterKey() throws Exception {
        var service = AuditLedgerService.createForTesting(auditStore, true, 60, "   ", meterRegistry);
        service.init();

        assertNull(service.getHmacKey());
        service.shutdown();
    }

    // ==================== init — empty master key ====================

    @Test
    @DisplayName("init with empty optional master key does not set hmacKey")
    void initEmptyMasterKey() throws Exception {
        var service = AuditLedgerService.createForTesting(auditStore, true, 60, null, meterRegistry);
        service.init();

        assertNull(service.getHmacKey());
        service.shutdown();
    }

    // ==================== NATS connection not CONNECTED ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("writeToDeadLetter skips NATS when connection not CONNECTED")
    void writeToDeadLetterNatsNotConnected() throws Exception {
        Connection conn = mock(Connection.class);
        doReturn(Connection.Status.CLOSED).when(conn).getStatus();

        Instance<Connection> natsInstance = mock(Instance.class);
        doReturn(true).when(natsInstance).isResolvable();
        doReturn(conn).when(natsInstance).get();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "Z:\\nonexistent\\deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush();

        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the dead-letter path must actually have been reached");
        // Should not call jetStream since connection is CLOSED
        verify(conn, never()).jetStream();

        service.shutdown();
    }
}
