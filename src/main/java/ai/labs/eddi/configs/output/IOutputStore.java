/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output;

import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store for output configurations, the versioned sets that tell an agent how to
 * phrase and deliver a reply. The runtime resolves the output set for a turn
 * through this store, while readActions() and the paged read back the endpoints
 * that list and preview output keys.
 */
public interface IOutputStore extends IResourceStore<OutputConfigurationSet> {
    OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
