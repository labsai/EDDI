package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;

public interface IPropertiesHandler {
    Properties loadProperties() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void mergeProperties(Properties properties) throws IResourceStore.ResourceStoreException;
}
