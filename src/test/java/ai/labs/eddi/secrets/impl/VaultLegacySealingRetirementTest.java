/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * L10 of the 2026-10-17 security review: the no-AAD decrypt fallback for rows
 * written before row binding was permanent, so with database write access the
 * ciphertexts of two such rows could be swapped and each still decrypted. A
 * legacy row is now re-sealed bound the first time it is read.
 */
class VaultLegacySealingRetirementTest {

    private static final String MASTER = "master-key-one-1234567890";

    private InMemorySecretPersistence persistence;
    private VaultSecretProvider provider;
    private byte[] rawDek;
    private EncryptedDek dek;

    @BeforeEach
    void setUp() throws Exception {
        persistence = new InMemorySecretPersistence();
        provider = new VaultSecretProvider(Optional.of(MASTER), persistence, new VaultSaltManager(persistence), new SimpleMeterRegistry(), null);
        provider.initMetrics();
        provider.onStartup(mock(StartupEvent.class));
        // A bound secret creates the tenant's first DEK generation.
        provider.store(new SecretReference("acme", "bound"), "bound-value", null, List.of("*"));
        byte[] kek = EnvelopeCrypto.deriveKeyFromString(MASTER, Base64.getDecoder().decode(persistence.meta.get("vault-kek-salt")));
        dek = persistence.deks.get("acme/1");
        rawDek = EnvelopeCrypto.decryptDek(dek.getEncryptedDek(), dek.getIv(), kek);
    }

    /** A row as it was written before AAD binding existed. */
    private EncryptedSecret seedLegacy(String key, String value) {
        var legacy = EnvelopeCrypto.encrypt(value, rawDek);
        var row = new EncryptedSecret(UUID.randomUUID().toString(), "acme", key, legacy.ciphertext(), legacy.iv(), dek.dekId(), null, null,
                List.of("*"), Instant.now(), null, null);
        persistence.secrets.put("acme/" + key, row);
        return row;
    }

    @Test
    @DisplayName("a legacy row resolves and is re-sealed bound in place: same value, same generation, not marked rotated")
    void legacyRowIsResealedOnRead() throws Exception {
        seedLegacy("legacy", "legacy-value");
        String legacyIv = persistence.secrets.get("acme/legacy").getIv();

        assertEquals("legacy-value", provider.resolve(new SecretReference("acme", "legacy")));

        EncryptedSecret after = persistence.secrets.get("acme/legacy");
        assertEquals(dek.dekId(), after.getDekId());
        assertNull(after.getLastRotatedAt(), "a re-seal is not a rotation");
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(after.getEncryptedValue(), after.getIv(), rawDek),
                "the row no longer opens without its row identity — the fallback is retired for it");
        assertEquals(false, legacyIv.equals(after.getIv()));
        assertEquals("legacy-value", provider.resolve(new SecretReference("acme", "legacy")));
        assertEquals(1, persistence.resealInPlaceCalls, "a bound row is never re-sealed again");
    }

    @Test
    @DisplayName("once re-sealed, swapping two former legacy rows' ciphertexts no longer decrypts")
    void swapAfterRetirementIsRefused() throws Exception {
        seedLegacy("one", "value-one");
        seedLegacy("two", "value-two");
        provider.resolve(new SecretReference("acme", "one"));
        provider.resolve(new SecretReference("acme", "two"));

        EncryptedSecret one = persistence.secrets.get("acme/one");
        EncryptedSecret two = persistence.secrets.get("acme/two");
        two.setEncryptedValue(one.getEncryptedValue());
        two.setIv(one.getIv());

        assertThrows(Exception.class, () -> provider.resolve(new SecretReference("acme", "two")));
    }

    @Test
    @DisplayName("a store landing between the read and the write-back wins: the old value is never written back")
    void concurrentStoreIsNotOverwritten() throws Exception {
        seedLegacy("racy", "old-value");
        persistence.beforeResealInPlace = () -> {
            persistence.beforeResealInPlace = null;
            assertDoesNotThrow(() -> provider.store(new SecretReference("acme", "racy"), "new-value", null, List.of("*")));
        };

        assertEquals("old-value", provider.resolve(new SecretReference("acme", "racy")), "the read itself saw the old value");

        assertEquals("new-value", provider.resolve(new SecretReference("acme", "racy")));
    }

    @Test
    @DisplayName("a failing write-back never fails the read")
    void failingWriteBackDoesNotFailTheRead() throws Exception {
        seedLegacy("flaky", "flaky-value");
        persistence.beforeResealInPlace = () -> {
            throw new PersistenceException("db down");
        };

        assertEquals("flaky-value", provider.resolve(new SecretReference("acme", "flaky")));
        assertDoesNotThrow(() -> EnvelopeCrypto.decrypt(persistence.secrets.get("acme/flaky").getEncryptedValue(),
                persistence.secrets.get("acme/flaky").getIv(), rawDek), "the row stays legacy and readable");
    }
}
