/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;

/**
 * Stateless utility for AES-256-GCM envelope encryption.
 * <p>
 * The envelope model uses a two-tier key hierarchy:
 * <ul>
 * <li><b>KEK (Key Encryption Key)</b> — Master key from environment variable,
 * encrypts DEKs</li>
 * <li><b>DEK (Data Encryption Key)</b> — Per-tenant key, encrypts actual
 * secrets</li>
 * </ul>
 * <p>
 * AES-256-GCM provides authenticated encryption (confidentiality + integrity).
 * Each encryption uses a unique 12-byte IV and produces a 16-byte auth tag.
 */
public final class EnvelopeCrypto {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_LENGTH_BITS = 256;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EnvelopeCrypto() {
        // Utility class
    }

    /**
     * Prefix of a ciphertext sealed with associated data (AAD).
     * <p>
     * Base64 never contains a colon, so the marker cannot collide with the
     * unprefixed form every ciphertext written before AAD existed still uses — and
     * that form keeps decrypting, without AAD, so no stored row has to be migrated
     * for this to ship. Stripping the marker does not downgrade a bound ciphertext
     * either: its tag was computed over the AAD, so opening it without the AAD
     * fails authentication.
     */
    static final String AAD_PREFIX = "a1:";

    /**
     * Encrypt plaintext using AES-256-GCM with the given key.
     *
     * @param plaintext
     *            the data to encrypt
     * @param key
     *            the 32-byte AES-256 key
     * @return encrypted result containing Base64-encoded ciphertext and IV
     */
    public static EncryptionResult encrypt(String plaintext, byte[] key) {
        return encrypt(plaintext, key, null);
    }

    /**
     * Encrypt plaintext using AES-256-GCM, binding the ciphertext to
     * {@code associatedData}.
     * <p>
     * The associated data is not stored, only authenticated: the same value must be
     * supplied to {@link #decrypt(String, String, byte[], String)}. Callers pass
     * the identity of the row the ciphertext is stored in, so that a ciphertext
     * copied into another row by someone with write access to the database fails
     * authentication instead of decrypting as that row's value.
     *
     * @param associatedData
     *            what the ciphertext is bound to, or {@code null} for the unbound
     *            legacy form
     */
    public static EncryptionResult encrypt(String plaintext, byte[] key, String associatedData) {
        validateKey(key);
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            String encoded = Base64.getEncoder().encodeToString(ciphertext);
            return new EncryptionResult(associatedData != null ? AAD_PREFIX + encoded : encoded, Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    /**
     * Decrypt AES-256-GCM ciphertext using the given key.
     *
     * @param encryptedValue
     *            Base64-encoded ciphertext (includes auth tag)
     * @param ivBase64
     *            Base64-encoded 12-byte IV
     * @param key
     *            the 32-byte AES-256 key
     * @return the decrypted plaintext
     */
    public static String decrypt(String encryptedValue, String ivBase64, byte[] key) {
        return decrypt(encryptedValue, ivBase64, key, null);
    }

    /**
     * Decrypt AES-256-GCM ciphertext, authenticating the associated data it was
     * bound to.
     * <p>
     * A ciphertext carrying the {@value #AAD_PREFIX} marker is opened with
     * {@code associatedData} and fails unless that is the value it was sealed with
     * — including when none is supplied at all. An unmarked ciphertext predates AAD
     * and is opened without it, whatever is passed, which is what keeps every row
     * written before this change readable.
     *
     * @param associatedData
     *            what the ciphertext must be bound to
     */
    public static String decrypt(String encryptedValue, String ivBase64, byte[] key, String associatedData) {
        validateKey(key);
        try {
            boolean bound = isBound(encryptedValue);
            if (bound && associatedData == null) {
                throw new CryptoException("Ciphertext is bound to associated data, but none was supplied");
            }
            byte[] ciphertext = Base64.getDecoder().decode(bound ? encryptedValue.substring(AAD_PREFIX.length()) : encryptedValue);
            byte[] iv = Base64.getDecoder().decode(ivBase64);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);
            if (bound) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Decryption failed", e);
        }
    }

    /**
     * Whether a stored ciphertext is bound to associated data — false for every
     * value written before AAD existed.
     */
    public static boolean isBound(String encryptedValue) {
        return encryptedValue != null && encryptedValue.startsWith(AAD_PREFIX);
    }

    /**
     * Generate a new random 256-bit AES key (for use as a DEK).
     *
     * @return the raw 32-byte key
     */
    public static byte[] generateDek() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(AES_KEY_LENGTH_BITS, SECURE_RANDOM);
            SecretKey key = keyGen.generateKey();
            return key.getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("AES key generation failed", e);
        }
    }

    /**
     * Encrypt a DEK using the KEK (Master Key).
     *
     * @param dek
     *            the raw DEK bytes
     * @param kek
     *            the raw KEK bytes (32 bytes)
     * @return encrypted result
     */
    public static EncryptionResult encryptDek(byte[] dek, byte[] kek) {
        return encryptDek(dek, kek, null);
    }

    /**
     * Encrypt a DEK using the KEK, bound to {@code associatedData} — see
     * {@link #encrypt(String, byte[], String)}.
     */
    public static EncryptionResult encryptDek(byte[] dek, byte[] kek, String associatedData) {
        return encrypt(Base64.getEncoder().encodeToString(dek), kek, associatedData);
    }

    /**
     * Decrypt a DEK using the KEK (Master Key).
     *
     * @param encryptedDek
     *            Base64-encoded encrypted DEK
     * @param ivBase64
     *            Base64-encoded IV
     * @param kek
     *            the raw KEK bytes (32 bytes)
     * @return the raw DEK bytes
     */
    public static byte[] decryptDek(String encryptedDek, String ivBase64, byte[] kek) {
        return decryptDek(encryptedDek, ivBase64, kek, null);
    }

    /**
     * Decrypt a DEK using the KEK, authenticating {@code associatedData} when the
     * wrapped DEK was bound to it — see
     * {@link #decrypt(String, String, byte[], String)}.
     */
    public static byte[] decryptDek(String encryptedDek, String ivBase64, byte[] kek, String associatedData) {
        String dekBase64 = decrypt(encryptedDek, ivBase64, kek, associatedData);
        return Base64.getDecoder().decode(dekBase64);
    }

    /**
     * Compute SHA-256 hex digest of plaintext (for integrity checks without
     * exposing the value).
     */
    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CryptoException("SHA-256 not available", e);
        }
    }

    /**
     * Derive a 32-byte key from an arbitrary-length string using a
     * <b>per-deployment random salt</b>. Uses PBKDF2WithHmacSHA256 with 600,000
     * iterations for brute-force resistance.
     *
     * @param keyString
     *            the passphrase / master key string
     * @param salt
     *            per-deployment random salt (should be at least 16 bytes)
     * @return derived 32-byte AES-256 key
     */
    public static byte[] deriveKeyFromString(String keyString, byte[] salt) {
        if (salt == null || salt.length < 8) {
            throw new CryptoException("PBKDF2 salt must be at least 8 bytes, got " + (salt == null ? "null" : salt.length));
        }
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(keyString.toCharArray(), salt, 600_000, 256);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new CryptoException("PBKDF2 key derivation failed", e);
        }
    }

    /**
     * Fixed, legacy salt — used for backward compatibility with pre-6.0.2
     * deployments.
     */
    private static final byte[] LEGACY_SALT = "eddi-vault-kek-v1".getBytes(StandardCharsets.UTF_8);

    /**
     * Derive a 32-byte key using the <b>legacy fixed salt</b>. New deployments
     * should use {@link #deriveKeyFromString(String, byte[])} with a random
     * per-deployment salt for stronger security.
     *
     * @param keyString
     *            the passphrase / master key string
     * @return derived 32-byte AES-256 key
     * @deprecated Use {@link #deriveKeyFromString(String, byte[])} with a random
     *             salt
     */
    @Deprecated(since = "6.0.2")
    public static byte[] deriveKeyFromString(String keyString) {
        return deriveKeyFromString(keyString, LEGACY_SALT);
    }

    private static byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new CryptoException("AES-256 key must be exactly 32 bytes, got " + (key == null ? "null" : key.length));
        }
    }

    /**
     * Result of an encryption operation.
     *
     * @param ciphertext
     *            Base64-encoded ciphertext (includes GCM auth tag)
     * @param iv
     *            Base64-encoded 12-byte initialization vector
     */
    public record EncryptionResult(String ciphertext, String iv) {
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message) {
            super(message);
        }

        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
