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

    /**
     * Requires USE access on the agents the <em>currently stored</em> trigger
     * routes to, before it may be re-pointed or removed. Triggers carry no owner
     * field, so "who may edit this trigger" is derived from the agents it already
     * commands: re-pointing or deleting a trigger redirects (or drops) the managed
     * conversations of everyone it routes for, which is exactly the act the USE
     * gate governs on {@code /agents/{id}/start}. A trigger that is genuinely
     * absent, or that references no agent, imposes no constraint here — the store's
     * own not-found handling and the new-config guard cover those.
     */
    private void requireUseOnStoredReferencedAgents(String intent) {
        AgentTriggerConfiguration stored;
        try {
            stored = agentTriggerStore.readAgentTrigger(intent);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return; // nothing stored to protect — downstream op surfaces the 404
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
        requireUseOnReferencedAgents(stored);
    }

    @Override
    public Response updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            // Guard BOTH the agents the trigger currently routes to (may I edit this
            // trigger at all?) and the agents the new config would route to (may I
            // aim it there?). Guarding only the new config let any editor re-point
            // another team's trigger — a standing bypass of the USE gate.
            requireUseOnStoredReferencedAgents(intent);
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
            // Deleting a trigger stops routing for everyone it serves — gate it on USE
            // of the agents it currently commands, so a foreign editor cannot remove
            // another team's trigger.
            requireUseOnStoredReferencedAgents(intent);
            agentTriggerStore.deleteAgentTrigger(intent);
            agentTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
