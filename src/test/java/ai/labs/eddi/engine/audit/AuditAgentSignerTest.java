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
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.SecurityConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditAgentSigner.Verdict;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.ISecretProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuditAgentSigner} with real Ed25519 keys over an in-memory vault, and
 * the agent's versions from a stubbed store read through the real
 * {@link AgentConfigurationCache}: sign, verify, and the ways a stored
 * signature can be wrong.
 */
class AuditAgentSignerTest {

    private static final String TENANT = "default";
    private static final String AGENT = "agent-1";
    private static final String HMAC = "v5:key-id:0123456789abcdef";

    private ISecretProvider vault;
    private AgentSigningService signingService;
    private IAgentStore agentStore;
    private AuditAgentSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        vault = SigningTestSupport.inMemoryVault();
        signingService = SigningTestSupport.signingService(vault, new SimpleMeterRegistry());
        agentStore = mock(IAgentStore.class);
        signer = new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore), agentStore);
    }

    /** {@code version} is {@link #AGENT}'s current version. */
    private void currentVersion(int version) throws Exception {
        when(agentStore.getCurrentResourceId(AGENT)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return AGENT;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        });
    }

    /** Stores {@code config} as version {@code version} of {@link #AGENT}. */
    private void agentVersion(int version, AgentConfiguration config) throws Exception {
        when(agentStore.read(AGENT, version)).thenReturn(config);
        when(agentStore.readIncludingDeleted(AGENT, version)).thenReturn(config);
    }

    /** An agent version that signs, declaring {@code keys}. */
    private static AgentConfiguration signing(List<AgentPublicKey> keys) {
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);
        var identity = new AgentIdentity();
        identity.setKeys(new ArrayList<>(keys));
        config.setIdentity(identity);
        return config;
    }

    private AgentPublicKey generate(int version) throws Exception {
        var generated = signingService.generateKey(TENANT, AGENT, version);
        return new AgentPublicKey(generated.version(), generated.publicKeyB64(), 0, 0);
    }

    private static AuditEntry entry(int agentVersion, String hmac) {
        return entry(agentVersion, hmac, Instant.EPOCH);
    }

    private static AuditEntry entry(int agentVersion, String hmac, Instant timestamp) {
        return new AuditEntry("entry-1", "conv-1", AGENT, agentVersion, "user-1", "production", 0, "task", "llm", 0, 10L,
                Map.of("input", "hi"), Map.of("output", "hello"), null, null, List.of(), 0.0, timestamp, hmac, null, 0L);
    }

    /** What a holder of key {@code keyVersion}'s private half writes into a row. */
    private String signatureBy(int keyVersion, String hmac) throws Exception {
        return "v" + keyVersion + ":" + signingService.sign(TENANT, AGENT, keyVersion, AuditAgentSigner.SIGNED_PREFIX + hmac);
    }

    @Test
    void signsWithTheNewestKeyTheAgentVersionDeclares_andTheSignatureVerifies() throws Exception {
        AgentPublicKey v2 = generate(2);
        agentVersion(2, signing(List.of(generate(1), v2)));

        AuditEntry signed = signer.sign(TENANT, entry(2, HMAC));

        assertNotNull(signed.agentSignature());
        assertTrue(signed.agentSignature().startsWith("v2:"), signed.agentSignature());
        assertEquals(Verdict.VALID, signer.verify(signed));
    }

    /**
     * The stored form an external verifier needs: {@code v<n>:} and an Ed25519
     * signature over {@code eddi-audit-entry:} followed by the HMAC.
     */
    @Test
    void theSignedBytesAreTheDomainPrefixAndTheHmac() throws Exception {
        AgentPublicKey v1 = generate(1);
        agentVersion(1, signing(List.of(v1)));

        String stored = signer.sign(TENANT, entry(1, HMAC)).agentSignature();

        assertTrue(signingService.verify(v1.publicKeyB64(), "eddi-audit-entry:" + HMAC, stored.substring("v1:".length())));
        assertFalse(signingService.verify(v1.publicKeyB64(), HMAC, stored.substring("v1:".length())),
                "without the prefix an audit signature would also be a signature over a bare string");
    }

    @Test
    void anEntryStillVerifiesAfterItsAgentRotatedItsKey() throws Exception {
        AgentPublicKey v1 = generate(1);
        agentVersion(1, signing(List.of(v1)));
        AuditEntry signedBeforeRotation = signer.sign(TENANT, entry(1, HMAC));
        agentVersion(2, signing(List.of(v1, generate(2))));
        currentVersion(2);

        assertTrue(signedBeforeRotation.agentSignature().startsWith("v1:"));
        assertEquals(Verdict.VALID, signer.verify(signedBeforeRotation));
        assertTrue(signer.sign(TENANT, entry(2, HMAC)).agentSignature().startsWith("v2:"));
    }

    @Test
    void anAgentWithSigningOffDoesNotSignAndCostsNoVaultRead() throws Exception {
        AgentConfiguration config = signing(List.of(generate(1)));
        config.getSecurity().setSignInterAgentMessages(false);
        agentVersion(1, config);

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
        verify(vault, never()).resolve(any());
    }

    @Test
    void anAgentThatDeclaresNoKeyDoesNotSign() throws Exception {
        agentVersion(1, signing(List.of()));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
        verify(vault, never()).resolve(any());
    }

    /** A signature over an entry id alone would prove nothing about its content. */
    @Test
    void anEntryWithoutAnHmacIsNotSigned() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));

        assertNull(signer.sign(TENANT, entry(1, null)).agentSignature());
    }

    @Test
    void aKeyMissingFromTheVaultLeavesTheEntryUnsigned() throws Exception {
        AgentPublicKey v1 = generate(1);
        signingService.discardKey(TENANT, AGENT, 1);
        agentVersion(1, signing(List.of(v1)));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
    }

    @Test
    void anUnreadableAgentVersionLeavesTheEntryUnsigned() throws Exception {
        when(agentStore.readIncludingDeleted(AGENT, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
    }

    @Test
    void anUnsignedEntryOfAnAgentThatDoesNotSignIsNotSigned() throws Exception {
        AgentConfiguration config = signing(List.of(generate(1)));
        config.getSecurity().setSignInterAgentMessages(false);
        agentVersion(1, config);

        assertEquals(Verdict.NOT_SIGNED, signer.verify(entry(1, HMAC)));
    }

    /**
     * Threat 7: the HMAC is computed before the agent signs and cannot cover the
     * signature, so a row stripped of it still passes its HMAC. Its agent version
     * says it should carry one, which is what gives the removal away.
     */
    @Test
    void aSignatureMissingFromAnEntryItsAgentVersionWouldHaveSignedIsMissing() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        assertNotNull(signed.agentSignature());

        assertEquals(Verdict.MISSING, signer.verify(signed.withAgentSignature(null)));
    }

    /** No HMAC, nothing to sign: the ledger never signs such an entry. */
    @Test
    void anUnsignedEntryWithoutAnHmacIsNotSigned() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));

        assertEquals(Verdict.NOT_SIGNED, signer.verify(entry(1, null)));
    }

    /**
     * A permanently deleted agent's unsigned entries: nothing says they should
     * carry a signature any more, and nothing about them is wrong.
     */
    @Test
    void anUnsignedEntryOfAPermanentlyDeletedAgentIsNotSigned() throws Exception {
        when(agentStore.readIncludingDeleted(AGENT, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));

        assertEquals(Verdict.NOT_SIGNED, signer.verify(entry(1, HMAC)));
    }

    /** The signature covers the HMAC, so an edited HMAC breaks it. */
    @Test
    void aSignatureOverAnotherHmacIsInvalid() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));

        assertEquals(Verdict.INVALID, signer.verify(signed.withHmac("v5:key-id:fedcba9876543210")));
    }

    @Test
    void aSignatureMadeWithAnotherKeyIsInvalid() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        String forged = signingService.sign(TENANT, AGENT, generate(7).version(), AuditAgentSigner.SIGNED_PREFIX + HMAC);

        assertEquals(Verdict.INVALID, signer.verify(entry(1, HMAC).withAgentSignature("v1:" + forged)));
    }

    /** Rewriting the version names another key, which then does not verify. */
    @Test
    void aRewrittenKeyVersionIsInvalid() throws Exception {
        agentVersion(2, signing(List.of(generate(1), generate(2))));
        AuditEntry signed = signer.sign(TENANT, entry(2, HMAC));

        assertEquals(Verdict.INVALID, signer.verify(signed.withAgentSignature(signed.agentSignature().replaceFirst("^v2:", "v1:"))));
    }

    @Test
    void aKeyVersionTheAgentVersionNeverDeclaredIsInvalid() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));

        assertEquals(Verdict.INVALID, signer.verify(signed.withAgentSignature(signed.agentSignature().replaceFirst("^v1:", "v9:"))));
    }

    @Test
    void aMalformedSignatureIsInvalid() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        String bare = signed.agentSignature().substring("v1:".length());

        assertEquals(Verdict.INVALID, signer.verify(signed.withAgentSignature(bare)));
        assertEquals(Verdict.INVALID, signer.verify(signed.withAgentSignature("v1:not base64!")));
    }

    /**
     * The trust anchor is the agent version that produced the entry. A later
     * version that swaps the key cannot make a signature by the substitute verify,
     * nor turn an honest one invalid: the honest one is merely no longer vouched
     * for, because the agent stopped declaring the key that made it.
     */
    @Test
    void aKeyReplacedInALaterAgentVersionCannotMakeAForgeryVerify() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        AgentPublicKey substitute = generate(5);
        agentVersion(2, signing(List.of(new AgentPublicKey(1, substitute.publicKeyB64(), 0, 0))));
        currentVersion(2);
        String bySubstitute = "v1:" + signingService.sign(TENANT, AGENT, 5, AuditAgentSigner.SIGNED_PREFIX + HMAC);

        assertEquals(Verdict.INVALID, signer.verify(signed.withAgentSignature(bySubstitute)));
        assertEquals(Verdict.KEY_REVOKED, signer.verify(signed));
    }

    /**
     * A soft delete flags the agent's current version and leaves it in the store
     * with the public key it declared, so its entries still verify. Read the way a
     * running agent is read, that version is gone and every entry it signed would
     * report UNKNOWN_KEY, while the agent's older versions still verified.
     */
    @Test
    void anEntryOfASoftDeletedAgentStillVerifies() throws Exception {
        AgentConfiguration config = signing(List.of(generate(1)));
        agentVersion(1, config);
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        IAgentStore afterSoftDelete = mock(IAgentStore.class);
        when(afterSoftDelete.read(AGENT, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));
        when(afterSoftDelete.readIncludingDeleted(AGENT, 1)).thenReturn(config);

        assertEquals(Verdict.VALID,
                new AuditAgentSigner(signingService, new AgentConfigurationCache(afterSoftDelete), afterSoftDelete).verify(signed));
    }

    /** Permanently deleted: the version, and the key it declared, are gone. */
    @Test
    void anAgentVersionThatNoLongerExistsLeavesTheSignatureUncheckable() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        IAgentStore deleted = mock(IAgentStore.class, invocation -> {
            throw new IResourceStore.ResourceNotFoundException("deleted");
        });
        var deletedSigner = new AuditAgentSigner(signingService, new AgentConfigurationCache(deleted), deleted);

        assertEquals(Verdict.KEY_UNAVAILABLE, deletedSigner.verify(signed));
    }

    /** A store outage fails the sweep rather than reporting a key as missing. */
    @Test
    void anAgentStoreFailureFailsTheVerification() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        IAgentStore failing = mock(IAgentStore.class, invocation -> {
            throw new IResourceStore.ResourceStoreException("store down");
        });
        var failingSigner = new AuditAgentSigner(signingService, new AgentConfigurationCache(failing), failing);

        assertThrows(IllegalStateException.class, () -> failingSigner.verify(signed));
    }

    // ---- what the ledger signs with ----

    /**
     * crypto-05: an agent version saved before keys were engine-owned may declare a
     * hand-typed public key that the vault entry of that version does not match.
     * Signing anyway wrote a signature its own public key reports as forged.
     */
    @Test
    void aVaultKeyThatDoesNotMatchTheDeclaredPublicKeyLeavesTheEntryUnsigned() throws Exception {
        generate(1);
        AgentPublicKey handTyped = new AgentPublicKey(1, generate(2).publicKeyB64(), 0, 0);
        agentVersion(1, signing(List.of(handTyped)));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
    }

    /**
     * An unchecked store failure (a driver timeout) must not escape: the ledger has
     * already taken the entry's chain position, and an exception here lost the
     * entry and left a gap that verification reports as a deletion.
     */
    @Test
    void anUncheckedStoreFailureLeavesTheEntryUnsigned() throws Exception {
        when(agentStore.readIncludingDeleted(AGENT, 1)).thenThrow(new IllegalStateException("socket timeout"));

        AuditEntry entry = entry(1, HMAC);
        assertSame(entry, signer.sign(TENANT, entry));
    }

    @Test
    void aRevokedNewestKeyDoesNotSign() throws Exception {
        agentVersion(1, signing(List.of(generate(1).revoke())));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
    }

    /**
     * The window is judged at the entry's own timestamp, which the HMAC covers, and
     * an entry the key could not sign is not one that should carry a signature.
     */
    @Test
    void aNewestKeyOutsideItsWindowAtTheEntrysTimeDoesNotSign() throws Exception {
        AgentPublicKey v1 = generate(1);
        agentVersion(1, signing(List.of(new AgentPublicKey(1, v1.publicKeyB64(), 1_000, 2_000))));

        AuditEntry before = entry(1, HMAC, Instant.ofEpochMilli(999));
        AuditEntry after = entry(1, HMAC, Instant.ofEpochMilli(2_001));
        assertNull(signer.sign(TENANT, before).agentSignature());
        assertNull(signer.sign(TENANT, after).agentSignature());
        assertEquals(Verdict.NOT_SIGNED, signer.verify(before));
        assertEquals(Verdict.NOT_SIGNED, signer.verify(after));
        assertTrue(signer.sign(TENANT, entry(1, HMAC, Instant.ofEpochMilli(1_500))).agentSignature().startsWith("v1:"));
    }

    /**
     * The ledger never signs outside the window, so such a signature is not its
     * own.
     */
    @Test
    void aSignatureMadeOutsideItsKeysWindowIsInvalid() throws Exception {
        AgentPublicKey v1 = generate(1);
        agentVersion(1, signing(List.of(new AgentPublicKey(1, v1.publicKeyB64(), 1_000, 2_000))));

        assertEquals(Verdict.INVALID, signer.verify(entry(1, HMAC, Instant.ofEpochMilli(2_001)).withAgentSignature(signatureBy(1, HMAC))));
    }

    /** The ledger signs with the version's newest key only. */
    @Test
    void aSignatureByAnOlderKeyOfTheAgentVersionIsInvalid() throws Exception {
        agentVersion(2, signing(List.of(generate(1), generate(2))));

        assertEquals(Verdict.INVALID, signer.verify(entry(2, HMAC).withAgentSignature(signatureBy(1, HMAC))));
    }

    /** Nor does it sign for an agent version with signing off. */
    @Test
    void aSignatureOnAnEntryOfAnAgentVersionWithSigningOffIsInvalid() throws Exception {
        AgentConfiguration config = signing(List.of(generate(1)));
        config.getSecurity().setSignInterAgentMessages(false);
        agentVersion(1, config);

        assertEquals(Verdict.INVALID, signer.verify(entry(1, HMAC).withAgentSignature(signatureBy(1, HMAC))));
    }

    /** Every version the engine can generate fits the stored form. */
    @Test
    void theHighestKeyVersionSignsAndVerifies() throws Exception {
        agentVersion(1, signing(List.of(generate(AgentPublicKey.MAX_VERSION))));

        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));

        assertTrue(signed.agentSignature().startsWith("v" + AgentPublicKey.MAX_VERSION + ":"), signed.agentSignature());
        assertEquals(Verdict.VALID, signer.verify(signed));
    }

    @Test
    void aKeyVersionWrittenInAnotherFormIsInvalid() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        String signature = signer.sign(TENANT, entry(1, HMAC)).agentSignature().substring("v1:".length());

        assertEquals(Verdict.INVALID, signer.verify(entry(1, HMAC).withAgentSignature("v01:" + signature)));
        assertEquals(Verdict.INVALID,
                signer.verify(entry(1, HMAC).withAgentSignature("v" + (AgentPublicKey.MAX_VERSION + 1) + ":" + signature)));
    }

    // ---- revocation ----

    /**
     * A revoked key is trusted for nothing, whenever it signed. Revoking writes a
     * new agent version, which the entry's own version never shows, so the agent's
     * current version is asked as well.
     */
    @Test
    void aKeyRevokedSinceNoLongerVouchesForTheEntry() throws Exception {
        AgentPublicKey v1 = generate(1);
        agentVersion(1, signing(List.of(v1)));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        agentVersion(2, signing(List.of(v1.revoke())));
        currentVersion(2);

        assertEquals(Verdict.KEY_REVOKED, signer.verify(signed));
        assertEquals(Verdict.KEY_REVOKED, signer.verify(signed.withAgentSignature(null)),
                "an unsigned entry of that key is not reported missing: no signature by it could vouch for anything");
    }

    /**
     * Revoking the legacy key removes it, so the current version no longer declares
     * it.
     */
    @Test
    void aLegacyKeyRemovedSinceNoLongerVouchesForTheEntry() throws Exception {
        AgentPublicKey legacy = generate(3);
        AgentConfiguration legacyVersion = signing(List.of());
        legacyVersion.getIdentity().setPublicKey(legacy.publicKeyB64());
        agentVersion(1, legacyVersion);
        AuditEntry legacySigned = entry(1, HMAC).withAgentSignature(
                "v0:" + signingService.sign(TENANT, AGENT, 3, AuditAgentSigner.SIGNED_PREFIX + HMAC));
        assertEquals(Verdict.VALID, signer.verify(legacySigned));

        agentVersion(2, signing(List.of()));
        currentVersion(2);

        assertEquals(Verdict.KEY_REVOKED, signer.verify(legacySigned));
    }

    /**
     * A deleted agent has no current version: its entries are judged by their own.
     */
    @Test
    void anAgentWithoutACurrentVersionIsJudgedByTheEntrysOwnVersion() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        when(agentStore.getCurrentResourceId(AGENT)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));

        assertEquals(Verdict.VALID, signer.verify(signed));
    }
}
