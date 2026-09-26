/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.triggermanagement.IAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import io.quarkus.security.ForbiddenException;
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

    /**
     * Only the triggers the caller could have authored: those whose every target
     * agent they may {@link AccessLevel#USE}. A trigger names the intent an
     * integration routes by and the agents it routes to, so listing another team's
     * is the first half of re-pointing it. Also backs the MCP
     * {@code discover_agents} intent mapping. Unfiltered while workspaces are not
     * enforced.
     */
    @Override
    public List<AgentTriggerConfiguration> readAllAgentTriggers() {
        try {
            if (resourceAccessGuard.seesEverything()) {
                return agentTriggerStore.readAllAgentTriggers();
            }
            return agentTriggerStore.readAllAgentTriggers().stream()
                    .filter(trigger -> holdsOnEveryTarget(trigger, AccessLevel.USE))
                    .toList();
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
            // Same visibility rule as the listing, and answered like an absent intent so
            // the endpoint cannot be used to probe which intents another team routes.
            if (!holdsOnEveryTarget(agentTriggerConfiguration, AccessLevel.USE)) {
                throw new TriggerNotVisibleException("No agent trigger for this intent.");
            }

            return agentTriggerConfiguration;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Whether the caller holds at least {@code level} on every agent the trigger
     * routes to.
     */
    private boolean holdsOnEveryTarget(AgentTriggerConfiguration configuration, AccessLevel level) {
        if (configuration == null || configuration.getAgentDeployments() == null) {
            return true;
        }
        for (var deployment : configuration.getAgentDeployments()) {
            if (deployment != null && deployment.getAgentId() != null && !deployment.getAgentId().isBlank()
                    && !resourceAccessGuard.hasAccess(deployment.getAgentId(), level)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Authority over an EXISTING trigger: EDIT on every agent it currently routes
     * to.
     * <p>
     * A trigger has no owner of its own; what it controls is how its target agents
     * are reached. Checking only the agents a PUT <em>introduces</em> let any
     * editor re-point another team's intent at an agent of their own and harvest
     * the managed-conversation traffic meant for the original, and a DELETE was not
     * checked at all. Requiring EDIT on the current targets puts the decision with
     * whoever may change those agents — their owners and editors — and leaves a
     * trigger nobody else can touch. A trigger that does not exist yet has no
     * current targets and passes; the create/update USE check on the new targets
     * still applies. A no-op while workspaces are not enforced.
     */
    private void requireEditOnCurrentTargets(String intent) throws IResourceStore.ResourceStoreException {
        if (resourceAccessGuard.seesEverything()) {
            return;
        }
        AgentTriggerConfiguration stored;
        try {
            stored = agentTriggerStore.readAgentTrigger(intent);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return;
        }
        if (!holdsOnEveryTarget(stored, AccessLevel.EDIT)) {
            throw new ForbiddenException("Access denied: this trigger routes to an agent you may not edit. "
                    + "Ask the agent's owner to change or remove it.");
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
            requireEditOnCurrentTargets(intent);
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
            requireEditOnCurrentTargets(intent);
            agentTriggerStore.deleteAgentTrigger(intent);
            agentTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
