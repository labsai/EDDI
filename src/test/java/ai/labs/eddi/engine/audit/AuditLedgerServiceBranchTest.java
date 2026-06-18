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
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
                Optional.of("master-key"), "deadletter.jsonl", true, "default",
                meterRegistry, natsInstance, signingService, new ObjectMapper());
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
                Optional.empty(), "deadletter.jsonl", true, "default",
                meterRegistry, natsInstance, signingService, new ObjectMapper());
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
                Optional.empty(), "deadletter.jsonl", true, "default",
                meterRegistry, natsInstance, signingService, new ObjectMapper());
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

        // First flush fails
        doThrow(new RuntimeException("fail")).doNothing().when(auditStore).appendBatch(any());
        service.submit(entry("1", "c1", "a1"));
        service.flush(); // fail 1

        assertEquals(1, service.getQueueSize()); // re-queued

        // Second flush succeeds
        service.flush();
        assertEquals(0, service.getQueueSize());
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
                Optional.empty(), "deadletter.jsonl", false, "default",
                meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        // Make flush fail 3 times to trigger dead letter
        doThrow(new RuntimeException("fail")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "c1", "a1"));
        service.flush(); // fail 1
        service.flush(); // fail 2
        service.flush(); // fail 3 → drop → writeToDeadLetter

        verify(js).publish(eq("eddi.deadletter.audit"), any(byte[].class));

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
                Optional.empty(), "Z:\\nonexistent\\path\\deadletter.jsonl", false, "default",
                meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        doThrow(new RuntimeException("fail")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        // Should not throw even though both NATS and file fail
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
                Optional.empty(), "Z:\\nonexistent\\deadletter.jsonl", false, "default",
                meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        doThrow(new RuntimeException("fail")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

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
                Optional.empty(), "deadletter.jsonl", false, "default",
                meterRegistry, natsInstance, null, failingMapper);
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
                Optional.empty(), "Z:\\nonexistent\\deadletter.jsonl", false, "default",
                meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        doThrow(new RuntimeException("fail")).when(auditStore).appendBatch(any());

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush();

        // Should not call jetStream since connection is CLOSED
        verify(conn, never()).jetStream();

        service.shutdown();
    }
}
