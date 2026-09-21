/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.SecurityConfig;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupSigningGuard}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 3) refactor.
 * Covers the guard-clause branches and the key-rotation-correctness fix
 * directly; the full sign → self-verify → nonce-validate happy path requires
 * real Ed25519 key material and is exercised by {@code AgentSigningService}'s
 * own tests plus {@code GroupConversationServiceBranchCoverageTest}'s
 * {@code SigningPaths} suite ({@code GroupConversationServiceHitlCoverage3Test}
 * only exercises {@code verifyPriorEntriesIfRequired}'s guard clauses, not the
 * signing happy path).
 *
 * @author tests
 */
class GroupSigningGuardTest {

    @Mock
    private IAgentStore agentStore;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private NonceCacheService nonceCacheService;

    private static final String AGENT_A = "agent-a";
    private static final String GROUP_ID = "group-1";
    private static final String TENANT = "default";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private GroupSigningGuard guard() {
        return new GroupSigningGuard(agentStore, agentSigningService, nonceCacheService, TENANT);
    }

    // =================================================================
    // signOutgoingMessage — guard clauses, all resolve to UNSIGNED
    // =================================================================

    @Test
    void signOutgoingMessage_nullAgentStore_unsigned() {
        var guard = new GroupSigningGuard(null, agentSigningService, nonceCacheService, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_nullSigningService_unsigned() {
        var guard = new GroupSigningGuard(agentStore, null, nonceCacheService, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentStore);
    }

    @Test
    void signOutgoingMessage_nullNonceCache_unsigned() {
        var guard = new GroupSigningGuard(agentStore, agentSigningService, null, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
    }

    @Test
    void signOutgoingMessage_noSecurityConfig_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        // security left null
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_signingDisabled_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(false);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_nullResponse_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, null, "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_storeThrows_unsignedNotPropagated() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
    }

    // =================================================================
    // verifyPriorEntriesIfRequired — guard clauses
    // =================================================================

    @Test
    void verifyPriorEntriesIfRequired_nullAgentStore_noop() {
        var guard = new GroupSigningGuard(null, agentSigningService, nonceCacheService, TENANT);
        assertDoesNotThrow(() -> guard.verifyPriorEntriesIfRequired(AGENT_A, gc()));
    }

    @Test
    void verifyPriorEntriesIfRequired_resourceIdNull_noReadOfConfig() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(null);

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));

        // any(), not anyInt(): read(String, Integer) takes a BOXED Integer, and
        // anyInt() does not match null — so a regression that called read(id, null)
        // would satisfy this verify vacuously while the store read fired on every turn.
        verify(agentStore, never()).read(eq(AGENT_A), any());
    }

    @Test
    void verifyPriorEntriesIfRequired_storeThrows_swallowed() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));
    }

    @Test
    void verifyPriorEntriesIfRequired_peerVerificationNotRequired_noop() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setRequirePeerVerification(false);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));

        verifyNoInteractions(agentSigningService);
    }

    // =================================================================
    // Key-rotation correctness: lookups must use the exact key version an
    // entry/envelope declares, not "whichever key happens to be valid right
    // now" — during a rotation overlap window those can disagree.
    // =================================================================

    private static final String KEY_V1_B64 = "old-key-b64";
    private static final String KEY_V2_B64 = "new-key-b64";

    @Test
    void verifyPriorEntriesIfRequired_keyRotationOverlap_eachEntryVerifiedWithItsOwnKeyVersion() throws Exception {
        var receiverResId = mockResourceId();
        var receiverConfig = new AgentConfiguration();
        var receiverSecurity = new SecurityConfig();
        receiverSecurity.setRequirePeerVerification(true);
        receiverConfig.setSecurity(receiverSecurity);
        when(agentStore.getCurrentResourceId("receiver")).thenReturn(receiverResId);
        when(agentStore.read("receiver", receiverResId.getVersion())).thenReturn(receiverConfig);

        // Both keys are simultaneously valid (no expiry) — an overlap window
        // where naively picking "the highest valid version" would pick v2 for
        // an entry that was actually signed with v1.
        var speakerResId = mockResourceId();
        var speakerConfig = new AgentConfiguration();
        var identity = new AgentIdentity();
        identity.setKeys(List.of(
                new AgentPublicKey(1, KEY_V1_B64, 0L, 0L),
                new AgentPublicKey(2, KEY_V2_B64, 0L, 0L)));
        speakerConfig.setIdentity(identity);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(speakerResId);
        when(agentStore.read(AGENT_A, speakerResId.getVersion())).thenReturn(speakerConfig);

        when(agentSigningService.verifyEnvelope(any(SignedEnvelope.class), anyString())).thenReturn(true);

        long now = Instant.now().toEpochMilli();
        var entrySignedWithV1 = new TranscriptEntry(AGENT_A, "A", "hello", 0, "P", TranscriptEntryType.OPINION,
                Instant.now(), null, null, "sig-1", "nonce-1", now, 1);
        var entrySignedWithV2 = new TranscriptEntry(AGENT_A, "A", "world", 0, "P", TranscriptEntryType.OPINION,
                Instant.now(), null, null, "sig-2", "nonce-2", now, 2);
        var g = gc();
        g.getTranscript().add(entrySignedWithV1);
        g.getTranscript().add(entrySignedWithV2);

        guard().verifyPriorEntriesIfRequired("receiver", g);

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentSigningService, times(2)).verifyEnvelope(any(SignedEnvelope.class), keyCaptor.capture());
        // The buggy getKeyValidAt(timestamp) lookup, cached per-agent-only,
        // would resolve BOTH entries to the higher-version key (v2) — the
        // second entry's own declared version would never even be consulted.
        assertEquals(List.of(KEY_V1_B64, KEY_V2_B64), keyCaptor.getAllValues(),
                "entry signed with key v1 must be verified against key v1, and the v2 entry against key v2 — "
                        + "not both collapsed onto whichever key is newest");
    }

    @Test
    void signOutgoingMessage_selfVerify_usesExactSignedKeyVersion_notWhicheverIsValidNow() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);

        // v2 is the highest version (so it's the one signOutgoingMessage signs
        // with) but is NOT yet valid at "now" — v1 is the only key currently
        // valid. A self-verify that asks "what's valid now" would wrongly use
        // v1 to check a signature made with v2.
        var identity = new AgentIdentity();
        long farFuture = Instant.now().toEpochMilli() + 10_000_000L;
        identity.setKeys(List.of(
                new AgentPublicKey(1, KEY_V1_B64, 0L, 0L),
                new AgentPublicKey(2, KEY_V2_B64, farFuture, 0L)));
        config.setIdentity(identity);

        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var signed = new SignedEnvelope(AGENT_A, GROUP_ID, Map.of("content", "hi", "phase", "OPINION"),
                "nonce-xyz", Instant.now().toEpochMilli(), "sig-xyz", 2);
        when(agentSigningService.signEnvelope(eq(TENANT), eq(AGENT_A), any(), eq(2))).thenReturn(signed);
        when(agentSigningService.verifyEnvelope(eq(signed), eq(KEY_V2_B64))).thenReturn(true);
        when(agentSigningService.verifyEnvelope(eq(signed), eq(KEY_V1_B64))).thenReturn(false);
        when(nonceCacheService.validate("nonce-xyz", signed.timestampMs()))
                .thenReturn(NonceCacheService.NonceValidation.VALID);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertNotEquals(GroupSigningGuard.SigningResult.UNSIGNED, result,
                "self-verify must look up key v2 (what it actually signed with), not whatever's valid "
                        + "'now' — otherwise a signature made just before a key's validFrom is wrongly discarded");
        assertEquals("sig-xyz", result.signature());
    }

    // =================================================================
    // forgetConversation
    // =================================================================

    @Test
    void forgetConversation_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> guard().forgetConversation("never-registered"));
    }

    private GroupConversation gc() {
        var g = new GroupConversation();
        g.setId("gc-1");
        g.setGroupId(GROUP_ID);
        return g;
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return AGENT_A;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    @Test
    void versionZeroResolvesToTheLegacyKey_evenAfterAVersionedListIsAdded() {
        // Entries signed before key versioning carry signatureKeyVersion 0. Once an
        // operator onboards a keys list starting at v1 — the normal rotation path —
        // a version-exact lookup returned null for every one of them, and peer
        // verification logged authentic entries as unverifiable. Version 0 MEANS
        // "signed against the legacy publicKey field", so that field is its key.
        var identity = new AgentConfiguration.AgentIdentity();
        identity.setPublicKey("legacy-key");

        assertEquals("legacy-key", identity.getKeyForVersion(0), "no versioned keys yet");

        identity.setKeys(List.of(new AgentPublicKey(1, "v1-key", 0L, Long.MAX_VALUE)));

        assertEquals("legacy-key", identity.getKeyForVersion(0),
                "a pre-rotation entry must still verify after the keys list is onboarded");
        assertEquals("v1-key", identity.getKeyForVersion(1), "versioned lookup is unaffected");
        assertNull(identity.getKeyForVersion(2), "an unknown version is still unknown");
    }
}
