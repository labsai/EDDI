package ai.labs.memory;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.properties.model.Properties;

public interface IPropertiesHandler {
    Properties loadProperties() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void mergeProperties(Properties properties) throws IResourceStore.ResourceStoreException;
}
