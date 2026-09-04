/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * A rotated agent has no legacy unversioned key, and the vault answers
     * {@code SecretNotFoundException} for it. That exception used to escape into
     * the method-wide catch, so the versioned scan below it never ran at all and
     * every rotated key stayed in the vault forever — the exact leak this method
     * exists to prevent, on the agents most likely to have one.
     */
    @Test
    void deleteKeyPair_deletesVersionedKeysWhenNoLegacyKeyExists() throws Exception {
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 2);

        signingService.deleteKeyPair("tenant-1", "agent-1");

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"),
                "v1 was left in the vault: the absent legacy key aborted the versioned scan");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"),
                "v2 was left in the vault: the absent legacy key aborted the versioned scan");
    }

    /**
     * {@code rotateKey} takes an arbitrary version number from its caller, so
     * versions can be sparse. Stopping the scan at the first missing version left
     * every key past the gap behind.
     */
    @Test
    void deleteKeyPair_deletesVersionsPastAGap() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 3);

        signingService.deleteKeyPair("tenant-1", "agent-1");

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v3"),
                "v3 was left in the vault: the scan stopped at the missing v2");
    }

    /**
     * The scan is deliberately exhaustive rather than stopping at the first gap, so
     * the cost of one permanent agent delete is fixed and worth pinning: the legacy
     * key plus every version in {@code 1..MAX_KEY_VERSION_SCAN}, once each. An
     * early {@code break} leaks the keys past a rotation gap; a wider or unbounded
     * loop turns one delete into an unbounded number of vault round trips.
     */
    @Test
    void deleteKeyPair_scansTheLegacyKeyAndTheWholeVersionRangeExactlyOnce() {
        signingService.deleteKeyPair("tenant-1", "agent-1");

        assertEquals(AgentSigningService.MAX_KEY_VERSION_SCAN + 1, secretProvider.deleteAttempts.size(),
                "one delete per version, plus the legacy key; attempted: " + secretProvider.deleteAttempts.size());
        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1"), "the legacy key must be attempted");
        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1:v1"));
        assertTrue(
                secretProvider.deleteAttempts
                        .contains("tenant-1:agent-signing-key:agent-1:v" + AgentSigningService.MAX_KEY_VERSION_SCAN),
                "the last version in the documented range must be attempted");
        assertFalse(
                secretProvider.deleteAttempts
                        .contains("tenant-1:agent-signing-key:agent-1:v" + (AgentSigningService.MAX_KEY_VERSION_SCAN + 1)),
                "the scan must stay inside its documented bound");
    }

    /**
     * "Absent" and "vault unreachable" are different, and only the first is
     * expected. A vault error on one key must be reported and stepped over, not
     * abandon the keys after it — a failure mid-scan is the same leak this method
     * exists to prevent, wearing a success message.
     */
    @Test
    void deleteKeyPair_continuesPastAVaultFailureOnOneKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 2);
        // The vault answers for everything except the legacy key, which is the first
        // thing the scan touches.
        secretProvider.failDeleteFor("tenant-1:agent-signing-key:agent-1");

        assertDoesNotThrow(() -> signingService.deleteKeyPair("tenant-1", "agent-1"));

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"),
                "a vault error on an earlier key must not abandon the rest of the scan");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"),
                "a vault error on an earlier key must not abandon the rest of the scan");
    }

    // ==================== sign error path with SecretNotFoundException
    // ====================

    /**
     * The envelope path and the plain path used to report the identical failure
     * differently: {@code sign} said "No signing key found", {@code signEnvelope}
     * said "Envelope signing failed … InvalidKeySpecException". Only the exception
     * TYPE was asserted, so the two were free to drift apart again.
     */
    @Test
    void signEnvelope_reportsAMissingKeyTheSameWaySignDoes() {
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("data", "test"));

        var envelopeFailure = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.signEnvelope("t1", "nonexistent", envelope, 0));
        var plainFailure = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("t1", "nonexistent", "payload"));

        assertEquals("No signing key found for agent nonexistent", envelopeFailure.getMessage());
        assertEquals(plainFailure.getMessage(), envelopeFailure.getMessage(), "the two signing paths must not drift apart again");
        assertInstanceOf(ISecretProvider.SecretNotFoundException.class, envelopeFailure.getCause(),
                "the original cause must survive the unwrapping");
    }

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

        /** Every key a delete was attempted for, in order — for pinning the scan. */
        private final List<String> deleteAttempts = new ArrayList<>();

        /**
         * Keys whose delete reports the vault as unreachable, not the key as absent.
         */
        private final Set<String> unreachable = new HashSet<>();

        /** Whether a key is still in the vault — for asserting what a delete left. */
        boolean contains(String tenantId, String keyName) {
            return store.containsKey(tenantId + ":" + keyName);
        }

        /** Makes {@code delete} answer {@code SecretProviderException} for this key. */
        void failDeleteFor(String fullKey) {
            unreachable.add(fullKey);
        }

        /**
         * Reversible obfuscation, not encryption — this is a test double, and the
         * property under test is that seal/unseal round-trips, not that it is strong.
         * Prefixed so a test asserting "the stored value is not the plaintext" can see
         * the difference.
         */
        @Override
        public SealedValue seal(String tenantId, String plaintext) {
            return plaintext == null ? null : new SealedValue("sealed:" + tenantId + ":" + plaintext, "test-iv");
        }

        @Override
        public String unseal(String tenantId, SealedValue sealed) throws SecretProviderException {
            if (sealed == null || sealed.ciphertext() == null) {
                return null;
            }
            String prefix = "sealed:" + tenantId + ":";
            if (!sealed.ciphertext().startsWith(prefix)) {
                throw new SecretProviderException("Sealed with a different tenant key");
            }
            return sealed.ciphertext().substring(prefix.length());
        }

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
        public void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
            String key = reference.tenantId() + ":" + reference.keyName();
            deleteAttempts.add(key);
            if (unreachable.contains(key)) {
                throw new SecretProviderException("Vault unreachable for: " + key);
            }
            if (store.remove(key) == null) {
                throw new SecretNotFoundException("Secret not found: " + key);
            }
        }

        @Override
        public SecretMetadata getMetadata(SecretReference reference) {
            return null;
        }

        @Override
        public List<SecretMetadata> listKeys(String tenantId) {
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
