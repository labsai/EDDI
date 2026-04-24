/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestGdprAdmin}. Verifies input validation and
 * delegation to {@link GdprComplianceService}.
 *
 * @author ginccc
 * @since 6.0.0
 */
class RestGdprAdminTest {

    private GdprComplianceService gdprService;
    private RestGdprAdmin restAdmin;

    @BeforeEach
    void setUp() {
        gdprService = mock(GdprComplianceService.class);
        restAdmin = new RestGdprAdmin(gdprService);
    }

    // ==================== Input Validation ====================

    @Test
    void deleteUserData_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.deleteUserData(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.deleteUserData("   "));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.exportUserData(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.exportUserData(""));
        verifyNoInteractions(gdprService);
    }

    // ==================== Delegation ====================

    @Test
    void deleteUserData_delegatesToService() {
        var expected = new GdprDeletionResult("user-1", 5, 3, 2, 10, 15,
                Instant.now());
        when(gdprService.deleteUserData("user-1")).thenReturn(expected);

        GdprDeletionResult result = restAdmin.deleteUserData("user-1");

        assertSame(expected, result);
        verify(gdprService).deleteUserData("user-1");
    }

    @Test
    void exportUserData_delegatesToService() {
        var expected = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of());
        when(gdprService.exportUserData("user-1")).thenReturn(expected);

        UserDataExport result = restAdmin.exportUserData("user-1");

        assertSame(expected, result);
        verify(gdprService).exportUserData("user-1");
    }

    // ==================== Restriction endpoints ====================

    @Test
    void restrictProcessing_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.restrictProcessing("  "));
        verifyNoInteractions(gdprService);
    }

    @Test
    void restrictProcessing_delegatesToService() {
        restAdmin.restrictProcessing("user-1");
        verify(gdprService).restrictProcessing("user-1");
    }

    @Test
    void unrestrictProcessing_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.unrestrictProcessing(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void unrestrictProcessing_delegatesToService() {
        restAdmin.unrestrictProcessing("user-1");
        verify(gdprService).unrestrictProcessing("user-1");
    }

    @Test
    void isProcessingRestricted_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.isProcessingRestricted(""));
        verifyNoInteractions(gdprService);
    }

    @Test
    void isProcessingRestricted_delegatesToService() {
        when(gdprService.isProcessingRestricted("user-1")).thenReturn(true);
        assertTrue(restAdmin.isProcessingRestricted("user-1"));
        verify(gdprService).isProcessingRestricted("user-1");
    }

    @Test
    void isProcessingRestricted_returnsFalse() {
        when(gdprService.isProcessingRestricted("user-1")).thenReturn(false);
        assertFalse(restAdmin.isProcessingRestricted("user-1"));
    }
}
