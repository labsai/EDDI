/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AES-256-GCM envelope encryption utilities. The encryption
 * tests use a random DEK; only the derivation tests pay for PBKDF2's
 * iterations.
 */
class EnvelopeCryptoTest {

    private static final byte[] SALT = "fixture-salt-16b".getBytes(StandardCharsets.UTF_8);

    @Test
    void encryptAndDecrypt_roundtrip() {
        byte[] key = EnvelopeCrypto.generateDek();
        String plaintext = "sk-abc123secretValue";

        var result = EnvelopeCrypto.encrypt(plaintext, key);
        assertNotNull(result.ciphertext());
        assertNotNull(result.iv());

        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecrypt_emptyString() {
        byte[] key = EnvelopeCrypto.generateDek();

        var result = EnvelopeCrypto.encrypt("", key);
        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);
        assertEquals("", decrypted);
    }

    @Test
    void encrypt_producesUniqueIVs() {
        byte[] key = EnvelopeCrypto.generateDek();

        var result1 = EnvelopeCrypto.encrypt("same-plaintext", key);
        var result2 = EnvelopeCrypto.encrypt("same-plaintext", key);

        // IVs must be different (nonce uniqueness)
        assertNotEquals(result1.iv(), result2.iv());
        // Ciphertexts must be different (due to different IVs)
        assertNotEquals(result1.ciphertext(), result2.ciphertext());
    }

    @Test
    void decrypt_wrongKey_throws() {
        byte[] key1 = EnvelopeCrypto.generateDek();
        byte[] key2 = EnvelopeCrypto.generateDek();

        var result = EnvelopeCrypto.encrypt("secret", key1);

        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key2));
    }

    @Test
    void deriveKeyFromString_producesConsistentResults() {
        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("my-passphrase", SALT);
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("my-passphrase", SALT);

        assertArrayEquals(key1, key2);
        assertEquals(32, key1.length, "Key must be 32 bytes for AES-256");
    }

    @Test
    void deriveKeyFromString_differentInputsDifferentKeys() {
        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("passphrase-a", SALT);
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("passphrase-b", SALT);

        assertFalse(Arrays.equals(key1, key2));
    }

    @Test
    void deriveKeyFromString_differentSaltsDifferentKeys() {
        byte[] otherSalt = "other-salt-16byt".getBytes(StandardCharsets.UTF_8);

        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("my-passphrase", SALT);
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("my-passphrase", otherSalt);

        assertFalse(Arrays.equals(key1, key2), "the per-deployment salt must change the KEK");
    }

    @Test
    void deriveKeyFromString_saltShorterThanEightBytes_throws() {
        byte[] sevenBytes = new byte[7];

        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.deriveKeyFromString("my-passphrase", sevenBytes));
    }

    @Test
    void deriveKeyFromString_nullSalt_throws() {
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.deriveKeyFromString("my-passphrase", null));
    }

    @Test
    void encrypt_nullKey_throws() {
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.encrypt("test", null));
    }

    @Test
    void encrypt_shortKey_throws() {
        byte[] shortKey = new byte[16]; // AES-128, not AES-256
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.encrypt("test", shortKey));
    }

    @Test
    void encryptDecrypt_unicodeContent() {
        byte[] key = EnvelopeCrypto.generateDek();
        String plaintext = "こんにちは世界 🔑 Ключ";

        var result = EnvelopeCrypto.encrypt(plaintext, key);
        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);

        assertEquals(plaintext, decrypted);
    }

    // ─── Finding #6: GCM Additional Authenticated Data (AAD) binding ───

    @Test
    void aad_roundtrip() {
        byte[] key = EnvelopeCrypto.generateDek();
        byte[] aad = "default|apiKey|default#g1".getBytes();
        String plaintext = "sk-secret-value";

        var result = EnvelopeCrypto.encrypt(plaintext, key, aad);
        assertEquals(plaintext, EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key, aad));
    }

    @Test
    void aad_wrongAadFailsToDecrypt() {
        byte[] key = EnvelopeCrypto.generateDek();
        var result = EnvelopeCrypto.encrypt("sk-secret-value", key, "default|apiKey|default#g1".getBytes());

        // A ciphertext bound to one row cannot be authenticated under another row's AAD
        // — this is what stops a DB-write attacker swapping ciphertext between keys.
        assertThrows(EnvelopeCrypto.CryptoException.class,
                () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key, "default|otherKey|default#g1".getBytes()));
        // And a value written WITH aad cannot be read without it.
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key));
    }

    @Test
    void aad_legacyNoAadValueCannotBeReadWithAad() {
        byte[] key = EnvelopeCrypto.generateDek();
        // A legacy row (encrypted without AAD) authenticates only without AAD — which
        // is
        // why the provider tries the AAD form first and falls back to no-AAD.
        var legacy = EnvelopeCrypto.encrypt("legacy-value", key);
        assertEquals("legacy-value", EnvelopeCrypto.decrypt(legacy.ciphertext(), legacy.iv(), key));
        assertThrows(EnvelopeCrypto.CryptoException.class,
                () -> EnvelopeCrypto.decrypt(legacy.ciphertext(), legacy.iv(), key, "some|aad|here".getBytes()));
    }

    @Test
    void aad_nullAadEqualsNoAadOverload() {
        byte[] key = EnvelopeCrypto.generateDek();
        var result = EnvelopeCrypto.encrypt("v", key, null);
        // null AAD is the legacy form: readable by both the no-AAD and null-AAD paths.
        assertEquals("v", EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key));
        assertEquals("v", EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key, null));
    }
}
