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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.LogCaptureSupport.FORGED_RECORD;
import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
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
     * The blind sweep is the FALLBACK, for callers that do not know which versions
     * exist. The agent's configuration lists them, and the caller has already read
     * it to decide whether there is anything to clean up at all — so an agent whose
     * highest declared version is 3 must cost four vault round trips, not 101. On a
     * vault-less dev instance those 101 were also 101 "may still hold private key
     * material" WARNs for versions that never existed.
     *
     * <p>
     * The declared versions BOUND the sweep; they do not enumerate it. See
     * {@link #deleteKeyPair_withKnownVersions_stillRemovesAnUndeclaredKeyBelowTheHighest}
     * for why.
     * </p>
     */
    @Test
    void deleteKeyPair_withKnownVersions_staysWithinTheHighestDeclaredVersion() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 3);

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of(3, 1));

        assertEquals(4, secretProvider.deleteAttempts.size(),
                "the legacy key plus 1..3, and nothing above the highest declared version; attempted: " + secretProvider.deleteAttempts);
        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1"));
        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1:v1"));
        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1:v3"));
        assertFalse(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1:v4"),
                "nothing above the highest declared version may be attempted — that is what keeps this off the 101-call path");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"));
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v3"));
    }

    /**
     * A vault key can exist at a version {@code identity.keys} does not list.
     * {@code rotateKey} writes the vault entry and returns; adding the version to
     * {@code keys[]} is a SEPARATE config write by the caller, which can fail after
     * the vault write succeeded — and {@code keys[]} is operator-editable JSON that
     * nothing prunes. Sweeping only the listed versions left that private key in
     * the vault forever after a permanent delete, while the tally reported "Deleted
     * N signing key(s)": the leak wearing a success message, on a narrower path
     * than the blanket-catch one the tally was written to close.
     */
    @Test
    void deleteKeyPair_withKnownVersions_stillRemovesAnUndeclaredKeyBelowTheHighest() throws Exception {
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        // v2 exists in the vault but the follow-up config write never landed, so
        // identity.keys names only v1 and v3.
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 2);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 3);

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of(1, 3));

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"),
                "v2 was left in the vault: a private key that keys[] does not list is still a private key");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"));
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v3"));
    }

    /**
     * The cap is on the RANGE, which is a guess, not on the declared versions,
     * which are known to exist. A stored version far above the cap must still be
     * attempted — otherwise the one key we are certain about is the one we skip —
     * while the range it implies stays bounded at {@code MAX_KEY_VERSION_SCAN}.
     */
    @Test
    void deleteKeyPair_withAVersionAboveTheCap_attemptsItWithoutUnboundingTheSweep() throws Exception {
        int aboveCap = AgentSigningService.MAX_KEY_VERSION_SCAN + 5;
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", aboveCap);

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of(aboveCap));

        assertTrue(secretProvider.deleteAttempts.contains("tenant-1:agent-signing-key:agent-1:v" + aboveCap),
                "a declared version is known to exist and must be attempted whatever the range cap says");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v" + aboveCap));
        assertEquals(AgentSigningService.MAX_KEY_VERSION_SCAN + 2, secretProvider.deleteAttempts.size(),
                "the legacy key, the capped range 1..MAX, and the one declared version above it; attempted: "
                        + secretProvider.deleteAttempts.size());
    }

    @Test
    void deleteKeyPair_withNoDeclaredVersions_stillRemovesTheLegacyKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of());

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1"));
    }

    /**
     * An empty declared-version list is not evidence that no versioned key exists,
     * so it bounds nothing and has to fall back to the blind sweep.
     *
     * <p>
     * {@code rotateKey} writes the vault entry and returns; adding the version to
     * {@code identity.keys} is a SEPARATE config write by the caller. A first
     * rotation whose config write failed therefore leaves v1 in the vault with
     * {@code keys[]} still empty — and an operator can prune {@code keys[]} by hand
     * to the same shape. Treating that as "no rotated versions, nothing to sweep"
     * left an Ed25519 private key in the vault after a PERMANENT delete, while the
     * tally reported a clean "Deleted 1 signing key(s)".
     * </p>
     */
    @Test
    void deleteKeyPair_withNoDeclaredVersions_stillRemovesAnUndeclaredRotatedKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        // The rotation reached the vault; the config write that would have added it to
        // identity.keys did not.
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of());

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"),
                "a rotated key that no keys[] entry names is exactly the private key material a permanent delete must not leave behind");
        assertEquals(AgentSigningService.MAX_KEY_VERSION_SCAN + 1, secretProvider.deleteAttempts.size(),
                "an empty list bounds nothing, so the whole documented range is swept; attempted: " + secretProvider.deleteAttempts.size());
    }

    @Test
    void deleteKeyPair_withNullVersions_fallsBackToTheBlindSweep() {
        signingService.deleteKeyPair("tenant-1", "agent-1", null);

        assertEquals(AgentSigningService.MAX_KEY_VERSION_SCAN + 1, secretProvider.deleteAttempts.size(),
                "null means the versions are unknown, so the whole documented range must still be swept");
    }

    /**
     * {@code identity.keys} is operator-editable JSON, so it can carry a null entry
     * or a zero/negative version. {@code rotateKey} rejects those, meaning they
     * name no vault entry at all — and letting one set the upper bound of the sweep
     * would either widen it needlessly or, for a null, throw out of a delete.
     */
    @Test
    void deleteKeyPair_ignoresNullAndNonPositiveDeclaredVersions() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 2);

        signingService.deleteKeyPair("tenant-1", "agent-1", Arrays.asList(null, 0, -3, 2));

        assertEquals(3, secretProvider.deleteAttempts.size(),
                "the legacy key plus versions 1..2 — nothing more; attempted: " + secretProvider.deleteAttempts);
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1"));
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"));
    }

    /**
     * A vault outage fails every key in the sweep. The sweep must not stop at the
     * first one — the keys it has not reached yet are exactly the private key
     * material this method exists to remove — and the tally must keep the FIRST
     * failure rather than being overwritten by whichever key was swept last.
     */
    @Test
    void deleteKeyPair_keepsSweepingAfterAVaultFailure() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 1);
        signingService.generateKeyPairVersioned("tenant-1", "agent-1", 2);
        secretProvider.failDeleteFor("tenant-1:agent-signing-key:agent-1");
        secretProvider.failDeleteFor("tenant-1:agent-signing-key:agent-1:v1");

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of(2));

        assertEquals(3, secretProvider.deleteAttempts.size(),
                "every key must still be attempted; attempted: " + secretProvider.deleteAttempts);
        assertTrue(secretProvider.contains("tenant-1", "agent-signing-key:agent-1"), "the failed delete left this key behind");
        assertTrue(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"), "the failed delete left this key behind");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"),
                "a vault outage on earlier keys must not stop the sweep");
    }

    /**
     * The private key is cached after the first load, so a second signature must
     * not go back to the vault. Pinned by removing the vault entry out from under
     * it: the cached key has to carry the second signature on its own.
     */
    @Test
    void sign_usesTheCachedPrivateKeyOnTheSecondCall() throws Exception {
        String publicKey = signingService.generateKeyPair("tenant-1", "agent-1");
        signingService.sign("tenant-1", "agent-1", "first");

        secretProvider.forget("tenant-1:agent-signing-key:agent-1");
        String second = signingService.sign("tenant-1", "agent-1", "second");

        assertTrue(signingService.verify(publicKey, "second", second),
                "the second signature must come from the cached key, not from a vault round trip");
    }

    /**
     * A vault delete that fails leaves the private key in the vault — but leaving
     * the loaded key in the in-process cache as well would let this node keep
     * SIGNING as the deleted agent from memory, for as long as it lives. That is
     * strictly worse than the vault entry the WARN is about, so the cache line goes
     * regardless of what the vault answered.
     */
    @Test
    void deleteKeyPair_evictsTheCacheEvenWhenTheVaultDeleteFails() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        // Populate the private-key cache.
        signingService.sign("tenant-1", "agent-1", "message");
        secretProvider.failDeleteFor("tenant-1:agent-signing-key:agent-1");

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of());

        // The key is still in the vault (the delete failed), so a cached entry would
        // otherwise keep signing working. Signing must now go back to the vault, and
        // this proves it did: the vault answer is removed out from under it.
        secretProvider.forget("tenant-1:agent-signing-key:agent-1");
        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("tenant-1", "agent-1", "message"),
                "the private key survived in the cache, so this process can still sign as a deleted agent");
    }

    /**
     * The closing line used to read "Deleted signing keys for agent … (cache
     * evicted)" unconditionally — after a vault outage that left every key behind,
     * and equally after deleting nothing at all. That is the leak wearing a success
     * message this method's own Javadoc warns about, so the line has to report what
     * actually happened.
     */
    @Test
    void deleteKeyPair_doesNotReportSuccessWhenTheVaultRefusedEveryKey() throws Exception {
        signingService.generateKeyPair("tenant-1", "agent-1");
        secretProvider.failDeleteFor("tenant-1:agent-signing-key:agent-1");

        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        // logging.properties turns the whole ai.labs.eddi namespace OFF for plain unit
        // tests, so this logger has to be opened explicitly to see anything.
        Logger julLogger = Logger.getLogger(AgentSigningService.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            signingService.deleteKeyPair("tenant-1", "agent-1", List.of());
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }

        assertTrue(captured.stream().anyMatch(m -> m.contains("could NOT be deleted")),
                "a vault outage must be reported, not summarised as a success; captured: " + captured);
        assertTrue(captured.stream().noneMatch(m -> m.startsWith("Deleted ")),
                "nothing was deleted, so nothing may claim it was; captured: " + captured);
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

    // ==================== log injection (CWE-117) ====================

    /**
     * CWE-117. {@code agentId} and {@code tenantId} arrive from the REST path and
     * from deployment configuration, and {@code firstFailure} quotes the vault key
     * built out of {@code agentId}, so all three carry caller text into the WARN
     * that reports a cleanup the operator is meant to act on. A newline in any of
     * them forges a record that reads as the server's own.
     */
    @Test
    void deleteKeyPair_vaultFailure_cannotForgeALogRecordThroughTheAgentId() throws Exception {
        String poisonedAgent = "agent-1" + FORGED_RECORD;
        String poisonedTenant = "tenant-1" + FORGED_RECORD;
        signingService.generateKeyPair(poisonedTenant, poisonedAgent);
        secretProvider.failDeleteFor(poisonedTenant + ":agent-signing-key:" + poisonedAgent);

        List<String> captured = captureLogsOf(AgentSigningService.class,
                () -> signingService.deleteKeyPair(poisonedTenant, poisonedAgent, List.of(1)));

        assertFalse(captured.isEmpty(),
                "nothing was captured, so this test proves nothing — the logger was not open, or the WARN branch did not run");
        assertTrue(captured.stream().anyMatch(value -> value.contains("could NOT be deleted")),
                "the vault-failure WARN is the line under test and it did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "AgentSigningService.deleteKeyPair's vault-failure WARN");
    }

    /**
     * CWE-117, the success branch of the same tally. It reports a different line
     * with the same caller-controlled identifiers, so it needs its own sanitising
     * and its own pin.
     */
    @Test
    void deleteKeyPair_success_cannotForgeALogRecordThroughTheAgentId() throws Exception {
        String poisonedAgent = "agent-1" + FORGED_RECORD;
        String poisonedTenant = "tenant-1" + FORGED_RECORD;
        signingService.generateKeyPair(poisonedTenant, poisonedAgent);

        List<String> captured = captureLogsOf(AgentSigningService.class,
                () -> signingService.deleteKeyPair(poisonedTenant, poisonedAgent, List.of(1)));

        assertFalse(captured.isEmpty(),
                "nothing was captured, so this test proves nothing — the logger was not open, or the INFO branch did not run");
        assertTrue(captured.stream().anyMatch(value -> value.contains("signing key(s) for agent")),
                "the successful-cleanup INFO is the line under test and it did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "AgentSigningService.deleteKeyPair's successful-cleanup INFO");
    }

    /**
     * CWE-117, the third branch: an agent that never had key material at all. It is
     * DEBUG rather than WARN, which changes nothing — a forged record is forged at
     * any level, and this is the branch an attacker reaches without needing the
     * vault to fail or the agent to exist.
     */
    @Test
    void deleteKeyPair_noKeys_cannotForgeALogRecordThroughTheAgentId() {
        String poisonedAgent = "agent-1" + FORGED_RECORD;
        String poisonedTenant = "tenant-1" + FORGED_RECORD;

        List<String> captured = captureLogsOf(AgentSigningService.class,
                () -> signingService.deleteKeyPair(poisonedTenant, poisonedAgent, List.of(1)));

        assertFalse(captured.isEmpty(),
                "nothing was captured, so this test proves nothing — the logger was not open, or the DEBUG branch did not run");
        assertTrue(captured.stream().anyMatch(value -> value.contains("No signing keys found in the vault")),
                "the nothing-to-clean DEBUG is the line under test and it did not fire; captured: " + captured);
        assertNoForgedRecordBoundary(captured, "AgentSigningService.deleteKeyPair's nothing-to-clean DEBUG");
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
         * Drops a key without going through {@link #delete} — for proving a cache
         * eviction.
         */
        void forget(String fullKey) {
            store.remove(fullKey);
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
