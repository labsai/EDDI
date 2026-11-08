/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.SecurityConfig;
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
 * Unit tests for {@link GroupSigningGuard}'s guard clauses and key lookups,
 * with the signing service and the agent store mocked. What the signatures
 * actually prove (tampering, copying, duplication, expiry, against real Ed25519
 * keys) is {@link GroupSigningRoundTripTest}'s job.
 *
 * @author tests
 */
class GroupSigningGuardTest {

    @Mock
    private IAgentStore agentStore;
    @Mock
    private AgentSigningService agentSigningService;

    private static final String AGENT_A = "agent-a";
    private static final String RECEIVER = "receiver";
    private static final String GROUP_ID = "group-1";
    private static final String TENANT = "default";
    private static final String KEY_V1_B64 = "old-key-b64";
    private static final String KEY_V2_B64 = "new-key-b64";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private GroupSigningGuard guard() {
        return new GroupSigningGuard(agentStore, agentSigningService, TENANT);
    }

    // =================================================================
    // sign — every way of not signing returns the entry unchanged
    // =================================================================

    @Test
    void sign_nullAgentStore_unchanged() {
        var entry = entry(AGENT_A, "hi");
        assertSame(entry, new GroupSigningGuard(null, agentSigningService, TENANT).sign(gc(), entry));
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void sign_nullSigningService_unchanged() {
        var entry = entry(AGENT_A, "hi");
        assertSame(entry, new GroupSigningGuard(agentStore, null, TENANT).sign(gc(), entry));
        verifyNoInteractions(agentStore);
    }

    @Test
    void sign_noContent_unchanged() {
        var entry = entry(AGENT_A, null);
        assertSame(entry, guard().sign(gc(), entry));
        verifyNoInteractions(agentStore, agentSigningService);
    }

    @Test
    void sign_noSecurityConfig_unchanged() throws Exception {
        stubAgent(AGENT_A, new AgentConfiguration());
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry));
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void sign_signingDisabled_unchanged() throws Exception {
        var config = new AgentConfiguration();
        config.setSecurity(new SecurityConfig());
        stubAgent(AGENT_A, config);
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry));
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void sign_storeThrows_unchangedNotPropagated() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry));
    }

    @Test
    void sign_signingOnButNoKeyDeclared_unchangedAndNothingSigned() throws Exception {
        stubAgent(AGENT_A, signingAgent(null));
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry));
        verify(agentSigningService, never()).signEnvelope(any(), any(), any(), anyInt());
    }

    /**
     * The newest key is the one that signs; once its {@code validUntilMs} has
     * passed (an admin revoked it) the agent must not sign with it, and must not
     * quietly fall back to an older key either.
     */
    @Test
    void sign_newestKeyExpired_unchangedAndNothingSigned() throws Exception {
        long past = Instant.now().toEpochMilli() - 60_000L;
        stubAgent(AGENT_A, signingAgent(identity(new AgentPublicKey(1, KEY_V1_B64, 0L, 0L),
                new AgentPublicKey(2, KEY_V2_B64, 0L, past))));
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry));
        verify(agentSigningService, never()).signEnvelope(any(), any(), any(), anyInt());
    }

    @Test
    void sign_conversationWithoutId_unchanged() throws Exception {
        stubAgent(AGENT_A, signingAgent(identity(new AgentPublicKey(1, KEY_V1_B64, 0L, 0L))));
        var gc = gc();
        gc.setId(null);
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc, entry));
        verify(agentSigningService, never()).signEnvelope(any(), any(), any(), anyInt());
    }

    @Test
    void sign_selfVerifyFails_unchanged() throws Exception {
        stubAgent(AGENT_A, signingAgent(identity(new AgentPublicKey(1, KEY_V1_B64, 0L, 0L))));
        when(agentSigningService.signEnvelope(eq(TENANT), eq(AGENT_A), any(), eq(1)))
                .thenAnswer(inv -> inv.<SignedEnvelope>getArgument(2).withSignature("sig", 1));
        when(agentSigningService.verifyEnvelope(any(), eq(KEY_V1_B64))).thenReturn(false);
        var entry = entry(AGENT_A, "hi");

        assertSame(entry, guard().sign(gc(), entry), "a signature peers could not verify is never stored");
    }

    /**
     * Signs with the newest version and self-verifies against that version's key,
     * with both keys valid (a rotation overlap).
     */
    @Test
    void sign_newestVersion_selfVerifiedAgainstThatVersionsKey() throws Exception {
        stubAgent(AGENT_A, signingAgent(identity(new AgentPublicKey(1, KEY_V1_B64, 0L, 0L),
                new AgentPublicKey(2, KEY_V2_B64, 0L, 0L))));
        when(agentSigningService.signEnvelope(eq(TENANT), eq(AGENT_A), any(), eq(2)))
                .thenAnswer(inv -> inv.<SignedEnvelope>getArgument(2).withSignature("sig-xyz", 2));
        when(agentSigningService.verifyEnvelope(any(), eq(KEY_V2_B64))).thenReturn(true);

        var signed = guard().sign(gc(), entry(AGENT_A, "hi"));

        assertEquals("sig-xyz", signed.signature());
        assertEquals(2, signed.signatureKeyVersion());
        assertNotNull(signed.signatureNonce());
        assertNotNull(signed.signatureTimestampMs());
        assertEquals("hi", signed.content());
        verify(agentSigningService, never()).verifyEnvelope(any(), eq(KEY_V1_B64));
    }

    /**
     * The signed bytes bind the entry to its conversation, position and key
     * version, not just to its content.
     */
    @Test
    void sign_payloadBindsConversationPositionAndKeyVersion() throws Exception {
        stubAgent(AGENT_A, signingAgent(identity(new AgentPublicKey(3, KEY_V1_B64, 0L, 0L))));
        var envelope = ArgumentCaptor.forClass(SignedEnvelope.class);
        when(agentSigningService.signEnvelope(eq(TENANT), eq(AGENT_A), envelope.capture(), eq(3)))
                .thenAnswer(inv -> inv.<SignedEnvelope>getArgument(2).withSignature("sig", 3));
        when(agentSigningService.verifyEnvelope(any(), eq(KEY_V1_B64))).thenReturn(true);
        var entry = new TranscriptEntry(AGENT_A, "Agent A", "hi", 4, "Critique", TranscriptEntryType.CRITIQUE, Instant.now(), null,
                "agent-b");

        guard().sign(gc(), entry);

        assertEquals(AGENT_A, envelope.getValue().senderId());
        assertEquals(GROUP_ID, envelope.getValue().recipientId());
        assertEquals(Map.of("conversationId", "gc-1", "phaseIndex", 4, "phase", "Critique", "content", "hi", "speakerName", "Agent A",
                "targetAgentId", "agent-b", "keyVersion", 3), envelope.getValue().payload());
    }

    // =================================================================
    // visibleTo — who has to verify, and what reaches them
    // =================================================================

    @Test
    void visibleTo_nullAgentStore_transcriptUnchanged() {
        var transcript = List.of(entry(AGENT_A, "hi"));
        assertSame(transcript, new GroupSigningGuard(null, agentSigningService, TENANT).visibleTo(RECEIVER, gc(), transcript));
    }

    @Test
    void visibleTo_receiverWithoutCurrentVersion_transcriptUnchangedAndConfigNotRead() throws Exception {
        when(agentStore.getCurrentResourceId(RECEIVER)).thenReturn(null);
        var transcript = List.of(entry(AGENT_A, "hi"));

        assertSame(transcript, guard().visibleTo(RECEIVER, gc(), transcript));
        // any(), not anyInt(): read(String, Integer) takes a boxed Integer, and
        // anyInt()
        // does not match null, so read(id, null) would slip past an anyInt() verify.
        verify(agentStore, never()).read(eq(RECEIVER), any());
    }

    @Test
    void visibleTo_receiverIsNoAgent_transcriptUnchanged() throws Exception {
        when(agentStore.getCurrentResourceId(RECEIVER)).thenThrow(new IResourceStore.ResourceNotFoundException("no such agent"));
        var transcript = List.of(entry(AGENT_A, "hi"));

        assertSame(transcript, guard().visibleTo(RECEIVER, gc(), transcript));
    }

    @Test
    void visibleTo_receiverDoesNotRequireVerification_transcriptUnchangedAndNothingVerified() throws Exception {
        stubAgent(RECEIVER, new AgentConfiguration());
        var transcript = List.of(entry(AGENT_A, "hi"));

        assertSame(transcript, guard().visibleTo(RECEIVER, gc(), transcript));
        verifyNoInteractions(agentSigningService);
    }

    /**
     * Fail-closed: when the receiver's own configuration cannot be read, it is
     * treated as requiring verification, and an entry that cannot be verified is
     * withheld rather than passed through.
     */
    @Test
    void visibleTo_receiverConfigUnreadable_failsClosed() throws Exception {
        when(agentStore.getCurrentResourceId(RECEIVER)).thenThrow(new RuntimeException("store down"));
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        var visible = guard().visibleTo(RECEIVER, gc(), List.of(entry(AGENT_A, "hi")));

        assertTrue(GroupSigningGuard.isWithheld(visible.get(0)));
    }

    /**
     * During a rotation overlap both keys are valid; each entry must be verified
     * against the version it declares, not both against the newest.
     */
    @Test
    void visibleTo_keyRotationOverlap_eachEntryVerifiedWithItsOwnKeyVersion() throws Exception {
        stubAgent(RECEIVER, verifyingAgent());
        var speaker = new AgentConfiguration();
        speaker.setIdentity(identity(new AgentPublicKey(1, KEY_V1_B64, 0L, 0L), new AgentPublicKey(2, KEY_V2_B64, 0L, 0L)));
        stubAgent(AGENT_A, speaker);
        when(agentSigningService.verifyEnvelope(any(SignedEnvelope.class), anyString())).thenReturn(true);
        long now = Instant.now().toEpochMilli();
        var transcript = List.of(signedEntry("hello", "nonce-1", now, 1), signedEntry("world", "nonce-2", now, 2));

        var visible = guard().visibleTo(RECEIVER, gc(), transcript);

        assertEquals(transcript, visible);
        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentSigningService, times(2)).verifyEnvelope(any(SignedEnvelope.class), keyCaptor.capture());
        assertEquals(List.of(KEY_V1_B64, KEY_V2_B64), keyCaptor.getAllValues());
    }

    /**
     * Only agents sign. The user's question, a reviewer's edit and a human member's
     * turn (speakers the agent store does not know) pass unsigned; an unsigned
     * entry from an agent, even one named like a reviewer, is withheld.
     */
    @Test
    void visibleTo_onlyAgentAuthoredEntriesMustVerify() throws Exception {
        stubAgent(RECEIVER, verifyingAgent());
        when(agentStore.getCurrentResourceId("hitl:bob")).thenThrow(new IResourceStore.ResourceNotFoundException("not an agent"));
        when(agentStore.getCurrentResourceId("alice")).thenThrow(new IResourceStore.ResourceNotFoundException("not an agent"));
        stubAgent("hitl:agent", new AgentConfiguration());
        var question = new TranscriptEntry("user", "User", "the question", 0, "Question", TranscriptEntryType.QUESTION, Instant.now(),
                null, null);
        var reviewerEdit = new TranscriptEntry("hitl:bob", "Reviewer", "edited", 1, "P", TranscriptEntryType.SYNTHESIS, Instant.now(),
                GroupReviewEditor.REVIEWER_EDIT_MARKER, null);
        var human = new TranscriptEntry("alice", "Alice", "my view", 1, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
        var lookalike = new TranscriptEntry("hitl:agent", "Agent", "hello", 1, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);

        var visible = guard().visibleTo(RECEIVER, gc(), List.of(question, reviewerEdit, human, lookalike));

        assertEquals(List.of(question, reviewerEdit, human), visible.subList(0, 3));
        assertTrue(GroupSigningGuard.isWithheld(visible.get(3)));
        verify(agentStore, never()).getCurrentResourceId("user");
    }

    /**
     * The placeholder keeps the entry's place, speaker, phase and type (window
     * summaries are keyed on raw indices) and says why, but not what was said.
     */
    @Test
    void visibleTo_withheldEntryKeepsItsPlaceButNotItsContent() throws Exception {
        stubAgent(RECEIVER, verifyingAgent());
        stubAgent(AGENT_A, new AgentConfiguration());
        var unsigned = new TranscriptEntry(AGENT_A, "Agent A", "secret plan", 2, "Critique", TranscriptEntryType.CRITIQUE, Instant.now(),
                null, "agent-b");

        var placeholder = guard().visibleTo(RECEIVER, gc(), List.of(unsigned)).get(0);

        assertEquals(AGENT_A, placeholder.speakerAgentId());
        assertEquals("Agent A", placeholder.speakerDisplayName());
        assertEquals(2, placeholder.phaseIndex());
        assertEquals("Critique", placeholder.phaseName());
        assertEquals(TranscriptEntryType.CRITIQUE, placeholder.type());
        assertEquals("agent-b", placeholder.targetAgentId());
        assertEquals(GroupSigningGuard.WITHHELD_MARKER, placeholder.errorReason());
        assertFalse(placeholder.content().contains("secret plan"));
        assertTrue(placeholder.content().contains("carries no signature"));
        assertNull(placeholder.signature());
    }

    @Test
    void forgetConversation_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> guard().forgetConversation("never-registered"));
    }

    @Test
    void versionZeroResolvesToTheLegacyKey_evenAfterAVersionedListIsAdded() {
        // Entries signed before key versioning carry signatureKeyVersion 0. Once an
        // operator onboards a keys list starting at v1 — the normal rotation path —
        // a version-exact lookup returned null for every one of them, and peer
        // verification logged authentic entries as unverifiable. Version 0 MEANS
        // "signed against the legacy publicKey field", so that field is its key.
        var identity = new AgentIdentity();
        identity.setPublicKey("legacy-key");

        assertEquals("legacy-key", identity.getKeyForVersion(0), "no versioned keys yet");

        identity.setKeys(List.of(new AgentPublicKey(1, "v1-key", 0L, Long.MAX_VALUE)));

        assertEquals("legacy-key", identity.getKeyForVersion(0),
                "a pre-rotation entry must still verify after the keys list is onboarded");
        assertEquals("v1-key", identity.getKeyForVersion(1), "versioned lookup is unaffected");
        assertNull(identity.getKeyForVersion(2), "an unknown version is still unknown");
    }

    private void stubAgent(String agentId, AgentConfiguration config) throws Exception {
        var resourceId = resourceId(agentId);
        when(agentStore.getCurrentResourceId(agentId)).thenReturn(resourceId);
        when(agentStore.read(agentId, resourceId.getVersion())).thenReturn(config);
    }

    private static AgentConfiguration signingAgent(AgentIdentity identity) {
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);
        config.setIdentity(identity);
        return config;
    }

    private static AgentConfiguration verifyingAgent() {
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setRequirePeerVerification(true);
        config.setSecurity(security);
        return config;
    }

    private static AgentIdentity identity(AgentPublicKey... keys) {
        var identity = new AgentIdentity();
        identity.setKeys(List.of(keys));
        return identity;
    }

    private static TranscriptEntry entry(String speaker, String content) {
        return new TranscriptEntry(speaker, "Agent A", content, 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    private static TranscriptEntry signedEntry(String content, String nonce, long signedAt, int keyVersion) {
        return new TranscriptEntry(AGENT_A, "Agent A", content, 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null,
                "sig-" + nonce, nonce, signedAt, keyVersion);
    }

    private static GroupConversation gc() {
        var g = new GroupConversation();
        g.setId("gc-1");
        g.setGroupId(GROUP_ID);
        return g;
    }

    private static IResourceStore.IResourceId resourceId(String id) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }
}
