package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.persistence.IResourceStore;

import java.util.List;

public interface IChannelDefinitionStore {
    List<ChannelDefinition> readAllChannelDefinitions() throws IResourceStore.ResourceStoreException;

    void createChannelDefinition(ChannelDefinition channelDefinition)
            throws IResourceStore.ResourceAlreadyExistsException, IResourceStore.ResourceStoreException;

    void deleteChannelDefinition(String name) throws IResourceStore.ResourceStoreException;

    ChannelDefinition readChannelDefinition(String name) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
