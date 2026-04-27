package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store interface for channel integration configurations. Uses the DB-agnostic
 * {@code AbstractResourceStore} via {@code IResourceStorageFactory}.
 *
 * @since 6.1.0
 */
public interface IChannelIntegrationStore extends IResourceStore<ChannelIntegrationConfiguration> {
}
