/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IApiCallsStore extends IResourceStore<ApiCallsConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
