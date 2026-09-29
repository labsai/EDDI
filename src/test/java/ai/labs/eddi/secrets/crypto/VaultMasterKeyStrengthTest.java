/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the vault master-key strength gate (finding #5). Pure, so no
 * container is required.
 */
class VaultMasterKeyStrengthTest {

    @Test
    @DisplayName("a strong, unique passphrase is accepted")
    void strongKeyAccepted() {
        assertTrue(VaultMasterKeyStrength.weakness("Zx9!kQ2m-Rt7pLw3aVn8Bd6").isEmpty());
        // The 64-char hex key used by the integration test profiles must pass.
        assertTrue(VaultMasterKeyStrength.weakness("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef").isEmpty());
    }

    @Test
    @DisplayName("the publicly-documented docker-compose demo key is rejected")
    void demoKeyRejected() {
        Optional<String> reason = VaultMasterKeyStrength.weakness("demo-only-vault-master-key-not-for-production-use");
        assertTrue(reason.isPresent());
        assertTrue(reason.get().toLowerCase().contains("demo") || reason.get().toLowerCase().contains("public"), reason.get());
    }

    @Test
    @DisplayName("the demo key is rejected case-insensitively and with surrounding whitespace")
    void demoKeyRejectedTrimmedAndCaseInsensitive() {
        assertTrue(VaultMasterKeyStrength.weakness("  DEMO-ONLY-VAULT-MASTER-KEY-NOT-FOR-PRODUCTION-USE  ").isPresent());
    }

    @Test
    @DisplayName("common placeholders are rejected")
    void placeholdersRejected() {
        assertTrue(VaultMasterKeyStrength.weakness("changeme").isPresent());
        assertTrue(VaultMasterKeyStrength.weakness("password").isPresent());
        assertTrue(VaultMasterKeyStrength.weakness("dev-passphrase").isPresent());
    }

    @Test
    @DisplayName("a key shorter than the minimum length is rejected")
    void shortKeyRejected() {
        assertTrue(VaultMasterKeyStrength.weakness("short-abc").isPresent());
        // Exactly one under the floor.
        assertTrue(VaultMasterKeyStrength.weakness("a1b2c3d4e5f6g7").isPresent());
    }

    @Test
    @DisplayName("a long but very low-entropy key is rejected")
    void lowEntropyKeyRejected() {
        assertTrue(VaultMasterKeyStrength.weakness("aaaaaaaaaaaaaaaaaaaaaaaaaaaa").isPresent());
        assertTrue(VaultMasterKeyStrength.weakness("ababababababababababab").isPresent());
    }

    @Test
    @DisplayName("null and blank are reported as weaknesses")
    void nullAndBlankRejected() {
        assertTrue(VaultMasterKeyStrength.weakness(null).isPresent());
        assertTrue(VaultMasterKeyStrength.weakness("   ").isPresent());
        assertFalse(VaultMasterKeyStrength.weakness("Zx9!kQ2m-Rt7pLw3aVn8Bd6").isPresent());
    }
}
