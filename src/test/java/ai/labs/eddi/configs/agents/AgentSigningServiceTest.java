/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        public boolean isAvailable() {
            return true;
        }
    }
}
