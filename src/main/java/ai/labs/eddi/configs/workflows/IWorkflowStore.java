/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IWorkflowStore extends IResourceStore<WorkflowConfiguration> {
    List<DocumentDescriptor> getWorkflowDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * Every workflow version — current or historical — that references
     * {@code resourceURI} at its version or any earlier one.
     * <p>
     * Unlike {@link #getWorkflowDescriptorsContainingResource}, which answers only
     * for workflows whose CURRENT version holds the reference, this keeps the
     * superseded versions too: an agent may pin one of them, and the orphan purge
     * must not delete what such a pinned version still uses.
     *
     * @param resourceURI
     *            a versioned resource URI ({@code ...?version=<n>})
     */
    List<IResourceStore.IResourceId> getWorkflowVersionsContainingResource(String resourceURI) throws IResourceStore.ResourceStoreException;
}
