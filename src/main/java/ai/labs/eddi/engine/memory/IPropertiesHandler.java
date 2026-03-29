package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.datastore.IResourceStore;

public interface IPropertiesHandler {
    Properties loadProperties() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void mergeProperties(Properties properties) throws IResourceStore.ResourceStoreException;

    /**
     * User memory config from agent configuration. {@code null} when memory is
     * disabled.
     */
    default AgentConfiguration.UserMemoryConfig getUserMemoryConfig() {
        return null;
    }

    /** User memory store instance. {@code null} when memory is disabled. */
    default IUserMemoryStore getUserMemoryStore() {
        return null;
    }
}
