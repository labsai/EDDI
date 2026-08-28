/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.AccessScope;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDocumentDescriptorStore extends IDescriptorStore<DocumentDescriptor> {

    /**
     * Lists descriptors, returning only what {@code scope} admits.
     * <p>
     * The scope is a required argument rather than something resolved inside this
     * method, so a caller that genuinely operates below the access model — the
     * export service walking a config graph, the orphan sweep, a startup migration
     * — has to write {@link AccessScope#unrestricted()} at the call site rather
     * than getting unfiltered results by omission.
     * <p>
     * {@link #readDescriptors(String, String, Integer, Integer, boolean)} without a
     * scope remains unrestricted, for the same internal callers and for backward
     * compatibility; new list endpoints should use this overload.
     *
     * @param scope
     *            what the caller may see; {@code null} is treated as unrestricted
     */
    List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted, AccessScope scope)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * The current descriptor for a resource id, regardless of which version the
     * caller happens to be addressing.
     * <p>
     * Ownership belongs to the resource, not to one of its versions, so every
     * access decision resolves through here — otherwise reading version 3 of a
     * resource whose version 4 was re-shared would be decided against stale
     * sharing.
     *
     * @return the descriptor at the current version
     */
    DocumentDescriptor readCurrentDescriptor(String resourceId)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
