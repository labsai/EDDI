/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class AgentSigningServiceTest {

    private AgentSigningService signingService;
    private InMemorySecretProvider secretProvider;

    @BeforeEach
    void setUp() {
        secretProvider = new InMemorySecretProvider();
        signingService = new AgentSigningService(secretProvider, new SimpleMeterRegistry());
        signingService.initMetrics();
    }

    @Test
    void generateKeyPair_returnsPublicKey() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        assertNotNull(publicKey);
        assertFalse(publicKey.isBlank());
    }

    @Test
    void sign_and_verify_roundTrip() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        String payload = "Hello from Agent-1";

        String signature = signingService.sign("tenant-1", "agent-1", payload);
        assertNotNull(signature);

        assertTrue(signingService.verify(publicKey, payload, signature));
    }

    @Test
    void verify_failsOnTamperedPayload() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        String signature = signingService.sign("tenant-1", "agent-1", "original message");

        assertFalse(signingService.verify(publicKey, "tampered message", signature));
    }

    @Test
    void verify_failsOnWrongKey() throws Exception {
        String publicKey1 = signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPair("tenant-1", "agent-2");

        String signature = signingService.sign("tenant-1", "agent-2", "message");

        // Verify with agent-1's public key should fail
        assertFalse(signingService.verify(publicKey1, "message", signature));
    }

    @Test
    void deleteKeyPair_removesKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.deleteKeyPair("tenant-1", "agent-1");

        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("tenant-1", "agent-1", "payload"));
    }

    @Test
    void deleteKeyPair_nonExistent_doesNotThrow() {
        // Should log a warning but not throw
        assertDoesNotThrow(() -> signingService.deleteKeyPair("tenant-1", "nonexistent"));
    }

    @Test
    void verify_returnsFalseOnInvalidBase64() {
        assertFalse(signingService.verify("not-a-key", "payload", "not-a-sig"));
    }

    @Test
    void generateKeyPair_throwsWhenVaultStoreFailsOnGenerate() {
        // Use a provider whose store() always throws
        var failingProvider = new InMemorySecretProvider() {
            @Override
            public void store(SecretReference reference, String plaintext,
                              String description, List<String> allowedAgents)
                    throws SecretProviderException {
                throw new SecretProviderException("Vault unavailable");
            }
        };
        var failService = new AgentSigningService(failingProvider,
                new SimpleMeterRegistry());
        failService.initMetrics();

        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> failService.generateKeyPair("t1", "a1"));
    }

    @Test
    void sign_throwsAgentSigningExceptionWhenKeyNotFound() {
        // Agent was never generated, so vault has no key
        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("t1", "nonexistent-agent", "payload"));
    }

    @Test
    void generateKeyPair_evictsCacheOnRegeneration() throws Exception {
        // Generate initial keypair and sign to populate the cache
        String publicKey1 = signingService.generateKeyPair("tenant-1", "agent-1");
        String sig1 = signingService.sign("tenant-1", "agent-1", "message");
        assertTrue(signingService.verify(publicKey1, "message", sig1));

        // Re-generate keypair (key rotation)
        String publicKey2 = signingService.generateKeyPair("tenant-1", "agent-1");

        // The new public key should be different
        assertNotEquals(publicKey1, publicKey2);

        // Signing should now use the NEW key (cache was evicted)
        String sig2 = signingService.sign("tenant-1", "agent-1", "message");

        // Verify with new public key should succeed
        assertTrue(signingService.verify(publicKey2, "message", sig2));

        // Verify with OLD public key should fail (proving new key is in use)
        assertFalse(signingService.verify(publicKey1, "message", sig2));
    }

    // ==================== Versioned Key Tests ====================

    @Test
    void generateKeyPairVersioned_returnsPublicKey() throws Exception {
        String publicKey = signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        assertNotNull(publicKey);
        assertFalse(publicKey.isBlank());
    }

    @Test
    void generateKeyPairVersioned_throwsForNonPositiveVersion() {
        var ex = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.generateKeyPairVersioned("t1", "a1", 0));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void generateKeyPairVersioned_throwsForNegativeVersion() {
        var ex = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.generateKeyPairVersioned("t1", "a1", -1));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void generateKeyPairVersioned_throwsWhenVaultFails() {
        var failingProvider = new InMemorySecretProvider() {
            @Override
            public void store(SecretReference reference, String plaintext,
                              String description, List<String> allowedAgents)
                    throws SecretProviderException {
                throw new SecretProviderException("Vault unavailable");
            }
        };
        var failService = new AgentSigningService(failingProvider, new SimpleMeterRegistry());
        failService.initMetrics();

        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> failService.generateKeyPairVersioned("t1", "a1", 1));
    }

    // ==================== rotateKey Tests ====================

    @Test
    void rotateKey_generatesNewVersionedKey() throws Exception {
        String publicKey = signingService.rotateKey("tenant-1", "agent-1", 2);
        assertNotNull(publicKey);
        assertFalse(publicKey.isBlank());
    }

    @Test
    void rotateKey_throwsForNonPositiveVersion() {
        var ex = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.rotateKey("t1", "a1", 0));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void rotateKey_throwsForNegativeVersion() {
        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.rotateKey("t1", "a1", -5));
    }

    // ==================== Envelope signing/verification ====================

    @Test
    void signEnvelope_andVerify_roundTrip() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("message", "hello"));

        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 0);

        assertNotNull(signed.signature());
        assertTrue(signingService.verifyEnvelope(signed, publicKey));
    }

    @Test
    void signEnvelope_withVersionedKey() throws Exception {
        String publicKey = signingService.generateKeyPairVersioned("tenant-1", "agent-1", 3);
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("data", "test"));

        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 3);

        assertNotNull(signed.signature());
        assertEquals(3, signed.keyVersion());
        assertTrue(signingService.verifyEnvelope(signed, publicKey));
    }

    @Test
    void signEnvelope_throwsWhenKeyNotFound() {
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("data", "test"));

        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.signEnvelope("t1", "nonexistent", envelope, 0));
    }

    @Test
    void verifyEnvelope_returnsFalseOnTamperedPayload() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("message", "original"));
        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 0);

        // Create a tampered envelope with different payload but same signature
        var tampered = new SignedEnvelope("agent-1", "agent-2",
                Map.of("message", "tampered"), signed.nonce(), signed.timestampMs(),
                signed.signature(), signed.keyVersion());

        assertFalse(signingService.verifyEnvelope(tampered, publicKey));
    }

    @Test
    void verifyEnvelope_returnsFalseOnInvalidPublicKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("msg", "test"));
        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 0);

        assertFalse(signingService.verifyEnvelope(signed, "invalid-key"));
    }

    // ==================== deleteKeyPair with versioned keys ====================

    @Test
    void deleteKeyPair_alsoDeletesVersionedKeys() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);

        signingService.deleteKeyPair("tenant-1", "agent-1");

        // Both legacy and versioned key should be gone
        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("tenant-1", "agent-1", "payload"));
    }

    // ==================== sign error path with SecretNotFoundException
    // ====================

    @Test
    void sign_throwsWithSecretNotFoundCauseMessage() {
        // Agent was never generated — SecretNotFoundException is the cause
        var ex = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("t1", "missing-agent", "payload"));
        assertTrue(ex.getMessage().contains("No signing key found") || ex.getMessage().contains("Failed to load"));
    }

    /**
     * Simple in-memory secret provider for testing.
     */
    private static class InMemorySecretProvider implements ISecretProvider {
        private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

        @Override
        public String resolve(SecretReference reference) throws SecretNotFoundException {
            String key = reference.tenantId() + ":" + reference.keyName();
            String value = store.get(key);
            if (value == null) {
                throw new SecretNotFoundException("Secret not found: " + key);
            }
            return value;
        }

        @Override
        public void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents)
                throws SecretProviderException {
            store.put(reference.tenantId() + ":" + reference.keyName(), plaintext);
        }

        @Override
        public void delete(SecretReference reference) throws SecretNotFoundException {
            String key = reference.tenantId() + ":" + reference.keyName();
            if (store.remove(key) == null) {
                throw new SecretNotFoundException("Secret not found: " + key);
            }
        }

        @Override
        public ai.labs.eddi.secrets.model.SecretMetadata getMetadata(SecretReference reference) {
            return null;
        }

        @Override
        public List<ai.labs.eddi.secrets.model.SecretMetadata> listKeys(String tenantId) {
            return List.of();
        }

        @Override
        public int rotateDek(String tenantId) {
            return 0;
        }

        @Override
        public int resetTenant(String tenantId) {
            int before = store.size();
            store.entrySet().removeIf(e -> e.getKey().startsWith(tenantId + ":"));
            return before - store.size();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
