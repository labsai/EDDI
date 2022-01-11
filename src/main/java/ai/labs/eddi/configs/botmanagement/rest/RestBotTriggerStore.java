package ai.labs.eddi.configs.botmanagement.rest;

import ai.labs.eddi.configs.botmanagement.IBotTriggerStore;
import ai.labs.eddi.configs.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.models.BotTriggerConfiguration;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Slf4j
public class RestBotTriggerStore implements IRestBotTriggerStore {
    private static final String CACHE_NAME = "botTriggers";
    private final IBotTriggerStore botTriggerStore;
    private final ICache<String, BotTriggerConfiguration> botTriggersCache;

    @Inject
    public RestBotTriggerStore(IBotTriggerStore botTriggerStore,
                               ICacheFactory cacheFactory) {
        this.botTriggerStore = botTriggerStore;
        botTriggersCache = cacheFactory.getCache(CACHE_NAME);
    }

    @Override
    public BotTriggerConfiguration readBotTrigger(String intent) {
        try {
            BotTriggerConfiguration botTriggerConfiguration = botTriggersCache.get(intent);
            if (botTriggerConfiguration == null) {
                botTriggerConfiguration = botTriggerStore.readBotTrigger(intent);
                botTriggersCache.put(intent, botTriggerConfiguration);
            }

            return botTriggerConfiguration;
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage());
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.updateBotTrigger(intent, botTriggerConfiguration);
            botTriggersCache.put(intent, botTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage());
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response createBotTrigger(BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.createBotTrigger(botTriggerConfiguration);
            botTriggersCache.put(botTriggerConfiguration.getIntent(), botTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            throw new WebApplicationException(e.getLocalizedMessage(), Response.Status.CONFLICT);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response deleteBotTrigger(String intent) {
        try {
            botTriggerStore.deleteBotTrigger(intent);
            botTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }
}
