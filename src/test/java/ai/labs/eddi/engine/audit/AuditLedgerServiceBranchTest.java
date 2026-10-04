/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.cluster.KvKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
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

    @TempDir
    Path tempDir;

    /**
     * A dead-letter path whose write is guaranteed to fail on every platform.
     * <p>
     * The parent directory is deliberately never created, and {@code Files.write}
     * with {@code CREATE} does not create parent directories - so the write throws
     * {@code NoSuchFileException} on Linux and Windows alike.
     * <p>
     * This used to be a hardcoded {@code Z:\nonexistent\...}, which is unwritable
     * only on Windows: a backslash is a legal character in a Unix filename, so on
     * the Linux CI runner that whole string was one relative filename which
     * {@code CREATE} happily created. The tests below then exercised the file
     * fallback's SUCCESS path while claiming to cover its failure path, and left a
     * junk file in the build directory.
     */
    private String unwritableDeadLetterPath() throws IOException {
        // A MISSING parent directory is no longer unwritable: writeToDeadLetter now
        // calls Files.createDirectories on it, deliberately, so a missing directory
        // cannot cost an audit record (see
        // deadLetterWriteCreatesItsMissingParentDirectory).
        // Blocking the path therefore needs something createDirectories cannot resolve:
        // a regular FILE where the parent directory has to go, which fails with
        // FileAlreadyExistsException on every OS.
        Path blocker = tempDir.resolve("blocked-by-a-regular-file");
        if (!Files.exists(blocker)) {
            Files.writeString(blocker, "not a directory");
        }
        return blocker.resolve("deadletter.jsonl").toString();
    }

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

        IAuditClusterSupport natsInstance = null;

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

        IAuditClusterSupport natsInstance = null;

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

        IAuditClusterSupport natsInstance = null;

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

    @Test
    @DisplayName("writeToDeadLetter publishes to the cluster dead-letter stream in cluster mode")
    void writeToDeadLetterNats() throws Exception {
        IAuditClusterSupport cluster = mock(IAuditClusterSupport.class);
        doReturn(true).when(cluster).isClustered();
        doReturn(true).when(cluster).publishDeadLetter(anyString());
        doReturn(OptionalLong.of(0L)).when(cluster).nextSequence(anyString(), any());

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, cluster, null, new ObjectMapper());
        service.init();

        // Make flush fail 3 times to trigger dead letter
        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush(); // fail 1
        service.flush(); // fail 2
        service.flush(); // fail 3 → drop → writeToDeadLetter

        verify(cluster).publishDeadLetter(argThat(json -> json.contains("audit_dead_letter")));
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the abandoned entry must be counted as dropped, not silently stored by the per-entry retry");

        service.shutdown();
    }

    // ==================== writeToDeadLetter — NATS fails, fallback to file
    // ====================

    @Test
    @DisplayName("writeToDeadLetter falls back to file when the cluster stream is unavailable")
    void writeToDeadLetterNatsFails() throws Exception {
        IAuditClusterSupport cluster = mock(IAuditClusterSupport.class);
        doReturn(true).when(cluster).isClustered();
        doReturn(false).when(cluster).publishDeadLetter(anyString());
        doReturn(OptionalLong.of(0L)).when(cluster).nextSequence(anyString(), any());

        // Use a temp file path that likely fails (to cover the file-fallback error
        // path)
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), unwritableDeadLetterPath(), false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, cluster, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        // The stream was actually attempted: without this the test would still pass
        // if writeToDeadLetter were never reached at all.
        verify(cluster, atLeastOnce()).publishDeadLetter(anyString());
        // ...and the file fallback genuinely failed rather than quietly succeeding.
        assertFalse(Files.exists(Path.of(unwritableDeadLetterPath())),
                "the dead-letter write must fail, otherwise this test does not cover the failure path");
        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the dead-letter path must actually have been reached");

        // Should not throw even though both the stream and the file fail
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
        IAuditClusterSupport natsInstance = null;

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
        IAuditClusterSupport natsInstance = null;

        // Use a nonexistent path to test error handling
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), unwritableDeadLetterPath(), false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, natsInstance, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush(); // triggers dead letter

        // No cluster support (single node), so the file fallback is the path under
        // test — and it failed, rather than creating the file and passing vacuously.
        assertFalse(Files.exists(Path.of(unwritableDeadLetterPath())),
                "the dead-letter write must fail, otherwise this test does not cover the failure path");
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
        IAuditClusterSupport natsInstance = null;

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

    // ==================== single node: cluster support present but inert
    // ====================

    @Test
    @DisplayName("writeToDeadLetter never uses the cluster stream on a single node")
    void writeToDeadLetterNatsNotConnected() throws Exception {
        IAuditClusterSupport cluster = mock(IAuditClusterSupport.class);
        doReturn(false).when(cluster).isClustered();

        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), unwritableDeadLetterPath(), false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, cluster, null, new ObjectMapper());
        service.init();

        storeIsDown();

        service.submit(entry("1", "c1", "a1"));
        service.flush();
        service.flush();
        service.flush();

        assertEquals(1.0, meterRegistry.counter("eddi_audit_entries_dropped_total").count(),
                "the dead-letter path must actually have been reached");
        verify(cluster, never()).publishDeadLetter(anyString());
        verify(cluster, never()).nextSequence(anyString(), any());

        service.shutdown();
    }
    @Test
    @DisplayName("the gdpr-stop of an erasure on another node marks the user here, so later entries are pseudonymised")
    void remoteErasureStopMarksTheUser() {
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, null, null, new ObjectMapper());
        assertEquals("user1", service.pseudonymiseIfErased(entry("1", "c1", "a1")).userId(), "nobody erased yet");

        assertEquals(0, service.stopInFlightWorkByHash(KvKeys.sha256("user1")));

        assertNotEquals("user1", service.pseudonymiseIfErased(entry("2", "c1", "a1")).userId());
        assertEquals(0, service.stopInFlightWork("user1"), "on the erasing node the cascade marks the user itself");
    }
}
