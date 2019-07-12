package ai.labs.memory;

import ai.labs.models.Properties;
import ai.labs.persistence.IResourceStore;

public interface IPropertiesHandler {
    Properties loadProperties() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void mergeProperties(Properties properties) throws IResourceStore.ResourceStoreException;
}
