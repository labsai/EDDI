/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Versioned store for {@link ConnectionConfiguration} documents.
 * <p>
 * Grants — the tokens themselves — deliberately live in a separate store with a
 * different lifecycle and different access control. A connection document is
 * ordinary configuration an operator reads, diffs and version-controls; a grant
 * is runtime state that must never appear in an export.
 */
public interface IConnectionStore extends IResourceStore<ConnectionConfiguration> {

    /**
     * Finds a connection by its {@code name} within a tenant, which is how
     * {@code ${connection:name}} refers to one.
     * <p>
     * Names are the reference vocabulary, so this is the lookup the resolver
     * actually uses; the id-and-version API that {@link IResourceStore} provides is
     * for the REST surface.
     *
     * @return the newest version of the named connection, or {@code null}
     */
    ConnectionConfiguration readByName(String tenantId, String name) throws ResourceStoreException;
}
