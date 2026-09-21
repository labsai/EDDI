/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Persistence for API call configurations, the named sets of external HTTP
 * calls an agent can invoke during a conversation. Besides the usual CRUD,
 * readActions() exposes the call keys of a version so the REST editor can offer
 * them wherever an action name is expected.
 */
public interface IApiCallsStore extends IResourceStore<ApiCallsConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
