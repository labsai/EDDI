package ai.labs.eddi.configs.http;


import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IHttpCallsStore extends IResourceStore<HttpCallsConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
