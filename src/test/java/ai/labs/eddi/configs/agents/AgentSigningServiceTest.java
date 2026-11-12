/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

class AgentSigningServiceTest {

    private AgentSigningService signingService;
    private InMemorySecretProvider secretProvider;

    @BeforeEach
    void setUp() {
        secretProvider = new InMemorySecretProvider();
        signingService = new AgentSigningService(secretProvider, new SimpleMeterRegistry());
        signingService.initMetrics();
    }

    // ─── negative cache: a keyless agent does not ask the vault per audit entry
    // ───

    @Test
    void keylessAgent_vaultAskedOncePerTtl() {
        long[] now = {0L};
        signingService.nanoClock = () -> now[0];

        for (int i = 0; i < 3; i++) {
            var e = assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.sign("tenant-1", "keyless", "entry"));
            assertTrue(e.getMessage().contains("No signing key found"), e.getMessage());
        }
        assertEquals(1, secretProvider.resolveCalls, "three audit entries, one vault lookup");

        now[0] += AgentSigningService.KEY_CACHE_TTL_NANOS;
        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.sign("tenant-1", "keyless", "entry"));
        assertEquals(2, secretProvider.resolveCalls, "after the TTL the vault is asked again");
    }

    @Test
    void signIfKeyPresent_keylessAgentIsNullWithoutAskingTheVaultAgain() throws Exception {
        signingService.nanoClock = () -> 0L;

        for (int i = 0; i < 3; i++) {
            assertNull(signingService.signIfKeyPresent("tenant-1", "keyless", "entry"));
        }
        assertEquals(1, secretProvider.resolveCalls, "three audit entries, one vault lookup");
    }

    @Test
    void signIfKeyPresent_signsWhenTheKeyExists() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1");

        String signature = signingService.signIfKeyPresent("tenant-1", "agent-1", "entry");

        assertNotNull(signature);
        assertTrue(signingService.verify(publicKey, "entry", signature));
    }

    @Test
    void signIfKeyPresent_aVaultFailureStillThrows() {
        var failing = new InMemorySecretProvider() {
            @Override
            public String resolve(SecretReference reference) {
                throw new IllegalStateException("Vault unavailable");
            }
        };
        var service = new AgentSigningService(failing, new SimpleMeterRegistry());
        service.initMetrics();

        assertThrows(AgentSigningService.AgentSigningException.class, () -> service.signIfKeyPresent("t1", "a1", "entry"));
    }

    /**
     * A key provisioned for an agent that was just found keyless — the unversioned
     * key the audit ledger uses, and a rotated one for an envelope — signs once the
     * remembered "no key" has expired.
     */
    @Test
    void keylessAgent_aKeyAddedToTheVaultSignsOnceTheTtlHasPassed() throws Exception {
        long[] now = {0L};
        signingService.nanoClock = () -> now[0];
        var envelope = SignedEnvelope.forSigning("agent-x", "agent-y", Map.of("text", "hello"));
        assertNull(signingService.signIfKeyPresent("tenant-1", "agent-x", "entry"));
        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.signEnvelope("tenant-1", "agent-x", envelope, 2));

        String publicKey = provisionKey("tenant-1", "agent-x");
        String publicKeyV2 = provisionKey("tenant-1", "agent-x", 2);
        now[0] += AgentSigningService.KEY_CACHE_TTL_NANOS;

        assertTrue(signingService.verify(publicKey, "entry", signingService.signIfKeyPresent("tenant-1", "agent-x", "entry")));
        assertTrue(signingService.verifyEnvelope(signingService.signEnvelope("tenant-1", "agent-x", envelope, 2), publicKeyV2));
    }

    // ─── positive cache: a loaded key is re-read from the vault after the TTL ───

    /**
     * The loaded private key used to be cached with no expiry, so a key replaced in
     * the vault — on this node by anything other than this service, or on another
     * node by anything at all — was never picked up: this process kept signing with
     * the old key until it restarted, and every signature failed verification
     * against the new public key.
     */
    @Test
    void replacedKey_isUsedOnceTheTtlHasPassed() throws Exception {
        long[] now = {0L};
        signingService.nanoClock = () -> now[0];
        String publicKeyA = provisionKey("tenant-1", "agent-1");
        signingService.sign("tenant-1", "agent-1", "first");

        String publicKeyB = provisionKey("tenant-1", "agent-1");
        now[0] += AgentSigningService.KEY_CACHE_TTL_NANOS;
        String signature = signingService.sign("tenant-1", "agent-1", "second");

        assertTrue(signingService.verify(publicKeyB, "second", signature), "the replaced key must be picked up after the TTL");
        assertFalse(signingService.verify(publicKeyA, "second", signature), "the old key is still cached");
    }

    /**
     * The other half of the TTL: inside it the cached key is used, so the vault is
     * read once per agent and TTL, not once per signature (the audit ledger signs
     * three entries a turn).
     */
    @Test
    void loadedKey_isServedFromTheCacheInsideTheTtl() throws Exception {
        long[] now = {0L};
        signingService.nanoClock = () -> now[0];
        String publicKeyA = provisionKey("tenant-1", "agent-1");
        signingService.sign("tenant-1", "agent-1", "first");
        int readsAfterFirstSignature = secretProvider.resolveCalls;

        provisionKey("tenant-1", "agent-1");
        now[0] += AgentSigningService.KEY_CACHE_TTL_NANOS - 1;
        String signature = signingService.sign("tenant-1", "agent-1", "second");

        assertTrue(signingService.verify(publicKeyA, "second", signature), "inside the TTL the cached key signs");
        assertEquals(readsAfterFirstSignature, secretProvider.resolveCalls, "inside the TTL the vault is not read again");
    }

    /**
     * A key removed from the vault without going through this node's
     * {@code deleteKeyPair} — another node's delete, for one — stops being used
     * after the TTL rather than for the rest of the process's life.
     */
    @Test
    void keyRemovedFromTheVaultElsewhere_stopsSigningOnceTheTtlHasPassed() throws Exception {
        long[] now = {0L};
        signingService.nanoClock = () -> now[0];
        provisionKey("tenant-1", "agent-1");
        signingService.sign("tenant-1", "agent-1", "first");

        secretProvider.forget("tenant-1:agent-signing-key:agent-1");
        now[0] += AgentSigningService.KEY_CACHE_TTL_NANOS;

        assertNull(signingService.signIfKeyPresent("tenant-1", "agent-1", "second"),
                "the removed key must not keep signing from memory past the TTL");
    }

    @Test
    void vaultFailure_isNotRemembered() {
        signingService.nanoClock = () -> 0L;
        secretProvider.resolveUnreachable = true;

        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.sign("tenant-1", "agent-1", "entry"));
        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.sign("tenant-1", "agent-1", "entry"));

        assertEquals(2, secretProvider.resolveCalls);
    }

    @Test
    void sign_and_verify_roundTrip() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1");
        String payload = "Hello from Agent-1";

        String signature = signingService.sign("tenant-1", "agent-1", payload);
        assertNotNull(signature);

        assertTrue(signingService.verify(publicKey, payload, signature));
    }

    @Test
    void verify_failsOnTamperedPayload() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1");
        String signature = signingService.sign("tenant-1", "agent-1", "original message");

        assertFalse(signingService.verify(publicKey, "tampered message", signature));
    }

    @Test
    void verify_failsOnWrongKey() throws Exception {
        String publicKey1 = provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-2");

        String signature = signingService.sign("tenant-1", "agent-2", "message");

        // Verify with agent-1's public key should fail
        assertFalse(signingService.verify(publicKey1, "message", signature));
    }

    @Test
    void deleteKeyPair_removesKey() throws Exception {
        provisionKey("tenant-1", "agent-1");
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
    void sign_throwsAgentSigningExceptionWhenKeyNotFound() {
        // Agent was never generated, so vault has no key
        assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.sign("t1", "nonexistent-agent", "payload"));
    }

    // ==================== Envelope signing/verification ====================

    @Test
    void signEnvelope_andVerify_roundTrip() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1");
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("message", "hello"));

        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 0);

        assertNotNull(signed.signature());
        assertTrue(signingService.verifyEnvelope(signed, publicKey));
    }

    @Test
    void signEnvelope_withVersionedKey() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1", 3);
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
        String publicKey = provisionKey("tenant-1", "agent-1");
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
        provisionKey("tenant-1", "agent-1");
        var envelope = SignedEnvelope.forSigning("agent-1", "agent-2", Map.of("msg", "test"));
        var signed = signingService.signEnvelope("tenant-1", "agent-1", envelope, 0);

        assertFalse(signingService.verifyEnvelope(signed, "invalid-key"));
    }

    // ==================== deleteKeyPair with versioned keys ====================

    @Test
    void deleteKeyPair_alsoDeletesVersionedKeys() throws Exception {
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 1);

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
        provisionKey("tenant-1", "agent-1", 1);
        provisionKey("tenant-1", "agent-1", 2);

        signingService.deleteKeyPair("tenant-1", "agent-1");

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"),
                "v1 was left in the vault: the absent legacy key aborted the versioned scan");
        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v2"),
                "v2 was left in the vault: the absent legacy key aborted the versioned scan");
    }

    /**
     * Rotated keys are provisioned under whatever version number their provisioner
     * chose, so versions can be sparse. Stopping the scan at the first missing
     * version left every key past the gap behind.
     */
    @Test
    void deleteKeyPair_deletesVersionsPastAGap() throws Exception {
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 1);
        provisionKey("tenant-1", "agent-1", 3);

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
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 1);
        provisionKey("tenant-1", "agent-1", 3);

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
     * A vault key can exist at a version {@code identity.keys} does not list. The
     * vault entry and the {@code keys[]} entry are SEPARATE writes, either of which
     * can land without the other — and {@code keys[]} is operator-editable JSON
     * that nothing prunes. Sweeping only the listed versions left that private key
     * in the vault forever after a permanent delete, while the tally reported
     * "Deleted N signing key(s)": the leak wearing a success message, on a narrower
     * path than the blanket-catch one the tally was written to close.
     */
    @Test
    void deleteKeyPair_withKnownVersions_stillRemovesAnUndeclaredKeyBelowTheHighest() throws Exception {
        provisionKey("tenant-1", "agent-1", 1);
        // v2 exists in the vault but the follow-up config write never landed, so
        // identity.keys names only v1 and v3.
        provisionKey("tenant-1", "agent-1", 2);
        provisionKey("tenant-1", "agent-1", 3);

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
        provisionKey("tenant-1", "agent-1", aboveCap);

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
        provisionKey("tenant-1", "agent-1");

        signingService.deleteKeyPair("tenant-1", "agent-1", List.of());

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1"));
    }

    /**
     * An empty declared-version list is not evidence that no versioned key exists,
     * so it bounds nothing and has to fall back to the blind sweep.
     *
     * <p>
     * The vault entry and the {@code identity.keys} entry are SEPARATE writes. A
     * first rotation whose config update never landed therefore leaves v1 in the
     * vault with {@code keys[]} still empty — and an operator can prune
     * {@code keys[]} by hand to the same shape. Treating that as "no rotated
     * versions, nothing to sweep" left an Ed25519 private key in the vault after a
     * PERMANENT delete, while the tally reported a clean "Deleted 1 signing
     * key(s)".
     * </p>
     */
    @Test
    void deleteKeyPair_withNoDeclaredVersions_stillRemovesAnUndeclaredRotatedKey() throws Exception {
        provisionKey("tenant-1", "agent-1");
        // The rotation reached the vault; the config write that would have added it to
        // identity.keys did not.
        provisionKey("tenant-1", "agent-1", 1);

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
     * or a zero/negative version. Signing treats those as the unversioned key,
     * meaning they name no versioned vault entry at all — and letting one set the
     * upper bound of the sweep would either widen it needlessly or, for a null,
     * throw out of a delete.
     */
    @Test
    void deleteKeyPair_ignoresNullAndNonPositiveDeclaredVersions() throws Exception {
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 2);

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
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 1);
        provisionKey("tenant-1", "agent-1", 2);
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
        String publicKey = provisionKey("tenant-1", "agent-1");
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
        provisionKey("tenant-1", "agent-1");
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
        provisionKey("tenant-1", "agent-1");
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
        provisionKey("tenant-1", "agent-1");
        provisionKey("tenant-1", "agent-1", 1);
        provisionKey("tenant-1", "agent-1", 2);
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
        provisionKey(poisonedTenant, poisonedAgent);
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
        provisionKey(poisonedTenant, poisonedAgent);

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

    // ─── generateKey / checkSigningKey: provisioning, and the save-time check ───

    @Test
    void generateKey_storesAPrivateKeyThatSignsForTheReturnedPublicKey() throws Exception {
        var generated = signingService.generateKey("tenant-1", "agent-1", 1);

        assertEquals(1, generated.version());
        assertTrue(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"), "the private key is in the vault under v1");
        var envelope = SignedEnvelope.forSigning("agent-1", "group-1", Map.of("content", "hello"));
        assertTrue(signingService.verifyEnvelope(signingService.signEnvelope("tenant-1", "agent-1", envelope, 1), generated.publicKeyB64()));
    }

    /**
     * The vault entry and the agent's {@code identity.keys} are two writes. A
     * version the vault already holds — a concurrent provisioning, or one whose
     * config write never landed — may have a published public key, so it is never
     * replaced.
     */
    @Test
    void generateKey_neverReplacesAKeyAlreadyInTheVault() throws Exception {
        String existingPublicKey = provisionKey("tenant-1", "agent-1", 1);

        var generated = signingService.generateKey("tenant-1", "agent-1", 1);

        assertEquals(2, generated.version(), "v1 is taken, so the new key is v2");
        var envelope = SignedEnvelope.forSigning("agent-1", "group-1", Map.of("content", "hello"));
        assertTrue(signingService.verifyEnvelope(signingService.signEnvelope("tenant-1", "agent-1", envelope, 1), existingPublicKey),
                "the key that was already at v1 still signs as v1");
    }

    /** A "no key" remembered for the version just generated must not outlive it. */
    @Test
    void generateKey_isUsedAtOnceAfterTheVaultWasFoundEmpty() throws Exception {
        signingService.nanoClock = () -> 0L;
        var envelope = SignedEnvelope.forSigning("agent-1", "group-1", Map.of("content", "hello"));
        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.signEnvelope("tenant-1", "agent-1", envelope, 1));

        var generated = signingService.generateKey("tenant-1", "agent-1", 1);

        assertTrue(signingService.verifyEnvelope(signingService.signEnvelope("tenant-1", "agent-1", envelope, 1), generated.publicKeyB64()),
                "the clock has not moved: only the eviction lets the new key sign");
    }

    @Test
    void generateKey_reportsAVaultFailure() throws Exception {
        ISecretProvider failing = SigningTestSupport.inMemoryVault();
        doThrow(new ISecretProvider.SecretProviderException("Secrets Vault is not available")).when(failing).storeIfAbsent(any(), anyString(),
                any(), any());
        var service = new AgentSigningService(failing, new SimpleMeterRegistry());

        var e = assertThrows(AgentSigningService.AgentSigningException.class, () -> service.generateKey("t1", "a1", 1));
        assertTrue(e.getMessage().contains("Secrets Vault is not available"), e.getMessage());
    }

    /**
     * "fromVersion + attempts" overflowed for a version near Integer.MAX_VALUE: the
     * loop never ran and a declared bogus version turned Generate into a 503 that
     * blamed the vault. Versions now stop at MAX_VERSION.
     */
    @Test
    void generateKey_neverGoesPastTheHighestVersion() throws Exception {
        provisionKey("tenant-1", "agent-1", AgentPublicKey.MAX_VERSION);

        var e = assertThrows(AgentSigningService.AgentSigningException.class,
                () -> signingService.generateKey("tenant-1", "agent-1", AgentPublicKey.MAX_VERSION));
        assertTrue(e.getMessage().contains("versions 1000000 to 1000000"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> signingService.generateKey("tenant-1", "agent-1", AgentPublicKey.MAX_VERSION + 1));
        assertThrows(IllegalArgumentException.class, () -> signingService.generateKey("tenant-1", "agent-1", Integer.MAX_VALUE));
    }

    @Test
    void discardKey_removesTheVersionFromTheVault() throws Exception {
        var generated = signingService.generateKey("tenant-1", "agent-1", 1);

        signingService.discardKey("tenant-1", "agent-1", generated.version());

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"));
    }

    @Test
    void discardKey_ofVersionZeroRemovesTheLegacyKey() throws Exception {
        provisionKey("tenant-1", "agent-1");
        signingService.generateKey("tenant-1", "agent-1", 1);

        signingService.discardKey("tenant-1", "agent-1", 0);

        assertFalse(secretProvider.contains("tenant-1", "agent-signing-key:agent-1"));
        assertTrue(secretProvider.contains("tenant-1", "agent-signing-key:agent-1:v1"), "only the legacy key goes");
    }

    @Test
    void isEd25519PublicKey_acceptsOnlyAnEd25519PublicKey() throws Exception {
        var generated = signingService.generateKey("tenant-1", "agent-1", 1);
        String rsaPublicKey = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());

        assertTrue(AgentSigningService.isEd25519PublicKey(generated.publicKeyB64()));
        assertFalse(AgentSigningService.isEd25519PublicKey(rsaPublicKey));
        assertFalse(AgentSigningService.isEd25519PublicKey("not base64 !"));
        assertFalse(AgentSigningService.isEd25519PublicKey(null));
    }

    @Test
    void checkSigningKey_isUsableForAGeneratedKey() throws Exception {
        var generated = signingService.generateKey("tenant-1", "agent-1", 1);

        assertEquals(AgentSigningService.SigningKeyStatus.USABLE, signingService.checkSigningKey("tenant-1", "agent-1", 1, generated.publicKeyB64()));
    }

    @Test
    void checkSigningKey_coversTheLegacyUnversionedKey() throws Exception {
        String publicKey = provisionKey("tenant-1", "agent-1");

        assertEquals(AgentSigningService.SigningKeyStatus.USABLE, signingService.checkSigningKey("tenant-1", "agent-1", 0, publicKey));
    }

    @Test
    void checkSigningKey_rejectsAPublicKeyThatIsNotEd25519() throws Exception {
        signingService.generateKey("tenant-1", "agent-1", 1);
        String rsaPublicKey = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());

        assertEquals(AgentSigningService.SigningKeyStatus.INVALID_PUBLIC_KEY,
                signingService.checkSigningKey("tenant-1", "agent-1", 1, "MCowBQYDK2VwAyEA-not-a-real-key"));
        assertEquals(AgentSigningService.SigningKeyStatus.INVALID_PUBLIC_KEY, signingService.checkSigningKey("tenant-1", "agent-1", 1, rsaPublicKey));
        assertEquals(AgentSigningService.SigningKeyStatus.INVALID_PUBLIC_KEY, signingService.checkSigningKey("tenant-1", "agent-1", 1, null));
    }

    @Test
    void checkSigningKey_reportsAKeyMissingFromTheVault() throws Exception {
        String publicKeyOfAnotherAgent = signingService.generateKey("tenant-1", "agent-2", 1).publicKeyB64();

        assertEquals(AgentSigningService.SigningKeyStatus.NOT_IN_VAULT,
                signingService.checkSigningKey("tenant-1", "agent-1", 1, publicKeyOfAnotherAgent));
    }

    /**
     * The declared public key is someone else's: the vault key signs, but not as
     * it.
     */
    @Test
    void checkSigningKey_reportsAPublicKeyThatDoesNotMatchTheVault() throws Exception {
        signingService.generateKey("tenant-1", "agent-1", 1);
        String publicKeyOfAnotherAgent = signingService.generateKey("tenant-1", "agent-2", 1).publicKeyB64();

        assertEquals(AgentSigningService.SigningKeyStatus.MISMATCH,
                signingService.checkSigningKey("tenant-1", "agent-1", 1, publicKeyOfAnotherAgent));
    }

    @Test
    void checkSigningKey_reportsAVaultEntryThatIsNotAPrivateKey() throws Exception {
        secretProvider.store(new SecretReference("tenant-1", "agent-signing-key:agent-1:v1"), "bm90LWEta2V5", null, List.of("agent-1"));
        String publicKey = signingService.generateKey("tenant-1", "agent-2", 1).publicKeyB64();

        assertEquals(AgentSigningService.SigningKeyStatus.UNREADABLE, signingService.checkSigningKey("tenant-1", "agent-1", 1, publicKey));
    }

    @Test
    void checkSigningKey_throwsWhenTheVaultCannotAnswer() throws Exception {
        String publicKey = signingService.generateKey("tenant-1", "agent-2", 1).publicKeyB64();
        secretProvider.resolveUnreachable = true;

        assertThrows(AgentSigningService.AgentSigningException.class, () -> signingService.checkSigningKey("tenant-1", "agent-1", 1, publicKey));
    }

    @Test
    void checkSigningKey_leavesTheVerifyMetricsAlone() throws Exception {
        var registry = new SimpleMeterRegistry();
        var service = new AgentSigningService(secretProvider, registry);
        service.initMetrics();
        var generated = service.generateKey("tenant-1", "agent-1", 1);

        service.checkSigningKey("tenant-1", "agent-1", 1, generated.publicKeyB64());

        assertEquals(0.0, registry.counter("eddi.agent.identity.verify.success").count());
        assertEquals(0.0, registry.counter("eddi.agent.identity.verify.fail").count());
    }

    /**
     * Writes a signing key into the vault in the one shape this service reads: an
     * Ed25519 keypair whose PKCS#8 private key goes in Base64-encoded under
     * {@code agent-signing-key:{agentId}}. {@link AgentSigningService#generateKey}
     * writes only versioned keys, so the test stands in for a legacy unversioned
     * one — and, through the overload below, for a version already taken in the
     * vault. Writing it again replaces the key.
     *
     * @return the Base64 X.509 public key, as {@code identity.publicKey} holds it
     */
    private String provisionKey(String tenantId, String agentId) throws Exception {
        return storeNewKey(tenantId, "agent-signing-key:" + agentId, agentId);
    }

    /**
     * As {@link #provisionKey(String, String)}, for a rotated key under
     * {@code agent-signing-key:{agentId}:v{version}}.
     */
    private String provisionKey(String tenantId, String agentId, int version) throws Exception {
        return storeNewKey(tenantId, "agent-signing-key:" + agentId + ":v" + version, agentId);
    }

    private String storeNewKey(String tenantId, String keyName, String agentId) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        secretProvider.store(new SecretReference(tenantId, keyName), Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                "Ed25519 signing key for agent " + agentId, List.of(agentId));
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /**
     * Writes a signing key into the vault in the one shape this service reads: an
     * Ed25519 keypair whose PKCS#8 private key goes in Base64-encoded under
     * {@code agent-signing-key:{agentId}}. Nothing in EDDI writes one, so the test
     * stands in for whatever provisions it. Writing it again replaces the key.
     *
     * @return the Base64 X.509 public key, as {@code identity.publicKey} holds it
     */
    private String provisionKey(String tenantId, String agentId) throws Exception {
        return storeNewKey(tenantId, "agent-signing-key:" + agentId, agentId);
    }

    /**
     * As {@link #provisionKey(String, String)}, for a rotated key under
     * {@code agent-signing-key:{agentId}:v{version}}.
     */
    private String provisionKey(String tenantId, String agentId, int version) throws Exception {
        return storeNewKey(tenantId, "agent-signing-key:" + agentId + ":v" + version, agentId);
    }

    private String storeNewKey(String tenantId, String keyName, String agentId) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        secretProvider.store(new SecretReference(tenantId, keyName), Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                "Ed25519 signing key for agent " + agentId, List.of(agentId));
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
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

        /**
         * How often the vault was asked for a secret — for pinning the negative cache.
         */
        int resolveCalls;

        /** Makes {@code resolve} fail as an unreachable vault would. */
        boolean resolveUnreachable;

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
            resolveCalls++;
            if (resolveUnreachable) {
                throw new IllegalStateException("Vault unreachable");
            }
            String key = reference.tenantId() + ":" + reference.keyName();
            String value = store.get(key);
            if (value == null) {
                throw new SecretNotFoundException("Secret not found: " + key);
            }
            return value;
        }

        @Override
        public void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents, List<String> allowedHosts)
                throws SecretProviderException {
            store.put(reference.tenantId() + ":" + reference.keyName(), plaintext);
        }

        @Override
        public SecretMetadata updateAllowedHosts(SecretReference reference, List<String> allowedHosts) throws SecretProviderException {
            throw new SecretProviderException("InMemorySecretProvider does not model allowed hosts");
        }

        @Override
        public boolean storeIfAbsent(SecretReference reference, String plaintext, String description, List<String> allowedAgents) {
            return store.putIfAbsent(reference.tenantId() + ":" + reference.keyName(), plaintext) == null;
        }

        /**
         * Unsupported on purpose. This double stores only plaintext by key — it has no
         * grant list to update, and agent signing never touches one. Throwing beats
         * returning a fabricated metadata record a later test might believe.
         */
        @Override
        public SecretMetadata updateGrant(SecretReference reference, List<String> allowedAgents, String description)
                throws SecretProviderException {
            throw new SecretProviderException("InMemorySecretProvider does not model grants");
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
