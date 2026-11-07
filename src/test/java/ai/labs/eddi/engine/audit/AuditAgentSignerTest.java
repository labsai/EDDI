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
        signer = new AuditAgentSigner(signingService, new AgentConfigurationCache(agentStore));
    }

    /** Stores {@code config} as version {@code version} of {@link #AGENT}. */
    private void agentVersion(int version, AgentConfiguration config) throws Exception {
        when(agentStore.read(AGENT, version)).thenReturn(config);
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
        return new AuditEntry("entry-1", "conv-1", AGENT, agentVersion, "user-1", "production", 0, "task", "llm", 0, 10L,
                Map.of("input", "hi"), Map.of("output", "hello"), null, null, List.of(), 0.0, Instant.EPOCH, hmac, null, 0L);
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
        when(agentStore.read(AGENT, 1)).thenThrow(new IResourceStore.ResourceStoreException("store down"));

        assertNull(signer.sign(TENANT, entry(1, HMAC)).agentSignature());
    }

    @Test
    void anEntryWithoutASignatureIsNotSigned() {
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
     * version that swaps the key cannot make a forged signature verify, nor turn an
     * honest one invalid.
     */
    @Test
    void aKeyReplacedInALaterAgentVersionDoesNotChangeTheVerdict() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        AgentPublicKey substitute = generate(5);
        agentVersion(2, signing(List.of(new AgentPublicKey(1, substitute.publicKeyB64(), 0, 0))));

        assertEquals(Verdict.VALID, signer.verify(signed));
    }

    @Test
    void anAgentVersionThatNoLongerExistsLeavesTheSignatureUncheckable() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        var deletedSigner = new AuditAgentSigner(signingService, new AgentConfigurationCache(mock(IAgentStore.class, invocation -> {
            throw new IResourceStore.ResourceNotFoundException("deleted");
        })));

        assertEquals(Verdict.KEY_UNAVAILABLE, deletedSigner.verify(signed));
    }

    /** A store outage fails the sweep rather than reporting a key as missing. */
    @Test
    void anAgentStoreFailureFailsTheVerification() throws Exception {
        agentVersion(1, signing(List.of(generate(1))));
        AuditEntry signed = signer.sign(TENANT, entry(1, HMAC));
        var failingSigner = new AuditAgentSigner(signingService, new AgentConfigurationCache(mock(IAgentStore.class, invocation -> {
            throw new IResourceStore.ResourceStoreException("store down");
        })));

        assertThrows(IllegalStateException.class, () -> failingSigner.verify(signed));
    }
}
