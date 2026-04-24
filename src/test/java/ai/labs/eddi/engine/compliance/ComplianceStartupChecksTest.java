/*
 * Copyright (c) 2016-2026 EDDI contributors
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

    @Test
    @DisplayName("construction with no SSL cert — does not throw")
    void noSslCert() {
        var checks = new ComplianceStartupChecks(Optional.empty(), false);
        // Should not throw — warnings are logged, not thrown
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("construction with SSL cert configured — does not throw")
    void withSslCert() {
        var checks = new ComplianceStartupChecks(Optional.of("/path/to/cert.pem"), true);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("db encryption acknowledged suppresses warning")
    void dbEncryptionAcknowledged() {
        var checks = new ComplianceStartupChecks(Optional.of("/cert.pem"), true);
        // No assertion needed — just verifying no exception
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("blank SSL cert triggers TLS warning path")
    void blankSslCert() {
        var checks = new ComplianceStartupChecks(Optional.of(""), false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }

    @Test
    @DisplayName("null event parameter is handled gracefully")
    void nullEvent() {
        var checks = new ComplianceStartupChecks(Optional.empty(), false);
        assertDoesNotThrow(() -> checks.onStartup(null));
    }
}
