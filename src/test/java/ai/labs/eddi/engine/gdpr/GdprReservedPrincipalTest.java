/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.connections.ConnectionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Principals that are not people are refused by erasure and export: erasing
 * {@code __service__} would delete every service grant, and erasing a dedicated
 * mailbox's {@code mailbox:<address>} would silently unlink it and stop its
 * email integration.
 */
class GdprReservedPrincipalTest {

    @Test
    @DisplayName("the service principal and a dedicated mailbox's grant principal are reserved, any case")
    void reserved() {
        assertTrue(GdprComplianceService.isReservedPrincipal(ConnectionResolver.SERVICE_PRINCIPAL));
        assertTrue(GdprComplianceService.isReservedPrincipal("mailbox:eddi@eddi.solutions"));
        assertTrue(GdprComplianceService.isReservedPrincipal("MAILBOX:eddi@eddi.solutions"));
    }

    @Test
    @DisplayName("a person, including the mailbox address as a sender identity, is not")
    void people() {
        assertFalse(GdprComplianceService.isReservedPrincipal("alice"));
        assertFalse(GdprComplianceService.isReservedPrincipal("eddi@eddi.solutions"));
        assertFalse(GdprComplianceService.isReservedPrincipal("email:int-1:eddi@eddi.solutions"));
        assertFalse(GdprComplianceService.isReservedPrincipal(null));
    }
}
