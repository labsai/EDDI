/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the keyed secret checksum (finding #2).
 */
class VaultChecksumTest {

    private static byte[] key(String seed) {
        // A deterministic 32-byte key per seed, so tests are reproducible.
        byte[] k = new byte[32];
        new SecureRandom(seed.getBytes()).nextBytes(k);
        return k;
    }

    @Test
    @DisplayName("a keyed checksum carries the version prefix and is not the plaintext's SHA-256")
    void keyedFormatIsVersionedAndNotPlainSha() {
        byte[] k = key("k1");
        String checksum = VaultChecksum.compute(k, "default", "hunter2");
        assertTrue(checksum.startsWith(VaultChecksum.KEYED_PREFIX), checksum);
        assertFalse(checksum.substring(VaultChecksum.KEYED_PREFIX.length()).equals(VaultChecksum.legacy("hunter2")),
                "a keyed checksum must not equal the plain SHA-256 an attacker could precompute");
    }

    @Test
    @DisplayName("the checksum is unforgeable without the key")
    void differentKeysProduceDifferentChecksums() {
        assertNotEquals(VaultChecksum.compute(key("k1"), "default", "1234"), VaultChecksum.compute(key("k2"), "default", "1234"));
    }

    @Test
    @DisplayName("equal values in different tenants do NOT share a checksum")
    void tenantBindingDefeatsCrossTenantLinkage() {
        byte[] k = key("k1");
        assertNotEquals(VaultChecksum.compute(k, "tenantA", "same-pin"), VaultChecksum.compute(k, "tenantB", "same-pin"));
    }

    @Test
    @DisplayName("matches verifies a keyed checksum and rejects a wrong value")
    void matchesKeyed() {
        byte[] k = key("k1");
        String checksum = VaultChecksum.compute(k, "default", "correct-value");
        assertTrue(VaultChecksum.matches(k, "default", checksum, "correct-value"));
        assertFalse(VaultChecksum.matches(k, "default", checksum, "wrong-value"));
        // Wrong tenant must not match either.
        assertFalse(VaultChecksum.matches(k, "other", checksum, "correct-value"));
    }

    @Test
    @DisplayName("a keyed checksum cannot be verified without the key")
    void keyedRequiresKey() {
        byte[] k = key("k1");
        String checksum = VaultChecksum.compute(k, "default", "v");
        assertFalse(VaultChecksum.matches(null, "default", checksum, "v"));
        assertFalse(VaultChecksum.matches(key("other"), "default", checksum, "v"));
    }

    @Test
    @DisplayName("legacy bare-SHA-256 checksums still verify (back-compat), key or no key")
    void matchesLegacy() {
        String legacy = VaultChecksum.legacy("legacy-value");
        assertTrue(VaultChecksum.matches(key("k1"), "default", legacy, "legacy-value"));
        assertTrue(VaultChecksum.matches(null, "default", legacy, "legacy-value"), "legacy verification needs no key");
        assertFalse(VaultChecksum.matches(null, "default", legacy, "other-value"));
    }

    @Test
    @DisplayName("null/blank stored checksum never matches")
    void nullStoredChecksumNeverMatches() {
        assertFalse(VaultChecksum.matches(key("k1"), "default", null, "v"));
        assertFalse(VaultChecksum.matches(key("k1"), "default", "", "v"));
    }

    @Test
    @DisplayName("legacy(plaintext) matches the documented SHA-256 hex")
    void legacyIsStableSha256() {
        // SHA-256("") — the well-known empty-string digest — anchors the legacy scheme.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", VaultChecksum.legacy(""));
    }
}
