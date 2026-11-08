/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.SigningTestSupport;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
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
import org.mockito.ArgumentCaptor;
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

    // ==================== agent signature ====================

    /** A ledger with an HMAC key, signing as agents through {@code signer}. */
    private AuditLedgerService signingLedger(AuditAgentSigner signer, boolean agentSigningEnabled) {
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.of("master-key"), "deadletter.jsonl", agentSigningEnabled, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, null, signer, new ObjectMapper());
        service.init();
        return service;
    }

    /** The one entry the ledger flushed to the store. */
    @SuppressWarnings("unchecked")
    private AuditEntry flushedEntry(AuditLedgerService service) {
        service.flush();
        var batch = ArgumentCaptor.forClass(List.class);
        verify(auditStore).appendBatch(batch.capture());
        return (AuditEntry) batch.getValue().getFirst();
    }

    @Test
    @DisplayName("the agent signs the entry after its HMAC, and the signed entry is what is stored")
    void agentSignsAfterTheHmac() throws Exception {
        AuditAgentSigner signer = mock(AuditAgentSigner.class);
        when(signer.sign(eq("default"), any())).thenAnswer(invocation -> {
            AuditEntry entry = invocation.getArgument(1);
            assertNotNull(entry.hmac(), "the signature covers the HMAC, so the HMAC comes first");
            return entry.withAgentSignature("v1:sig");
        });
        var service = signingLedger(signer, true);

        service.submit(entry("id1", "conv1", "agent1"));

        assertEquals("v1:sig", flushedEntry(service).agentSignature());
        service.shutdown();
    }

    @Test
    @DisplayName("eddi.audit.agent-signing-enabled=false signs nothing")
    void agentSigningDisabledSignsNothing() throws Exception {
        AuditAgentSigner signer = mock(AuditAgentSigner.class);
        var service = signingLedger(signer, false);

        service.submit(entry("id1", "conv1", "agent1"));

        assertNull(flushedEntry(service).agentSignature());
        verify(signer, never()).sign(anyString(), any());
        service.shutdown();
    }

    /**
     * Sign, store, verify — with real Ed25519 keys over an in-memory vault, and a
     * signing agent version from a stubbed agent store.
     */
    @Nested
    @DisplayName("agent signature round trip")
    class AgentSignatureRoundTrip {

        private AgentSigningService signingService;
        private IAgentStore agentStore;
        private AgentConfiguration config;
        private AuditLedgerService service;

        @BeforeEach
        void provisionASigningAgent() throws Exception {
            signingService = SigningTestSupport.signingService(SigningTestSupport.inMemoryVault(), meterRegistry);
            agentStore = mock(IAgentStore.class);
            var key = signingService.generateKey("default", "agent1", 1);
            config = new AgentConfiguration();
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            config.setSecurity(security);
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(List.of(new AgentPublicKey(key.version(), key.publicKeyB64(), 0, 0))));
            config.setIdentity(identity);
            when(agentStore.read("agent1", 1)).thenReturn(config);
            when(agentStore.readIncludingDeleted("agent1", 1)).thenReturn(config);
            service = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), true);
        }

        @Test
        @DisplayName("a stored entry carries a versioned agent signature and verifies VALID")
        void signedEntryVerifies() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);

            assertTrue(stored.agentSignature().startsWith("v1:"), stored.agentSignature());
            assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(stored));
            service.shutdown();
        }

        @Test
        @DisplayName("a replaced agent signature on intact content is AGENT_SIGNATURE_INVALID")
        void replacedSignatureIsReported() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            String otherKeysSignature = signingService.sign("default", "agent1", signingService.generateKey("default", "agent1", 2).version(),
                    AuditAgentSigner.SIGNED_PREFIX + stored.hmac());

            assertEquals(AuditVerificationStatus.AGENT_SIGNATURE_INVALID,
                    service.verifyEntry(stored.withAgentSignature("v1:" + otherKeysSignature)));
            service.shutdown();
        }

        @Test
        @DisplayName("edited content is INVALID, whatever the agent signature says")
        void editedContentIsInvalid() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);

            AuditEntry edited = stored.withPayload(Map.of("text", "edited"), stored.output(), stored.llmDetail(), stored.toolCalls());
            assertEquals(AuditVerificationStatus.INVALID, service.verifyEntry(edited));
            service.shutdown();
        }

        /**
         * The agent's owner may delete it permanently. Its entries' HMACs still prove
         * their content, so the uncheckable signature is reported, not counted as
         * tampering (see {@code RestAuditStoreTest}).
         */
        @Test
        @DisplayName("the agent signature of an entry whose agent was permanently deleted is AGENT_KEY_UNAVAILABLE")
        void deletedAgentsSignatureIsUnavailable() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            when(agentStore.read("agent1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
            when(agentStore.readIncludingDeleted("agent1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
            var afterDeletion = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), true);

            assertEquals(AuditVerificationStatus.AGENT_KEY_UNAVAILABLE, afterDeletion.verifyEntry(stored));
            service.shutdown();
            afterDeletion.shutdown();
        }

        @Test
        @DisplayName("a signature stripped from an entry its agent version signs is AGENT_SIGNATURE_MISSING")
        void strippedSignatureIsMissing() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stripped = flushedEntry(service).withAgentSignature(null);

            assertEquals(AuditVerificationStatus.AGENT_SIGNATURE_MISSING, service.verifyEntry(stripped));
            service.shutdown();
        }

        /**
         * With agent signing off for the deployment, no entry is expected to carry one.
         */
        @Test
        @DisplayName("with eddi.audit.agent-signing-enabled=false an unsigned entry is not reported missing")
        void noSignatureIsMissingWhileAgentSigningIsOff() {
            service.shutdown();
            var unsignedLedger = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), false);
            unsignedLedger.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(unsignedLedger);

            assertNull(stored.agentSignature());
            assertEquals(AuditVerificationStatus.VALID, unsignedLedger.verifyEntry(stored));
            unsignedLedger.shutdown();
        }

        @Test
        @DisplayName("an entry whose agent key was revoked since is AGENT_KEY_REVOKED")
        void revokedKeyIsReported() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            var revoked = new AgentConfiguration();
            revoked.setSecurity(config.getSecurity());
            var identity = new AgentConfiguration.AgentIdentity();
            identity.setKeys(new ArrayList<>(config.getIdentity().getKeys().stream().map(AgentPublicKey::revoke).toList()));
            revoked.setIdentity(identity);
            when(agentStore.readIncludingDeleted("agent1", 2)).thenReturn(revoked);
            when(agentStore.getNewestVersionIncludingDeleted("agent1")).thenReturn(2);

            assertEquals(AuditVerificationStatus.AGENT_KEY_REVOKED, service.verifyEntry(stored));
            service.shutdown();
        }

        /**
         * The chain position is taken before the agent signs. An unchecked failure
         * reading the agent version (a driver timeout) used to escape submit, losing
         * the entry and leaving a gap verification reports as a deletion.
         */
        @Test
        @DisplayName("an unchecked agent-store failure neither loses the entry nor leaves a gap in the chain")
        @SuppressWarnings("unchecked")
        void anAgentStoreFailureKeepsTheChainWhole() throws Exception {
            doReturn(true).when(auditStore).supportsSequence();
            when(agentStore.readIncludingDeleted("agent1", 1)).thenThrow(new IllegalStateException("socket timeout")).thenReturn(config);

            assertTrue(service.submit(entry("id1", "conv1", "agent1")));
            assertTrue(service.submit(entry("id2", "conv1", "agent1")));
            service.flush();

            var batch = ArgumentCaptor.forClass(List.class);
            verify(auditStore).appendBatch(batch.capture());
            List<AuditEntry> stored = batch.getValue();
            assertEquals(2, stored.size(), "the entry whose agent version could not be read is still stored");
            assertTrue(stored.get(0).sequence() >= 0);
            assertEquals(stored.get(0).sequence() + 1, stored.get(1).sequence(), "no position between them was consumed");
            assertNull(stored.get(0).agentSignature());
            assertNotNull(stored.get(1).agentSignature());
            service.shutdown();
        }

        @Test
        @DisplayName("an entry of a soft-deleted agent still verifies VALID")
        void softDeletedAgentStillVerifies() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            when(agentStore.read("agent1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
            var afterSoftDelete = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), true);

            assertEquals(AuditVerificationStatus.VALID, afterSoftDelete.verifyEntry(stored));
            service.shutdown();
            afterSoftDelete.shutdown();
        }

        /**
         * Whether an erasure may re-sign a row depends on its HMAC alone: the reseal
         * makes a new agent signature anyway. Graded by the agent signature too, a row
         * whose agent was deleted kept its old HMAC over the redacted content and
         * failed verification from then on.
         */
        @Test
        @DisplayName("an erasure reseals an intact row even when its agent signature can no longer be checked")
        void erasureResealsByTheHmacAlone() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            service.shutdown();
            when(agentStore.read("agent1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
            when(agentStore.readIncludingDeleted("agent1", 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
            var afterDeletion = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), true);
            when(auditStore.getEntriesByUserId(eq("user1"), eq(0), anyInt())).thenReturn(List.of(stored));
            when(auditStore.redactEntry(any(), eq(stored.hmac()))).thenReturn(true);

            var result = afterDeletion.eraseUser("user1");

            assertEquals(1, result.resealed());
            var redacted = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditStore).redactEntry(redacted.capture(), eq(stored.hmac()));
            assertNull(redacted.getValue().agentSignature(), "the deleted agent cannot sign the redacted row");
            assertEquals(AuditVerificationStatus.VALID, afterDeletion.verifyEntry(redacted.getValue()));
            afterDeletion.shutdown();
        }

        /** The row an erasure re-signs, once the sweep has run over {@code row}. */
        private AuditEntry erasedRow(AuditEntry row) {
            when(auditStore.getEntriesByUserId(eq("user1"), eq(0), anyInt())).thenReturn(List.of(row));
            when(auditStore.redactEntry(any(), eq(row.hmac()))).thenReturn(true);

            assertEquals(1, service.eraseUser("user1").resealed());
            var redacted = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditStore).redactEntry(redacted.capture(), eq(row.hmac()));
            return redacted.getValue();
        }

        @Test
        @DisplayName("an erasure gives an intact row a fresh agent signature over its new HMAC")
        void erasureResignsAnIntactRow() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);

            AuditEntry erased = erasedRow(stored);

            assertNotEquals(stored.agentSignature(), erased.agentSignature());
            assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(erased));
            service.shutdown();
        }

        /**
         * A row forged by someone holding the audit HMAC key, without the agent's
         * signature. Re-signed by the erasure, the agent would vouch for a row it never
         * wrote, and the one trace of the forgery would be gone.
         */
        @Test
        @DisplayName("an erasure does not give a row missing its agent signature a genuine one")
        void erasureKeepsAMissingAgentSignatureMissing() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry forged = flushedEntry(service).withAgentSignature(null);

            AuditEntry erased = erasedRow(forged);

            assertNull(erased.agentSignature());
            assertEquals(AuditVerificationStatus.AGENT_SIGNATURE_MISSING, service.verifyEntry(erased));
            service.shutdown();
        }

        @Test
        @DisplayName("an erasure does not replace a forged agent signature with a genuine one")
        void erasureKeepsAForgedAgentSignatureInvalid() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            String otherKeysSignature = signingService.sign("default", "agent1", signingService.generateKey("default", "agent1", 2).version(),
                    AuditAgentSigner.SIGNED_PREFIX + stored.hmac());
            AuditEntry forged = stored.withAgentSignature("v1:" + otherKeysSignature);

            AuditEntry erased = erasedRow(forged);

            assertEquals(AuditVerificationStatus.AGENT_SIGNATURE_INVALID, service.verifyEntry(erased));
            service.shutdown();
        }

        /**
         * A row written before 6.7 carries the agent's signature in the old form:
         * Base64 with no key version, over the bare HMAC, by the key under the agent's
         * unversioned vault name.
         */
        private AuditEntry legacySignedRow() throws Exception {
            var legacy = signingService.generateKey("default", "agent1", 5);
            config.getIdentity().setPublicKey(legacy.publicKeyB64());
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            return stored.withAgentSignature(signingService.sign("default", "agent1", legacy.version(), stored.hmac()));
        }

        @Test
        @DisplayName("a row signed in the pre-6.7 form verifies VALID, also with agent signing off")
        void legacySignatureVerifies() throws Exception {
            AuditEntry legacyRow = legacySignedRow();
            var unsignedLedger = signingLedger(new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore), false);

            assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(legacyRow));
            assertEquals(AuditVerificationStatus.VALID, unsignedLedger.verifyEntry(legacyRow));
            service.shutdown();
            unsignedLedger.shutdown();
        }

        @Test
        @DisplayName("an erasure re-signs a row signed in the pre-6.7 form in the current form")
        void erasureResignsALegacyRow() throws Exception {
            AuditEntry erased = erasedRow(legacySignedRow());

            assertTrue(erased.agentSignature().startsWith("v1:"), erased.agentSignature());
            assertEquals(AuditVerificationStatus.VALID, service.verifyEntry(erased));
            service.shutdown();
        }

        /**
         * A pre-6.7 signature nothing can check is unproven, not tampering, and the
         * erasure must not turn it into a genuine signature: someone holding the audit
         * HMAC key could otherwise write a row with any Base64 in it and have the agent
         * vouch for the row at the next erasure.
         */
        @Test
        @DisplayName("an erasure does not give a row whose pre-6.7 signature cannot be checked a genuine one")
        void erasureDoesNotVouchForAnUncheckableLegacySignature() {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            AuditEntry uncheckable = stored.withAgentSignature(stored.agentSignature().substring("v1:".length()));
            assertEquals(AuditVerificationStatus.AGENT_KEY_UNAVAILABLE, service.verifyEntry(uncheckable));

            AuditEntry erased = erasedRow(uncheckable);

            assertNull(erased.agentSignature());
            assertEquals(AuditVerificationStatus.AGENT_SIGNATURE_MISSING, service.verifyEntry(erased));
            service.shutdown();
        }

        /**
         * Whether the row's signature is a finding cannot be told while the agent store
         * is down, so the reseal signs nothing rather than signing on trust.
         */
        @Test
        @DisplayName("an erasure that cannot read the agent store leaves the resealed row unsigned")
        void erasureLeavesTheRowUnsignedWhenTheAgentStoreFails() throws Exception {
            service.submit(entry("id1", "conv1", "agent1"));
            AuditEntry stored = flushedEntry(service);
            when(agentStore.getNewestVersionIncludingDeleted("agent1")).thenThrow(new IllegalStateException("socket timeout"));

            assertNull(erasedRow(stored).agentSignature());
            service.shutdown();
        }
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
    @DisplayName("the gdpr-stop of an erasure on another node marks the user here, so later entries are pseudonymised and redacted")
    void remoteErasureStopMarksTheUser() {
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, null, null, new ObjectMapper());
        AuditEntry untouched = service.pseudonymiseIfErased(entry("1", "c1", "a1"), false);
        assertEquals("user1", untouched.userId(), "nobody erased yet");
        assertNull(untouched.input().get(AuditLedgerService.REDACTION_MARKER_KEY));

        assertEquals(0, service.stopInFlightWorkByHash(KvKeys.sha256("user1")));

        AuditEntry late = service.pseudonymiseIfErased(entry("2", "c1", "a1"), false);
        assertNotEquals("user1", late.userId());
        assertNotNull(late.input().get(AuditLedgerService.REDACTION_MARKER_KEY),
                "an entry of a user erased on another node is redacted like one erased here (erasure-mode=redact)");
        assertEquals(0, service.stopInFlightWork("user1"), "on the erasing node the cascade marks the user itself");
    }
    @Test
    @DisplayName("cluster mode: a sequence seed that cannot be read leaves the entry unsequenced instead of failing submit")
    void clusteredSeedFailureIsUnsequenced() throws Exception {
        IAuditClusterSupport cluster = mock(IAuditClusterSupport.class);
        doReturn(true).when(cluster).isClustered();
        doThrow(new IllegalStateException("seed read failed")).when(cluster).nextSequence(anyString(), any());
        doReturn(true).when(auditStore).supportsSequence();
        var service = new AuditLedgerService(auditStore, true, 60,
                Optional.empty(), "deadletter.jsonl", false, "default", AuditLedgerService.DEFAULT_MAX_QUEUE_SIZE,
                true, 500, meterRegistry, cluster, null, new ObjectMapper());

        assertDoesNotThrow(() -> service.submit(entry("1", "c1", "a1")));
        service.flush();
        verify(auditStore).appendBatch(argThat(batch -> batch.size() == 1 && batch.get(0).sequence() == AuditEntry.UNSEQUENCED));
    }
}
