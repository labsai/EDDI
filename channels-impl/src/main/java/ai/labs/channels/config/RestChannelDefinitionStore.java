package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.persistence.IResourceStore;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.util.List;

@Slf4j
public class RestChannelDefinitionStore implements IRestChannelDefinitionStore {
    private final IChannelDefinitionStore channelDefinitionStore;
    private final IChannelManager channelManager;

    @Inject
    public RestChannelDefinitionStore(IChannelDefinitionStore channelDefinitionStore,
                                      IChannelManager channelManager) {

        this.channelDefinitionStore = channelDefinitionStore;
        this.channelManager = channelManager;
    }

    @Override
    public List<ChannelDefinition> readAllChannelDefinitions() {
        try {
            return channelDefinitionStore.readAllChannelDefinitions();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public ChannelDefinition readChannelDefinition(String name) {
        try {
            return channelDefinitionStore.readChannelDefinition(name);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response createChannelDefinition(ChannelDefinition channelDefinition) {
        try {
            channelDefinitionStore.createChannelDefinition(channelDefinition);
            if (channelDefinition.isActive()) {
                channelManager.initChannel(channelDefinition);
            }
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw new BadRequestException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response deleteChannelDefinition(String name) {
        try {
            channelDefinitionStore.deleteChannelDefinition(name);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }
}
