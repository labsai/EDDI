/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Integrity/dedup checksum for stored secrets.
 * <p>
 * The vault used to store a <em>plain</em> {@code SHA-256(plaintext)} beside
 * every ciphertext and hand it out over REST. {@code scope: secret} auto-vaults
 * low-entropy user values (PINs, short passwords), so a plain digest is
 * brute-forceable offline by anyone with database read access, and — being
 * unsalted and un-keyed — it is <em>identical</em> for equal values across rows
 * and across tenants, leaking which secrets are the same.
 * <p>
 * This computes a <b>keyed</b> checksum instead: {@code HMAC-SHA256} over
 * {@code tenantId + NUL + plaintext} under a key the caller derives from the
 * vault KEK. An attacker without the key can neither brute-force a value nor
 * test a guess, and binding the tenant id into the MAC means equal plaintexts
 * in different tenants produce different checksums.
 * <p>
 * <b>Backward compatibility.</b> Keyed checksums carry the
 * {@value #KEYED_PREFIX} version prefix. A stored value <em>without</em> that
 * prefix is a legacy bare SHA-256 hex digest, still recognised by
 * {@link #matches}. New writes are always keyed; a legacy row migrates to the
 * keyed form the next time its value is stored. Dedup and value-match therefore
 * keep working across the upgrade without a migration pass.
 *
 * @since 6.5.0
 */
public final class VaultChecksum {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Version marker on a keyed checksum; its absence means legacy SHA-256. */
    public static final String KEYED_PREFIX = "h1:";

    private VaultChecksum() {
        // Utility class
    }

    /**
     * The current (keyed) checksum for a plaintext under a tenant.
     *
     * @param checksumKey
     *            the HMAC key, derived from the vault KEK (see
     *            {@link #deriveKey(byte[])})
     * @param tenantId
     *            the owning tenant, bound into the MAC so equal values in different
     *            tenants do not share a checksum
     * @param plaintext
     *            the secret value
     * @return {@value #KEYED_PREFIX} followed by the hex HMAC
     */
    public static String compute(byte[] checksumKey, String tenantId, String plaintext) {
        String data = (tenantId == null ? "" : tenantId) + "\u0000" + (plaintext == null ? "" : plaintext);
        return KEYED_PREFIX + hmacHex(checksumKey, data);
    }

    /**
     * The legacy bare SHA-256 hex digest of a plaintext — retained only to verify
     * checksums written before keyed checksums existed.
     */
    public static String legacy(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Whether {@code storedChecksum} is the checksum of {@code plaintext},
     * selecting the keyed or legacy scheme by the stored value's prefix. The
     * comparison is constant-time within each scheme.
     *
     * @param checksumKey
     *            the HMAC key for the keyed scheme; may be {@code null} only when
     *            the stored checksum is known to be legacy
     * @param tenantId
     *            the owning tenant (must match what {@link #compute} was given)
     * @param storedChecksum
     *            the checksum read from the row
     * @param plaintext
     *            the candidate value
     * @return true when they match
     */
    public static boolean matches(byte[] checksumKey, String tenantId, String storedChecksum, String plaintext) {
        if (storedChecksum == null || storedChecksum.isEmpty()) {
            return false;
        }
        String expected;
        if (storedChecksum.startsWith(KEYED_PREFIX)) {
            if (checksumKey == null) {
                return false;
            }
            expected = compute(checksumKey, tenantId, plaintext);
        } else {
            expected = legacy(plaintext);
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), storedChecksum.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Derive the checksum HMAC key from the vault KEK, domain-separated so it is
     * independent of the KEK's other uses (DEK wrapping) and of the audit HMAC key.
     * A single fixed derivation input is enough — the KEK is already high-entropy
     * and per-deployment.
     *
     * @param kek
     *            the 32-byte vault KEK
     * @return the 32-byte checksum key
     */
    public static byte[] deriveKey(byte[] kek) {
        return hmac(kek, "eddi-vault-checksum-key-v1".getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmac(key, data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}
