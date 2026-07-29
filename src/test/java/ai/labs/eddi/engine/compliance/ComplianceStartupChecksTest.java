/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComplianceStartupChecks — verifies construction and check execution
 * without requiring CDI or actual SSL certs.
 */
class ComplianceStartupChecksTest {

    /** Signed ledger, so the audit check stays quiet unless a test asks for it. */
    private static ComplianceStartupChecks checks(Optional<String> sslCert, boolean dbAcknowledged) {
        return new ComplianceStartupChecks(sslCert, dbAcknowledged, Optional.of("a-master-key"), true, false);
    }

    @Test
    @DisplayName("construction with no SSL cert — does not throw")
    void noSslCert() {
        var checks = checks(Optional.empty(), false);
        // Should not throw — warnings are logged, not thrown
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("construction with SSL cert configured — does not throw")
    void withSslCert() {
        var checks = checks(Optional.of("/path/to/cert.pem"), true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("db encryption acknowledged suppresses warning")
    void dbEncryptionAcknowledged() {
        var checks = checks(Optional.of("/cert.pem"), true);
        // No assertion needed — just verifying no exception
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("blank SSL cert triggers TLS warning path")
    void blankSslCert() {
        var checks = checks(Optional.of(""), false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("null event parameter is handled gracefully")
    void nullEvent() {
        var checks = checks(Optional.empty(), false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    // ==================== G19: unsigned audit ledger ====================

    /**
     * {@code eddi.vault.master-key} ships empty, so out of the box every audit
     * entry is written without an HMAC and tampering is undetectable — while the
     * ledger is documented as evidence-grade. A deployment that depends on that
     * evidence sets {@code eddi.compliance.audit-signing-required=true} and must be
     * stopped at startup rather than silently keeping an unsigned ledger.
     */
    @Test
    @DisplayName("missing vault key with signing required fails startup")
    void missingVaultKeyWithSigningRequiredFailsStartup() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true, Optional.of(""), true, true);

        var thrown = assertThrows(IllegalStateException.class, () -> checks.onStartup(null));
        assertTrue(thrown.getMessage().contains("audit-signing-required"),
                "the failure must name the setting that caused it: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("HMAC"),
                "the failure must say what is actually missing: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an absent vault key still fails when signing is required")
    void absentVaultKeyWithSigningRequiredFailsStartup() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true, Optional.empty(), true, true);
        assertThrows(IllegalStateException.class, () -> checks.onStartup(null));
    }

    @Test
    @DisplayName("missing vault key only warns when signing is not required")
    void missingVaultKeyOnlyWarnsByDefault() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true, Optional.empty(), true, false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("a configured vault key satisfies the requirement")
    void configuredVaultKeyPassesTheRequirement() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true, Optional.of("a-master-key"), true, true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("a disabled audit ledger is not held to the signing requirement")
    void disabledAuditLedgerIsExempt() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true, Optional.empty(), false, true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }
}
