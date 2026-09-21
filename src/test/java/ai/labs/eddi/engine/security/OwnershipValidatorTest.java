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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.Principal;
import java.util.stream.Stream;

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

    // ==================== nameless principal ====================

    /**
     * An authenticated, non-anonymous identity whose principal name is null or
     * blank — what Quarkus OIDC produces for a token with no upn,
     * preferred_username or sub. Every check must deny with 403, never NPE into a
     * 500, and never resolve to a null owner.
     */
    @Nested
    @DisplayName("nameless principal")
    class NamelessPrincipal {

        private final OwnershipValidator validator = new OwnershipValidator(true);

        static Stream<String> namelessNames() {
            return Stream.of(null, "", "   ");
        }

        private SecurityIdentity principalLessIdentity() {
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getPrincipal()).thenReturn(null);
            return identity;
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("isOwner() is false, even for an unowned resource")
        void isOwner_false(String name) {
            var identity = authenticatedIdentity(name, false);
            assertFalse(validator.isOwner(identity, OTHER_USER));
            assertFalse(validator.isOwner(identity, name));
            assertFalse(validator.isOwner(identity, null));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("validateUserAccess() denies with 403")
        void validateUserAccess_denies(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.validateUserAccess(identity, OTHER_USER));
            assertThrows(ForbiddenException.class, () -> validator.validateUserAccess(identity, name));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("validateAndResolveUserId() denies instead of resolving to a null owner")
        void validateAndResolveUserId_deniesWithoutRequestedUserId(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.validateAndResolveUserId(identity, null));
            assertThrows(ForbiddenException.class, () -> validator.validateAndResolveUserId(identity, "  "));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("validateAndResolveUserId() denies a non-admin naming a userId")
        void validateAndResolveUserId_deniesNamedUserId(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.validateAndResolveUserId(identity, OTHER_USER));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("validateAndResolveUserId() denies even an admin when there is no userId to resolve")
        void validateAndResolveUserId_adminWithoutRequestedUserId(String name) {
            var identity = authenticatedIdentity(name, true);
            assertThrows(ForbiddenException.class, () -> validator.validateAndResolveUserId(identity, null));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("validateAndResolveUserId() lets an admin name a userId — the role, not the name, authorizes it")
        void validateAndResolveUserId_adminNamedUserId(String name) {
            var identity = authenticatedIdentity(name, true);
            assertEquals(OTHER_USER, validator.validateAndResolveUserId(identity, OTHER_USER));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerOrAdmin() denies with 403")
        void requireOwnerOrAdmin_denies(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdmin(identity, OTHER_USER, "conversation"));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerOrAdmin() denies with 403 on an unowned (legacy) resource too")
        void requireOwnerOrAdmin_deniesUnowned(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdmin(identity, null, "conversation"));
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdmin(identity, "  ", "conversation"));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerOrAdmin() still lets an admin through, owned or unowned")
        void requireOwnerOrAdmin_adminUnowned(String name) {
            var identity = authenticatedIdentity(name, true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(identity, null, "conversation"));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("isNamelessCaller() is true for an authenticated identity without a name")
        void isNamelessCaller_true(String name) {
            assertTrue(OwnershipValidator.isNamelessCaller(authenticatedIdentity(name, false)));
        }

        @Test
        @DisplayName("isNamelessCaller() is false for null, anonymous and named identities")
        void isNamelessCaller_false() {
            assertFalse(OwnershipValidator.isNamelessCaller(null));
            assertFalse(OwnershipValidator.isNamelessCaller(anonymousIdentity()));
            assertFalse(OwnershipValidator.isNamelessCaller(authenticatedIdentity(CALLER_ID, false)));
            assertTrue(OwnershipValidator.isNamelessCaller(principalLessIdentity()));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerOrAdmin() still lets an admin through")
        void requireOwnerOrAdmin_admin(String name) {
            var identity = authenticatedIdentity(name, true);
            assertDoesNotThrow(() -> validator.requireOwnerOrAdmin(identity, OTHER_USER, "conversation"));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerOrAdminStrict() denies with 403, owned or unowned")
        void requireOwnerOrAdminStrict_denies(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdminStrict(identity, OTHER_USER, "approval"));
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdminStrict(identity, null, "approval"));
        }

        @ParameterizedTest(name = "name=[{0}]")
        @MethodSource("namelessNames")
        @DisplayName("requireOwnerAdminOrApprover() denies a caller without the approver role")
        void requireOwnerAdminOrApprover_denies(String name) {
            var identity = authenticatedIdentity(name, false);
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerAdminOrApprover(identity, OTHER_USER, "approval"));
        }

        @Test
        @DisplayName("an authenticated identity with no principal at all is denied the same way")
        void noPrincipal_denied() {
            var identity = principalLessIdentity();
            assertFalse(validator.isOwner(identity, OTHER_USER));
            assertThrows(ForbiddenException.class, () -> validator.validateUserAccess(identity, OTHER_USER));
            assertThrows(ForbiddenException.class, () -> validator.validateAndResolveUserId(identity, null));
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdmin(identity, OTHER_USER, "conversation"));
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdmin(identity, null, "conversation"));
            assertThrows(ForbiddenException.class, () -> validator.requireOwnerOrAdminStrict(identity, OTHER_USER, "conversation"));
        }

        @Test
        @DisplayName("principalName() normalises null, blank and missing principals to null")
        void principalName_normalises() {
            assertNull(OwnershipValidator.principalName(null));
            assertNull(OwnershipValidator.principalName(principalLessIdentity()));
            assertNull(OwnershipValidator.principalName(authenticatedIdentity(null, false)));
            assertNull(OwnershipValidator.principalName(authenticatedIdentity(" ", false)));
            assertEquals(CALLER_ID, OwnershipValidator.principalName(authenticatedIdentity(CALLER_ID, false)));
        }
    }
}
