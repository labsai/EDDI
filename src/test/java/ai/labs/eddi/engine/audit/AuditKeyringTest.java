/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.AuditHmac.VerificationOutcome;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H6d: the audit ledger's HMAC key was derived from the vault master key and
 * nothing else, so the documented KEK rotation silently changed it and every
 * entry ever written failed verification after the restart.
 */
@DisplayName("AuditKeyring")
class AuditKeyringTest {

    private static final String MASTER = "master-key-one-1234567890";
    private static final String NEW_MASTER = "master-key-two-0987654321";

    /**
     * Stands in for the vault's pinned system values: insert-if-absent, by name.
     */
    private final Map<String, String> pinned = new HashMap<>();
    private Instance<ISecretProvider> vault;
    private ISecretProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.pinSystemValue(anyString(), anyString())).thenAnswer(inv -> {
            pinned.putIfAbsent(inv.getArgument(0), inv.getArgument(1));
            return pinned.get(inv.<String>getArgument(0));
        });
        when(provider.readSystemValue(anyString())).thenAnswer(inv -> Optional.ofNullable(pinned.get(inv.<String>getArgument(0))));
        this.provider = provider;
        vault = mock(Instance.class);
        when(vault.isUnsatisfied()).thenReturn(false);
        when(vault.get()).thenReturn(provider);
    }

    private AuditKeyring keyring(String masterKey, String configuredKey, List<String> previousKeys, Instance<ISecretProvider> secretProvider) {
        var keyring = new AuditKeyring(Optional.ofNullable(masterKey), Optional.ofNullable(configuredKey), Optional.ofNullable(previousKeys),
                secretProvider);
        keyring.initialize();
        keyring.pinWithVault();
        return keyring;
    }

    private static AuditEntry entry() {
        return AuditHmac.withStorablePrecision(new AuditEntry("id-1", "conv-1", "agent-1", 1, "alice", "production", 0, "task", "LlmTask", 0, 10L,
                Map.of("text", "hi"), Map.of("text", "hello"), null, null, List.of("action"), 0.0, Instant.now(), null, null));
    }

    @Test
    @DisplayName("the ledger keeps verifying after the vault master key is rotated")
    void ledgerSurvivesAMasterKeyRotation() {
        var beforeRotation = keyring(MASTER, null, null, vault);
        AuditEntry v5 = entry();
        v5 = v5.withHmac(AuditHmac.computeHmac(v5, beforeRotation.signingKey()));
        // A pre-v5 row, signed with the master-derived key as every row used to be.
        AuditEntry v4 = entry();
        v4 = v4.withHmac(AuditHmac.computeHmac(v4, AuditHmac.deriveHmacKey(MASTER)));

        var afterRotation = keyring(NEW_MASTER, null, null, vault);

        assertEquals(beforeRotation.signingKey().id(), afterRotation.signingKey().id(), "the ledger's key must not follow the master key");
        assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(v5, afterRotation.verificationKeys(), AuditRecoveryBudget.none()));
        assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(v4, afterRotation.verificationKeys(), AuditRecoveryBudget.none()));

        // What happened before the keyring: the key derived from the new master key
        // alone knows neither row.
        var derivedOnly = AuditKeyring.fromMasterKey(NEW_MASTER);
        assertEquals(VerificationOutcome.UNKNOWN_KEY, AuditHmac.verify(v5, derivedOnly.verificationKeys(), AuditRecoveryBudget.none()));
        assertEquals(VerificationOutcome.MISMATCH, AuditHmac.verify(v4, derivedOnly.verificationKeys(), AuditRecoveryBudget.none()));
    }

    @Test
    @DisplayName("eddi.audit.hmac-key signs independently of the master key")
    void configuredKeyIsIndependentOfTheMasterKey() {
        var one = keyring(MASTER, "audit-only-secret-123", null, null);
        var two = keyring(NEW_MASTER, "audit-only-secret-123", null, null);

        assertEquals(AuditHmac.signingKey(AuditHmac.deriveHmacKey("audit-only-secret-123")).id(), one.signingKey().id());
        assertEquals(one.signingKey().id(), two.signingKey().id());
        // Rows signed before the independent key was introduced still verify.
        AuditEntry old = entry();
        old = old.withHmac(AuditHmac.computeHmac(old, AuditHmac.signingKey(AuditHmac.deriveHmacKey(MASTER))));
        assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(old, one.verificationKeys(), AuditRecoveryBudget.none()));
    }

    @Test
    @DisplayName("a retired key listed in eddi.audit.hmac-previous-keys verifies; an unlisted one is UNKNOWN_KEY, not a mismatch")
    void previousKeysVerifyAndUnknownKeysAreDistinguished() {
        var retired = AuditHmac.signingKey(AuditHmac.deriveHmacKey("retired-audit-key-1"));
        AuditEntry old = entry();
        old = old.withHmac(AuditHmac.computeHmac(old, retired));

        var withRetired = keyring(null, "current-audit-key-2", List.of("retired-audit-key-1"), null);
        var withoutRetired = keyring(null, "current-audit-key-2", null, null);

        assertEquals(VerificationOutcome.MATCH, AuditHmac.verify(old, withRetired.verificationKeys(), AuditRecoveryBudget.none()));
        assertEquals(VerificationOutcome.UNKNOWN_KEY, AuditHmac.verify(old, withoutRetired.verificationKeys(), AuditRecoveryBudget.none()));
        assertNotEquals(retired.id(), withRetired.signingKey().id(), "a retired key never signs");
    }

    @Test
    @DisplayName("without the vault the master-derived key signs, and with no secret at all nothing does")
    void fallbacks() {
        var noVault = keyring(MASTER, null, null, null);
        assertEquals(AuditHmac.signingKey(AuditHmac.deriveHmacKey(MASTER)).id(), noVault.signingKey().id());
        assertTrue(pinned.isEmpty());

        assertNull(keyring(null, null, null, vault).signingKey());
    }

    @Test
    @DisplayName("keyed pseudonyms are offered for every verification key")
    void keyedPseudonymsForEveryKey() {
        var keyring = keyring(MASTER, "audit-only-secret-123", List.of("retired-audit-key-1"), null);

        var pseudonyms = keyring.keyedPseudonymsFor("alice");

        assertEquals(keyring.verificationKeys().size(), pseudonyms.size());
        for (var key : keyring.verificationKeys()) {
            assertEquals(AuditHmac.keyedPseudonymFor("alice", key.pseudonymKey()), pseudonyms.get(key.id()));
        }
    }

    /**
     * M1: the key id is text in the row, so an unknown id alone proves nothing.
     * Only ids the keyring recorded in the vault count as "a key we used and lost".
     */
    @Test
    @DisplayName("only key ids recorded in the vault are recognised; a made-up id is not")
    void recordedKeyIds() {
        var keyring = keyring(MASTER, null, null, vault);
        String signingId = keyring.signingKey().id();

        assertTrue(pinned.containsKey(AuditKeyring.KEY_ID_RECORD_PREFIX + signingId), "the signing key is recorded when pinned");
        var elsewhere = keyring(NEW_MASTER, null, null, vault);
        assertTrue(elsewhere.isRecordedKeyId(signingId), "another node reads the record from the vault");
        assertFalse(elsewhere.isRecordedKeyId("0123456789abcdef"), "an id nobody recorded is not a lost key");
        assertFalse(AuditKeyring.fromMasterKey(MASTER).isRecordedKeyId(signingId), "without a vault nothing counts as recorded");
    }

    /**
     * The key id record is written after the pin. A failed record used to be final
     * until restart, so rows signed with that key would report INVALID rather than
     * UNKNOWN_KEY if the key were ever lost.
     */
    @Test
    @DisplayName("a key id that failed to be recorded is retried lazily from signingKey()")
    void failedKeyIdRecordIsRetried() throws Exception {
        var keyring = new AuditKeyring(Optional.of(MASTER), Optional.empty(), Optional.empty(), vault);
        keyring.initialize();
        // The pin succeeds; recording the key id behind it fails until the vault
        // recovers.
        AtomicBoolean recordsFail = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (recordsFail.get() && inv.<String>getArgument(0).startsWith(AuditKeyring.KEY_ID_RECORD_PREFIX)) {
                throw new ISecretProvider.SecretProviderException("db down");
            }
            pinned.putIfAbsent(inv.getArgument(0), inv.getArgument(1));
            return pinned.get(inv.<String>getArgument(0));
        }).when(provider).pinSystemValue(anyString(), anyString());

        assertTrue(keyring.pinWithVault());
        String signingId = keyring.signingKey().id();
        assertFalse(pinned.containsKey(AuditKeyring.KEY_ID_RECORD_PREFIX + signingId));

        recordsFail.set(false);
        keyring.signingKey();
        assertFalse(pinned.containsKey(AuditKeyring.KEY_ID_RECORD_PREFIX + signingId), "not before the backoff has elapsed");

        keyring.resetPinBackoffForTesting();
        keyring.signingKey();

        assertTrue(pinned.containsKey(AuditKeyring.KEY_ID_RECORD_PREFIX + signingId), "the next signing call must retry the record");
    }

    /** m6: a pin that failed at boot is retried while entries are signed. */
    @Test
    @DisplayName("a failed pin is retried lazily from signingKey()")
    void failedPinIsRetried() throws Exception {
        var keyring = new AuditKeyring(Optional.of(MASTER), Optional.empty(), Optional.empty(), vault);
        keyring.initialize();
        // doX().when() form: re-stubbing with when(provider.pinSystemValue(...)) would
        // invoke the existing answer with null arguments.
        doThrow(new ISecretProvider.SecretProviderException("db down")).doAnswer(inv -> {
            pinned.putIfAbsent(inv.getArgument(0), inv.getArgument(1));
            return pinned.get(inv.<String>getArgument(0));
        }).when(provider).pinSystemValue(anyString(), anyString());

        assertFalse(keyring.pinWithVault());
        assertTrue(pinned.isEmpty());

        keyring.resetPinBackoffForTesting();
        keyring.signingKey();

        assertTrue(pinned.containsKey(AuditKeyring.PINNED_KEY_NAME), "the next signing call must retry the pin");
    }
}
