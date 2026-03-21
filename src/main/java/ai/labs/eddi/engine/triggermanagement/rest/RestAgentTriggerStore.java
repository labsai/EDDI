package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;


import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestAgentTriggerStore implements IRestAgentTriggerStore {
    private static final String CACHE_NAME = "botTriggers";
    private final IAgentTriggerStore AgentTriggerStore;
    private final ICache<String, AgentTriggerConfiguration> botTriggersCache;



    @Inject
    public RestAgentTriggerStore(IAgentTriggerStore AgentTriggerStore,
            ICacheFactory cacheFactory) {
        this.AgentTriggerStore = AgentTriggerStore;
        botTriggersCache = cacheFactory.getCache(CACHE_NAME);
    }

    @Override
    public List<AgentTriggerConfiguration> readAllBotTriggers() {
        try {
            return AgentTriggerStore.readAllBotTriggers();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public AgentTriggerConfiguration readBotTrigger(String intent) {
        try {
            AgentTriggerConfiguration AgentTriggerConfiguration = botTriggersCache.get(intent);
            if (AgentTriggerConfiguration == null) {
                AgentTriggerConfiguration = AgentTriggerStore.readBotTrigger(intent);
                botTriggersCache.put(intent, AgentTriggerConfiguration);
            }

            return AgentTriggerConfiguration;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateBotTrigger(String intent, AgentTriggerConfiguration AgentTriggerConfiguration) {
        try {
            AgentTriggerStore.updateBotTrigger(intent, AgentTriggerConfiguration);
            botTriggersCache.put(intent, AgentTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createAgentTrigger(AgentTriggerConfiguration AgentTriggerConfiguration) {
        try {
            AgentTriggerStore.createAgentTrigger(AgentTriggerConfiguration);
            botTriggersCache.put(AgentTriggerConfiguration.getIntent(), AgentTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteBotTrigger(String intent) {
        try {
            AgentTriggerStore.deleteBotTrigger(intent);
            botTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
