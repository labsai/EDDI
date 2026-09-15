/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.configuration;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
public interface IResourceClientLibrary {
    void init() throws ResourceClientLibraryException;

    <T> T getResource(URI uri, Class<T> clazz) throws ServiceException;

    Response duplicateResource(URI uri) throws ServiceException;

    Response deleteResource(URI uri, boolean permanent) throws ServiceException;

    /**
     * The live version of the resource a reference names, regardless of which
     * version the reference itself pins.
     *
     * <p>
     * References are version-pinned and are <em>not</em> re-pointed when the
     * resource they name is edited, so "the version in the URI" and "the version
     * that exists" routinely disagree. A cascade delete has to ask both questions
     * against the same version — who else references this resource, and which row
     * am I about to remove — or it guards one version and deletes another (or, as
     * it did, guards a version nobody references and then has its delete rejected
     * as stale, cascading nothing at all).
     * </p>
     *
     * @return the current id and version, or {@code null} when the type is not
     *         registered or the resource has no live version left
     */
    IResourceStore.IResourceId getCurrentResourceId(URI uri);

    class ResourceClientLibraryException extends RuntimeException {
        public ResourceClientLibraryException(String message, Exception e) {
            super(message, e);
        }
    }
}
