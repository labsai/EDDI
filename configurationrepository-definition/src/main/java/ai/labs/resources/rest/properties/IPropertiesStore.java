package ai.labs.resources.rest.properties;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.properties.model.Properties;

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
