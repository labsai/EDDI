/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AES-256-GCM envelope encryption utilities.
 */
@SuppressWarnings("deprecation") // Intentionally testing deprecated single-arg deriveKeyFromString for backward
                                 // compat
class EnvelopeCryptoTest {

    @Test
    void encryptAndDecrypt_roundtrip() {
        byte[] key = EnvelopeCrypto.deriveKeyFromString("test-master-key");
        String plaintext = "sk-abc123secretValue";

        var result = EnvelopeCrypto.encrypt(plaintext, key);
        assertNotNull(result.ciphertext());
        assertNotNull(result.iv());

        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);
        assertEquals(plaintext, decrypted);
    }

    @Test
    void encryptAndDecrypt_emptyString() {
        byte[] key = EnvelopeCrypto.deriveKeyFromString("test-key");

        var result = EnvelopeCrypto.encrypt("", key);
        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);
        assertEquals("", decrypted);
    }

    @Test
    void encrypt_producesUniqueIVs() {
        byte[] key = EnvelopeCrypto.deriveKeyFromString("test-key");

        var result1 = EnvelopeCrypto.encrypt("same-plaintext", key);
        var result2 = EnvelopeCrypto.encrypt("same-plaintext", key);

        // IVs must be different (nonce uniqueness)
        assertNotEquals(result1.iv(), result2.iv());
        // Ciphertexts must be different (due to different IVs)
        assertNotEquals(result1.ciphertext(), result2.ciphertext());
    }

    @Test
    void decrypt_wrongKey_throws() {
        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("key-one");
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("key-two");

        var result = EnvelopeCrypto.encrypt("secret", key1);

        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key2));
    }

    @Test
    void deriveKeyFromString_producesConsistentResults() {
        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("my-passphrase");
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("my-passphrase");

        assertArrayEquals(key1, key2);
        assertEquals(32, key1.length, "Key must be 32 bytes for AES-256");
    }

    @Test
    void deriveKeyFromString_differentInputsDifferentKeys() {
        byte[] key1 = EnvelopeCrypto.deriveKeyFromString("passphrase-a");
        byte[] key2 = EnvelopeCrypto.deriveKeyFromString("passphrase-b");

        assertFalse(Arrays.equals(key1, key2));
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
        byte[] key = EnvelopeCrypto.deriveKeyFromString("unicode-key");
        String plaintext = "こんにちは世界 🔑 Ключ";

        var result = EnvelopeCrypto.encrypt(plaintext, key);
        String decrypted = EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), key);

        assertEquals(plaintext, decrypted);
    }

    // ─── L-S1: associated data binds a ciphertext to its row ───

    private static final byte[] AAD_KEY = new byte[32];

    @Test
    void boundCiphertext_opensOnlyWithTheSameAssociatedData() {
        var result = EnvelopeCrypto.encrypt("sk-secret", AAD_KEY, "row-a");

        assertTrue(EnvelopeCrypto.isBound(result.ciphertext()));
        assertEquals("sk-secret", EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), AAD_KEY, "row-a"));
        // Copied into another row: authentication fails instead of decrypting as that
        // row's value — the swap AES-GCM without AAD allowed.
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), AAD_KEY, "row-b"));
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), AAD_KEY, null));
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(result.ciphertext(), result.iv(), AAD_KEY));
    }

    @Test
    void boundCiphertext_strippingTheMarkerDoesNotDowngradeIt() {
        var result = EnvelopeCrypto.encrypt("sk-secret", AAD_KEY, "row-a");
        String stripped = result.ciphertext().substring(EnvelopeCrypto.AAD_PREFIX.length());

        // The tag covers the associated data, so the unbound path cannot open it.
        assertThrows(EnvelopeCrypto.CryptoException.class, () -> EnvelopeCrypto.decrypt(stripped, result.iv(), AAD_KEY, "row-a"));
    }

    @Test
    void legacyCiphertext_keepsDecryptingWhateverAssociatedDataIsPassed() {
        // Every row written before AAD existed: no marker, no associated data. Passing
        // the row's binding when reading it must not break it.
        var legacy = EnvelopeCrypto.encrypt("sk-legacy", AAD_KEY);

        assertFalse(EnvelopeCrypto.isBound(legacy.ciphertext()));
        assertEquals("sk-legacy", EnvelopeCrypto.decrypt(legacy.ciphertext(), legacy.iv(), AAD_KEY, "row-a"));
    }

    @Test
    void boundDek_roundTripsAndRefusesAnotherBinding() {
        byte[] dek = EnvelopeCrypto.generateDek();
        var wrapped = EnvelopeCrypto.encryptDek(dek, AAD_KEY, "tenant-a|1");

        assertArrayEquals(dek, EnvelopeCrypto.decryptDek(wrapped.ciphertext(), wrapped.iv(), AAD_KEY, "tenant-a|1"));
        assertThrows(EnvelopeCrypto.CryptoException.class,
                () -> EnvelopeCrypto.decryptDek(wrapped.ciphertext(), wrapped.iv(), AAD_KEY, "tenant-b|1"));
    }
}
