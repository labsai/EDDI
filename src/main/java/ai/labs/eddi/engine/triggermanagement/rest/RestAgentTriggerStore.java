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
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
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
    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public RestAgentTriggerStore(IAgentTriggerStore agentTriggerStore, ICacheFactory cacheFactory, ResourceAccessGuard resourceAccessGuard) {
        this.agentTriggerStore = agentTriggerStore;
        this.resourceAccessGuard = resourceAccessGuard;
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

    /**
     * A trigger routes inbound messages into conversations with the agents its
     * deployments name; the routing itself runs with no interactive caller and sits
     * below the USE gate. So the gate applies at authoring time: without this,
     * pointing a trigger at a private agent is a standing bypass of the check on
     * {@code /agents/{id}/start}.
     */
    private void requireUseOnReferencedAgents(AgentTriggerConfiguration configuration) {
        if (configuration == null || configuration.getAgentDeployments() == null) {
            return;
        }
        for (var deployment : configuration.getAgentDeployments()) {
            if (deployment != null && deployment.getAgentId() != null && !deployment.getAgentId().isBlank()) {
                resourceAccessGuard.requireAgentUseAccess(deployment.getAgentId());
            }
        }
    }

    @Override
    public Response updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            requireUseOnReferencedAgents(agentTriggerConfiguration);
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
            requireUseOnReferencedAgents(agentTriggerConfiguration);
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
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
