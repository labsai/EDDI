/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OwnershipValidator}.
 *
 * @author ginccc
 */
class OwnershipValidatorTest {

    private static final String CALLER_ID = "user-123";
    private static final String OTHER_USER = "user-456";

    // -- helpers --

    private SecurityIdentity authenticatedIdentity(String principalName, boolean isAdmin) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(principalName);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole("eddi-admin")).thenReturn(isAdmin);
        return identity;
    }

    private SecurityIdentity anonymousIdentity() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        return identity;
    }

    // ==================== isAuthEnabled ====================

    @Nested
    @DisplayName("isAuthEnabled()")
    class IsAuthEnabled {

        @Test
        @DisplayName("returns true when constructed with authEnabled=true")
        void returnsTrue_whenAuthEnabled() {
            var validator = new OwnershipValidator(true);
            assertTrue(validator.isAuthEnabled());
        }

        @Test
        @DisplayName("returns false when constructed with authEnabled=false")
        void returnsFalse_whenAuthDisabled() {
            var validator = new OwnershipValidator(false);
            assertFalse(validator.isAuthEnabled());
        }
    }

    // ==================== validateUserAccess ====================

    @Nested
    @DisplayName("validateUserAccess()")
    class ValidateUserAccess {

        @Test
        @DisplayName("does not throw when auth is disabled")
        void noOp_whenAuthDisabled() {
            var validator = new OwnershipValidator(false);
            assertDoesNotThrow(() -> validator.validateUserAccess(null, OTHER_USER));
        }

        @Test
        @DisplayName("does not throw when auth is disabled, even with mismatched identity")
        void noOp_whenAuthDisabled_withMismatchedIdentity() {
            var validator = new OwnershipValidator(false);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertDoesNotThrow(() -> validator.validateUserAccess(identity, OTHER_USER));
        }

        @Test
        @DisplayName("does not throw when identity is null (lets @RolesAllowed handle)")
        void noOp_whenIdentityIsNull() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.validateUserAccess(null, OTHER_USER));
        }

        @Test
        @DisplayName("does not throw when identity is anonymous")
        void noOp_whenAnonymous() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.validateUserAccess(anonymousIdentity(), OTHER_USER));
        }

        @Test
        @DisplayName("does not throw when caller is admin, even with userId mismatch")
        void noOp_whenAdmin() {
            var validator = new OwnershipValidator(true);
            var admin = authenticatedIdentity(CALLER_ID, true);
            assertDoesNotThrow(() -> validator.validateUserAccess(admin, OTHER_USER));
        }

        @Test
        @DisplayName("does not throw when caller matches requestedUserId")
        void noOp_whenCallerMatchesUserId() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertDoesNotThrow(() -> validator.validateUserAccess(identity, CALLER_ID));
        }

        @Test
        @DisplayName("throws ForbiddenException when caller does not match requestedUserId")
        void throws_whenCallerDoesNotMatchUserId() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);

            var ex = assertThrows(ForbiddenException.class,
                    () -> validator.validateUserAccess(identity, OTHER_USER));
            assertTrue(ex.getMessage().contains("do not own"));
        }

        @Test
        @DisplayName("throws ForbiddenException when requestedUserId is null (caller doesn't match null)")
        void throws_whenRequestedUserIdIsNull() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);

            assertThrows(ForbiddenException.class,
                    () -> validator.validateUserAccess(identity, null));
        }
    }

    // ==================== validateAndResolveUserId ====================

    @Nested
    @DisplayName("validateAndResolveUserId()")
    class ValidateAndResolveUserId {

        @Test
        @DisplayName("returns requestedUserId as-is when auth is disabled")
        void returnsRequestedUserId_whenAuthDisabled() {
            var validator = new OwnershipValidator(false);
            assertEquals(OTHER_USER, validator.validateAndResolveUserId(null, OTHER_USER));
        }

        @Test
        @DisplayName("returns null when auth is disabled and requestedUserId is null")
        void returnsNull_whenAuthDisabled_andRequestedUserIdIsNull() {
            var validator = new OwnershipValidator(false);
            assertNull(validator.validateAndResolveUserId(null, null));
        }

        @Test
        @DisplayName("returns requestedUserId as-is when identity is null")
        void returnsRequestedUserId_whenIdentityIsNull() {
            var validator = new OwnershipValidator(true);
            assertEquals(OTHER_USER, validator.validateAndResolveUserId(null, OTHER_USER));
        }

        @Test
        @DisplayName("returns requestedUserId as-is when identity is anonymous")
        void returnsRequestedUserId_whenAnonymous() {
            var validator = new OwnershipValidator(true);
            assertEquals(OTHER_USER, validator.validateAndResolveUserId(anonymousIdentity(), OTHER_USER));
        }

        @Test
        @DisplayName("returns caller's principal name when requestedUserId is null")
        void returnsCallerId_whenRequestedUserIdIsNull() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertEquals(CALLER_ID, validator.validateAndResolveUserId(identity, null));
        }

        @Test
        @DisplayName("returns caller's principal name when requestedUserId is blank")
        void returnsCallerId_whenRequestedUserIdIsBlank() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertEquals(CALLER_ID, validator.validateAndResolveUserId(identity, "   "));
        }

        @Test
        @DisplayName("admin can impersonate — returns requestedUserId even if different")
        void returnsRequestedUserId_whenAdminImpersonates() {
            var validator = new OwnershipValidator(true);
            var admin = authenticatedIdentity(CALLER_ID, true);
            assertEquals(OTHER_USER, validator.validateAndResolveUserId(admin, OTHER_USER));
        }

        @Test
        @DisplayName("returns requestedUserId when caller matches")
        void returnsRequestedUserId_whenCallerMatches() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertEquals(CALLER_ID, validator.validateAndResolveUserId(identity, CALLER_ID));
        }

        @Test
        @DisplayName("throws ForbiddenException when non-admin caller does not match requestedUserId")
        void throws_whenNonAdminCallerMismatch() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);

            var ex = assertThrows(ForbiddenException.class,
                    () -> validator.validateAndResolveUserId(identity, OTHER_USER));
            assertTrue(ex.getMessage().contains("cannot start a conversation as another user"));
        }
    }

    // ==================== requireOwnerOrAdmin ====================

    @Nested
    @DisplayName("requireOwnerOrAdmin()")
    class RequireOwnerOrAdmin {

        @Test
        @DisplayName("does not throw when auth is disabled")
        void noOp_whenAuthDisabled() {
            var validator = new OwnershipValidator(false);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(
                    authenticatedIdentity(CALLER_ID, false), OTHER_USER, "agent"));
        }

        @Test
        @DisplayName("does not throw when resourceOwnerId is null (legacy data)")
        void noOp_whenOwnerIdIsNull() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(
                    authenticatedIdentity(CALLER_ID, false), null, "agent"));
        }

        @Test
        @DisplayName("does not throw when resourceOwnerId is blank (legacy data)")
        void noOp_whenOwnerIdIsBlank() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(
                    authenticatedIdentity(CALLER_ID, false), "  ", "agent"));
        }

        @Test
        @DisplayName("does not throw when identity is null")
        void noOp_whenIdentityIsNull() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(null, OTHER_USER, "agent"));
        }

        @Test
        @DisplayName("does not throw when identity is anonymous")
        void noOp_whenAnonymous() {
            var validator = new OwnershipValidator(true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(
                    anonymousIdentity(), OTHER_USER, "agent"));
        }

        @Test
        @DisplayName("does not throw when caller is admin, even with owner mismatch")
        void noOp_whenAdmin() {
            var validator = new OwnershipValidator(true);
            var admin = authenticatedIdentity(CALLER_ID, true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(admin, OTHER_USER, "agent"));
        }

        @Test
        @DisplayName("does not throw when caller matches resourceOwnerId")
        void noOp_whenCallerMatchesOwner() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(identity, CALLER_ID, "agent"));
        }

        @Test
        @DisplayName("throws ForbiddenException when caller does not match resourceOwnerId")
        void throws_whenCallerDoesNotMatchOwner() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);

            var ex = assertThrows(ForbiddenException.class,
                    () -> validator.requireOwnerOrAdmin(identity, OTHER_USER, "agent"));
            assertTrue(ex.getMessage().contains("agent"));
        }

        @Test
        @DisplayName("ForbiddenException message includes resourceType")
        void exceptionMessage_containsResourceType() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);

            var ex = assertThrows(ForbiddenException.class,
                    () -> validator.requireOwnerOrAdmin(identity, OTHER_USER, "conversation"));
            assertTrue(ex.getMessage().contains("conversation"),
                    "Expected message to contain 'conversation', was: " + ex.getMessage());
        }
    }

    // ==================== requireOwnerAdminOrApprover ====================

    @Nested
    @DisplayName("requireOwnerAdminOrApprover()")
    class RequireOwnerAdminOrApprover {

        private SecurityIdentity approverIdentity(String principalName) {
            var identity = mock(SecurityIdentity.class);
            var principal = mock(Principal.class);
            when(principal.getName()).thenReturn(principalName);
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.hasRole("eddi-admin")).thenReturn(false);
            when(identity.hasRole("eddi-approver")).thenReturn(true);
            return identity;
        }

        @Test
        @DisplayName("allows owner")
        void allows_owner() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertDoesNotThrow(() -> validator.requireOwnerAdminOrApprover(identity, CALLER_ID, "conversation"));
        }

        @Test
        @DisplayName("allows admin (non-owner)")
        void allows_admin() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, true);
            assertDoesNotThrow(() -> validator.requireOwnerAdminOrApprover(identity, OTHER_USER, "conversation"));
        }

        @Test
        @DisplayName("allows eddi-approver role (non-owner)")
        void allows_approver_role() {
            var validator = new OwnershipValidator(true);
            var identity = approverIdentity(CALLER_ID);
            assertDoesNotThrow(() -> validator.requireOwnerAdminOrApprover(identity, OTHER_USER, "conversation"));
        }

        @Test
        @DisplayName("denies non-owner without approver/admin role")
        void denies_nonOwner_noApproverRole() {
            var validator = new OwnershipValidator(true);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertThrows(ForbiddenException.class,
                    () -> validator.requireOwnerAdminOrApprover(identity, OTHER_USER, "conversation"));
        }

        @Test
        @DisplayName("denies anonymous on unowned resource (fail-closed)")
        void denies_anonymous_unowned() {
            var validator = new OwnershipValidator(true);
            var identity = anonymousIdentity();
            assertThrows(ForbiddenException.class,
                    () -> validator.requireOwnerAdminOrApprover(identity, null, "conversation"));
        }

        @Test
        @DisplayName("allows everything when auth disabled")
        void allows_everything_authDisabled() {
            var validator = new OwnershipValidator(false);
            var identity = authenticatedIdentity(CALLER_ID, false);
            assertDoesNotThrow(() -> validator.requireOwnerAdminOrApprover(identity, OTHER_USER, "conversation"));
        }
    }
}
