package ai.labs.eddi.engine.botmanagement.rest;

import ai.labs.eddi.engine.botmanagement.IBotTriggerStore;
import ai.labs.eddi.engine.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.botmanagement.model.BotTriggerConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;


import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@ApplicationScoped
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
    public List<BotTriggerConfiguration> readAllBotTriggers() {
        try {
            return botTriggerStore.readAllBotTriggers();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
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
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateBotTrigger(String intent, BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.updateBotTrigger(intent, botTriggerConfiguration);
            botTriggersCache.put(intent, botTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createBotTrigger(BotTriggerConfiguration botTriggerConfiguration) {
        try {
            botTriggerStore.createBotTrigger(botTriggerConfiguration);
            botTriggersCache.put(botTriggerConfiguration.getIntent(), botTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteBotTrigger(String intent) {
        try {
            botTriggerStore.deleteBotTrigger(intent);
            botTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
