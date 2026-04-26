/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
    private static final String CACHE_NAME = "agentTriggers";
    private final IAgentTriggerStore agentTriggerStore;
    private final ICache<String, AgentTriggerConfiguration> agentTriggersCache;

    @Inject
    public RestAgentTriggerStore(IAgentTriggerStore agentTriggerStore, ICacheFactory cacheFactory) {
        this.agentTriggerStore = agentTriggerStore;
        agentTriggersCache = cacheFactory.getCache(CACHE_NAME);
    }

    @Override
    public List<AgentTriggerConfiguration> readAllAgentTriggers() {
        try {
            return agentTriggerStore.readAllAgentTriggers();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public AgentTriggerConfiguration readAgentTrigger(String intent) {
        try {
            AgentTriggerConfiguration agentTriggerConfiguration = agentTriggersCache.get(intent);
            if (agentTriggerConfiguration == null) {
                agentTriggerConfiguration = agentTriggerStore.readAgentTrigger(intent);
                agentTriggersCache.put(intent, agentTriggerConfiguration);
            }

            return agentTriggerConfiguration;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            agentTriggerStore.updateAgentTrigger(intent, agentTriggerConfiguration);
            agentTriggersCache.put(intent, agentTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            agentTriggerStore.createAgentTrigger(agentTriggerConfiguration);
            agentTriggersCache.put(agentTriggerConfiguration.getIntent(), agentTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteAgentTrigger(String intent) {
        try {
            agentTriggerStore.deleteAgentTrigger(intent);
            agentTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
