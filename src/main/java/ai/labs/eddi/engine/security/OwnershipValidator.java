/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Centralized ownership validation utility. Provides methods to assert that the
 * authenticated caller owns the resource they are attempting to access, or
 * holds the {@code eddi-admin} role.
 *
 * <p>
 * All checks are no-ops when {@code authorization.enabled=false}.
 * </p>
 *
 * @author ginccc
 * @since 6.1.0
 */
@ApplicationScoped
public class OwnershipValidator {

    private static final Logger LOGGER = Logger.getLogger(OwnershipValidator.class);

    private final boolean authEnabled;

    @Inject
    public OwnershipValidator(@ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    /**
     * Returns whether authorization enforcement is enabled.
     *
     * @return {@code true} if authorization checks are active
     */
    public boolean isAuthEnabled() {
        return authEnabled;
    }

    /**
     * Asserts that the caller matches the requested {@code userId} or holds the
     * {@code eddi-admin} role.
     *
     * @param identity
     *            the caller's security identity (may be null/anonymous)
     * @param requestedUserId
     *            the userId being accessed
     * @throws ForbiddenException
     *             if the caller is not the owner and not an admin
     */
    public void validateUserAccess(SecurityIdentity identity, String requestedUserId) {
        if (!authEnabled) {
            return;
        }
        if (identity == null || identity.isAnonymous()) {
            return; // let @RolesAllowed handle anonymous access
        }
        if (identity.hasRole("eddi-admin")) {
            return;
        }

        String callerId = identity.getPrincipal().getName();
        if (!callerId.equals(requestedUserId)) {
            LOGGER.warnf("Ownership check failed: caller '%s' attempted to access userId '%s'", callerId, requestedUserId);
            throw new ForbiddenException("Access denied: you do not own this user's data");
        }
    }

    /**
     * Resolves the effective userId for starting a conversation. Admins may specify
     * any userId; regular users must match their own identity. When the requested
     * userId is {@code null}, the caller's identity is used as fallback.
     *
     * <p>
     * No-op when authorization is disabled — returns {@code requestedUserId} as-is.
     * </p>
     *
     * @param identity
     *            the caller's security identity (may be null/anonymous)
     * @param requestedUserId
     *            the userId requested by the caller (may be null)
     * @return the resolved userId to use
     * @throws ForbiddenException
     *             if a non-admin caller tries to impersonate another user
     */
    public String validateAndResolveUserId(SecurityIdentity identity, String requestedUserId) {
        if (!authEnabled) {
            return requestedUserId;
        }
        if (identity == null || identity.isAnonymous()) {
            return requestedUserId; // let @RolesAllowed handle anonymous access
        }

        String callerId = identity.getPrincipal().getName();

        if (requestedUserId == null || requestedUserId.isBlank()) {
            return callerId;
        }

        if (identity.hasRole("eddi-admin")) {
            return requestedUserId;
        }

        if (!callerId.equals(requestedUserId)) {
            LOGGER.warnf("UserId resolution rejected: caller '%s' attempted to set userId '%s'", callerId, requestedUserId);
            throw new ForbiddenException("Access denied: you cannot start a conversation as another user");
        }

        return requestedUserId;
    }

    /**
     * Asserts that the caller owns the resource identified by
     * {@code resourceOwnerId}, or holds the {@code eddi-admin} role.
     *
     * <p>
     * No-op when authorization is disabled, or when {@code resourceOwnerId} is
     * null/blank (legacy data without ownership tracking).
     * </p>
     *
     * @param identity
     *            the caller's security identity (may be null/anonymous)
     * @param resourceOwnerId
     *            the userId of the resource owner (may be null for legacy data)
     * @param resourceType
     *            human-readable resource type for error messages
     * @throws ForbiddenException
     *             if the caller is not the owner and not an admin
     */
    public void requireOwnerOrAdmin(SecurityIdentity identity, String resourceOwnerId, String resourceType) {
        if (!authEnabled) {
            return;
        }
        if (resourceOwnerId == null || resourceOwnerId.isBlank()) {
            return; // legacy data without ownership — allow access
        }
        if (identity == null || identity.isAnonymous()) {
            return; // let @RolesAllowed handle anonymous access
        }
        if (identity.hasRole("eddi-admin")) {
            return;
        }

        String callerId = identity.getPrincipal().getName();
        if (!callerId.equals(resourceOwnerId)) {
            LOGGER.warnf("Ownership check failed: caller '%s' denied access to %s owned by '%s'",
                    callerId, resourceType, resourceOwnerId);
            throw new ForbiddenException("Access denied: you do not own this " + resourceType);
        }
    }
}
