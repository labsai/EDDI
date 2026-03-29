package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Legacy properties store interface for user-scoped key-value properties.
 *
 * @deprecated since 6.0.0. Use {@link IUserMemoryStore} instead, which provides
 *             both legacy property compatibility methods and structured memory
 *             entry operations.
 * @author ginccc
 */
@Deprecated(since = "6.0.0", forRemoval = false)
public interface IPropertiesStore {
    Properties readProperties(String userId) throws IResourceStore.ResourceStoreException;

    void mergeProperties(String userId, Properties properties) throws IResourceStore.ResourceStoreException;

    void deleteProperties(String userId) throws IResourceStore.ResourceStoreException;
}
