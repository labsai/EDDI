package ai.labs.eddi.configs.channels.mongo;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * DB-agnostic store for channel integration configurations. Extends
 * {@link AbstractResourceStore} which delegates to either MongoDB or PostgreSQL
 * via {@link IResourceStorageFactory}.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class ChannelIntegrationStore
        extends
            AbstractResourceStore<ChannelIntegrationConfiguration>
        implements
            IChannelIntegrationStore {

    @Inject
    public ChannelIntegrationStore(IResourceStorageFactory storageFactory,
            IDocumentBuilder documentBuilder) {
        super(storageFactory, "channels", documentBuilder,
                ChannelIntegrationConfiguration.class);
    }
}
