package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * @author ginccc
 */
public interface IPropertiesStore {
    Properties readProperties(String userId)
            throws IResourceStore.ResourceStoreException;

    void mergeProperties(String userId, Properties Properties)
            throws IResourceStore.ResourceStoreException;

    void deleteProperties(String userId) throws IResourceStore.ResourceStoreException;
}
