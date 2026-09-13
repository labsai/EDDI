/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

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

    /**
     * The resource id of the connection currently holding {@code name}, or
     * {@code null}.
     * <p>
     * Exists so a write can refuse a duplicate name. Names are the reference
     * vocabulary — {@code ${connection:jira}} names one connection and must keep
     * naming the same one — and without this check a second connection called
     * "jira" resolves or does not depending on descriptor scan order, which changes
     * after a delete or a re-index.
     */
    String idOfName(String tenantId, String name) throws ResourceStoreException;

    /**
     * Every connection currently holding {@code name} in a tenant, oldest first —
     * by descriptor creation time, then by id, so two replicas scanning the same
     * index agree on the order.
     * <p>
     * Exists for the post-create check in {@code RestConnectionStore}: the
     * pre-create {@link #idOfName} is a check-then-act, and two replicas creating
     * "jira" in the same instant both pass it. This is the store being asked
     * afterwards who holds the name now. Empty when nobody does.
     */
    List<String> idsOfName(String tenantId, String name) throws ResourceStoreException;
}
