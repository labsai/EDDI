/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.settings;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence for the workspace settings an administrator can change at runtime
 * — one document per tenant.
 * <p>
 * Only the policy settings live here. Whether workspaces are enforced at all,
 * and which token claim carries group membership, stay startup properties on
 * purpose: they are the security posture of the deployment, and neither should
 * be one API call away from being switched off by a leaked admin token.
 */
public interface IWorkspaceSettingsStore {

    /**
     * What an administrator stored.
     *
     * @param defaultSpace
     *            the team new resources are filed under when a request names no
     *            space, or {@code null} for the creator's personal space
     * @param legacyVisibility
     *            {@code shared} or {@code admin-only}, or {@code null} for the
     *            default
     * @param updatedAt
     *            when it was written
     * @param updatedBy
     *            the principal that wrote it
     */
    record StoredWorkspaceSettings(String defaultSpace, String legacyVisibility, Instant updatedAt, String updatedBy) {
    }

    Optional<StoredWorkspaceSettings> read(String tenantId);

    void write(String tenantId, StoredWorkspaceSettings settings);
}
