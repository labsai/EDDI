package ai.labs.resources.rest.config.http;


import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;

import java.util.List;

/**
 * @author ginccc
 */
public interface IHttpCallsStore extends IResourceStore<HttpCallsConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit) throws ResourceNotFoundException, ResourceStoreException;
}
