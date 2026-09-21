/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComplianceStartupChecks — verifies construction and check execution
 * without requiring CDI or actual SSL certs.
 */
class ComplianceStartupChecksTest {

    /** Signed ledger, so the audit check stays quiet unless a test asks for it. */
    private static ComplianceStartupChecks checks(Optional<String> sslCert, boolean dbAcknowledged) {
        return checks(sslCert.map(List::of), Optional.empty(), dbAcknowledged, Optional.of("a-master-key"), true, false);
    }

    /**
     * The TLS half of the constructor takes the two keys Quarkus actually reads:
     * {@code quarkus.http.ssl.certificate.files} (a list) and
     * {@code …key-store-file}. It used to take the singular
     * {@code …certificate.file}, which no working TLS configuration sets — so the
     * warning fired for operators who had configured TLS correctly, and stayed
     * quiet for operators who had set a key Quarkus ignores.
     */
    private static ComplianceStartupChecks checks(Optional<List<String>> certFiles, Optional<String> keyStoreFile, boolean dbAcknowledged,
                                                  Optional<String> vaultKey, boolean auditEnabled, boolean signingRequired) {
        // A cert list with no explicit key list gets a matching one, so the existing
        // callers keep meaning "TLS is configured".
        Optional<List<String>> keyFiles = certFiles.map(certs -> certs.stream().map(c -> c + ".key").toList());
        return checks(certFiles, keyFiles, keyStoreFile, dbAcknowledged, vaultKey, auditEnabled, signingRequired);
    }

    private static ComplianceStartupChecks checks(Optional<List<String>> certFiles, Optional<List<String>> keyFiles, Optional<String> keyStoreFile,
                                                  boolean dbAcknowledged, Optional<String> vaultKey, boolean auditEnabled,
                                                  boolean signingRequired) {
        return new ComplianceStartupChecks(certFiles, keyFiles, keyStoreFile, dbAcknowledged, vaultKey, auditEnabled, signingRequired);
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
        var checks = checks(Optional.of(List.of("/cert.pem")), Optional.empty(), true, Optional.of(""), true, true);

        var thrown = assertThrows(IllegalStateException.class, () -> checks.onStartup(null));
        assertTrue(thrown.getMessage().contains("audit-signing-required"),
                "the failure must name the setting that caused it: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("HMAC"),
                "the failure must say what is actually missing: " + thrown.getMessage());
    }

    @Test
    @DisplayName("an absent vault key still fails when signing is required")
    void absentVaultKeyWithSigningRequiredFailsStartup() {
        var checks = checks(Optional.of(List.of("/cert.pem")), Optional.empty(), true, Optional.empty(), true, true);
        assertThrows(IllegalStateException.class, () -> checks.onStartup(null));
    }

    @Test
    @DisplayName("missing vault key only warns when signing is not required")
    void missingVaultKeyOnlyWarnsByDefault() {
        var checks = checks(Optional.of(List.of("/cert.pem")), Optional.empty(), true, Optional.empty(), true, false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("a configured vault key satisfies the requirement")
    void configuredVaultKeyPassesTheRequirement() {
        var checks = checks(Optional.of(List.of("/cert.pem")), Optional.empty(), true, Optional.of("a-master-key"), true, true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("a disabled audit ledger is not held to the signing requirement")
    void disabledAuditLedgerIsExempt() {
        var checks = checks(Optional.of(List.of("/cert.pem")), Optional.empty(), true, Optional.empty(), false, true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    /**
     * The TLS check read {@code quarkus.http.ssl.certificate.file} — singular. The
     * real Quarkus keys are {@code …certificate.files} and {@code …key-files},
     * plural, and there is no deprecated singular alias. So the warning fired for
     * every operator who had configured TLS correctly, and — worse — following the
     * banner's own advice by setting the singular key silenced the warning while
     * Quarkus ignored it entirely, leaving the check reporting satisfied on a
     * plaintext listener.
     */
    @Nested
    @DisplayName("TLS detection")
    class TlsDetectionTests {

        @Test
        @DisplayName("the PEM pair Quarkus actually reads counts as configured")
        void certificateFilesCountAsConfigured() {
            var checks = checks(Optional.of(List.of("/etc/tls/cert.pem")), Optional.empty(), true, Optional.of("k"), true, false);
            assertDoesNotThrow(() -> checks.onStartup(null));
            assertTrue(tlsConfigured(checks), "quarkus.http.ssl.certificate.files is how a PEM-based TLS listener is configured");
        }

        @Test
        @DisplayName("a keystore counts as configured too")
        void keyStoreCountsAsConfigured() {
            var checks = checks(Optional.empty(), Optional.empty(), Optional.of("/etc/tls/keystore.p12"), true, Optional.of("k"), true, false);
            assertTrue(tlsConfigured(checks), "a keystore is the other supported way to terminate TLS in Quarkus");
        }

        @Test
        @DisplayName("no certificate at all is reported as unconfigured")
        void absentCertificateIsUnconfigured() {
            assertFalse(tlsConfigured(checks(Optional.empty(), Optional.empty(), true, Optional.of("k"), true, false)));
        }

        /**
         * An empty list and a blank path are what a half-written Helm values file
         * produces. Neither configures TLS, so neither may silence the warning.
         */
        @Test
        @DisplayName("an empty or blank value does not silence the warning")
        void emptyOrBlankIsUnconfigured() {
            assertFalse(tlsConfigured(checks(Optional.of(List.of()), Optional.empty(), true, Optional.of("k"), true, false)),
                    "an empty file list configures nothing");
            assertFalse(tlsConfigured(checks(Optional.of(List.of("   ")), Optional.empty(), true, Optional.of("k"), true, false)),
                    "a blank path configures nothing");
            assertFalse(tlsConfigured(checks(Optional.empty(), Optional.of(" "), true, Optional.of("k"), true, false)),
                    "a blank keystore path configures nothing");
        }

        /**
         * A certificate on its own does not start a TLS listener. Accepting it would be
         * the same fail-open the singular property name produced: the check reports
         * satisfied while the listener is still plaintext.
         */
        @Test
        @DisplayName("a certificate without its key is not TLS")
        void certificateWithoutKeyIsNotConfigured() {
            var checks = checks(Optional.of(List.of("/etc/tls/cert.pem")), Optional.empty(), Optional.empty(), true, Optional.of("k"), true,
                    false);
            assertFalse(tlsConfigured(checks), "quarkus.http.ssl.certificate.files alone does not configure TLS");
        }

        /**
         * Quarkus pairs the two lists by position, so two certificates and one key is a
         * half-configured listener rather than a working one.
         */
        @Test
        @DisplayName("mismatched certificate and key counts are not TLS")
        void mismatchedCardinalityIsNotConfigured() {
            var checks = checks(Optional.of(List.of("/etc/tls/a.pem", "/etc/tls/b.pem")), Optional.of(List.of("/etc/tls/a.key")),
                    Optional.empty(), true, Optional.of("k"), true, false);
            assertFalse(tlsConfigured(checks), "two certificates and one key do not pair up");
        }

        @Test
        @DisplayName("a blank entry on either side is not TLS")
        void blankEntryOnEitherSideIsNotConfigured() {
            assertFalse(tlsConfigured(checks(Optional.of(List.of("  ")), Optional.of(List.of("/etc/tls/a.key")), Optional.empty(), true,
                    Optional.of("k"), true, false)), "a blank certificate path configures nothing");
            assertFalse(tlsConfigured(checks(Optional.of(List.of("/etc/tls/a.pem")), Optional.of(List.of("")), Optional.empty(), true,
                    Optional.of("k"), true, false)), "a blank key path configures nothing");
        }

        @Test
        @DisplayName("a matching pair is TLS")
        void matchingPemPairIsConfigured() {
            var checks = checks(Optional.of(List.of("/etc/tls/a.pem")), Optional.of(List.of("/etc/tls/a.key")), Optional.empty(), true,
                    Optional.of("k"), true, false);
            assertTrue(tlsConfigured(checks));
        }

        private boolean tlsConfigured(ComplianceStartupChecks checks) {
            try {
                var field = ComplianceStartupChecks.class.getDeclaredField("tlsConfigured");
                field.setAccessible(true);
                return field.getBoolean(checks);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("the TLS verdict field was renamed; update this test", e);
            }
        }
    }
}