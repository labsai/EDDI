package ai.labs.resources.rest.properties;

import ai.labs.models.Properties;
import ai.labs.persistence.IResourceStore;

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
