/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists the one {@link ConnectionSettings} document a tenant has.
 * <p>
 * A whole-document replace, last write wins. Four fields edited by
 * administrators on a settings page do not justify optimistic versioning, and
 * the effective view returned after every write shows what landed.
 */
public interface IConnectionSettingsStore {

    /**
     * What was stored, with who stored it and when.
     *
     * @param updatedBy
     *            the principal that wrote it; "anonymous" when authorization is off
     */
    record StoredConnectionSettings(ConnectionSettings settings, Instant updatedAt, String updatedBy) {
    }

    /** The tenant's stored settings, or empty when none were ever written. */
    Optional<StoredConnectionSettings> read(String tenantId);

    /** Replaces the tenant's stored settings. */
    void write(String tenantId, StoredConnectionSettings stored);
}
