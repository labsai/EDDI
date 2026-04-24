/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

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
     * Encrypt plaintext using AES-256-GCM with the given key.
     *
     * @param plaintext
     *            the data to encrypt
     * @param key
     *            the 32-byte AES-256 key
     * @return encrypted result containing Base64-encoded ciphertext and IV
     */
    public static EncryptionResult encrypt(String plaintext, byte[] key) {
        validateKey(key);
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return new EncryptionResult(Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(iv));
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
        validateKey(key);
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encryptedValue);
            byte[] iv = Base64.getDecoder().decode(ivBase64);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CryptoException("Decryption failed", e);
        }
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
        return encrypt(Base64.getEncoder().encodeToString(dek), kek);
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
        String dekBase64 = decrypt(encryptedDek, ivBase64, kek);
        return Base64.getDecoder().decode(dekBase64);
    }

    /**
     * Compute SHA-256 hex digest of plaintext (for integrity checks without
     * exposing the value).
     */
    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(keyString.toCharArray(), salt, 600_000, 256);
            return factory.generateSecret(spec).getEncoded();
        } catch (java.security.NoSuchAlgorithmException | java.security.spec.InvalidKeySpecException e) {
            throw new CryptoException("PBKDF2 key derivation failed", e);
        }
    }

    /**
     * Fixed, legacy salt — used for backward compatibility with pre-6.0.2
     * deployments.
     */
    private static final byte[] LEGACY_SALT = "eddi-vault-kek-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

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
