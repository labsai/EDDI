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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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
     * Requires {@link AccessLevel#EDIT} on every agent a trigger routes to.
     * <p>
     * Triggers carry no owner field, so "who may change this trigger" is derived
     * from the agents it commands. USE was the bar until 6.5: but USE is what an
     * agent's <em>users</em> hold, and a trigger is not something its users may
     * rewire — re-pointing an intent hands every managed conversation it routes to
     * a different agent, which is a change to how the agent is reached, i.e. an
     * edit. With USE, any editor an agent was shared with for chatting could aim
     * another team's {@code support} intent at their own agent and have it answer
     * that team's users. Applied to the stored targets (may I change this trigger?)
     * and to the new ones (may I aim it there?).
     * <p>
     * While workspaces are not enforced every caller holds every level, so this
     * restricts nothing there — exactly like editing the agent itself, which any
     * editor may then do too.
     */
    private void requireEditOnReferencedAgents(AgentTriggerConfiguration configuration) {
        if (configuration == null || configuration.getAgentDeployments() == null) {
            return;
        }
        for (var deployment : configuration.getAgentDeployments()) {
            if (deployment != null && deployment.getAgentId() != null && !deployment.getAgentId().isBlank()) {
                resourceAccessGuard.requireAccess(deployment.getAgentId(), AccessLevel.EDIT, "agent");
            }
        }
    }

    /**
     * {@link #requireEditOnReferencedAgents} on the <em>currently stored</em>
     * trigger, before it may be re-pointed or removed. A trigger that is genuinely
     * absent imposes no constraint here — the store's own not-found handling
     * answers that.
     */
    private void requireEditOnStoredReferencedAgents(String intent) {
        AgentTriggerConfiguration stored;
        try {
            stored = agentTriggerStore.readAgentTrigger(intent);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return; // nothing stored to protect — downstream op surfaces the 404
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
        requireEditOnReferencedAgents(stored);
    }

    /**
     * The body's intent must be the one addressed, or absent (it is then filled
     * in). The stores disagreed about a mismatch — MongoDB's replace renamed the
     * trigger to the body's intent, PostgreSQL kept the row under the path's intent
     * with the body's intent inside it — and neither was a decision anybody made. A
     * rename is a delete plus a create, each with its own check.
     */
    private static void requireIntentMatchesPath(String intent, AgentTriggerConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        String bodyIntent = configuration.getIntent();
        if (bodyIntent == null || bodyIntent.isBlank()) {
            configuration.setIntent(intent);
        } else if (!bodyIntent.equals(intent)) {
            throw new BadRequestException("The trigger's 'intent' (" + bodyIntent + ") must match the intent in the path (" + intent
                    + "). To rename a trigger, create the new intent and delete the old one.");
        }
    }

    @Override
    public Response updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            requireIntentMatchesPath(intent, agentTriggerConfiguration);
            // Guard BOTH the agents the trigger currently routes to (may I edit this
            // trigger at all?) and the agents the new config would route to (may I
            // aim it there?). Guarding only the new config let any editor re-point
            // another team's trigger; guarding with USE let anybody the agents were
            // shared with for chatting do it.
            requireEditOnStoredReferencedAgents(intent);
            requireEditOnReferencedAgents(agentTriggerConfiguration);
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
            // EDIT, as for update and delete: whoever may create a trigger must be able
            // to change and remove it again, and routing an intent at an agent is a
            // change to how that agent is reached. Creating an intent that already
            // exists is refused by both stores (unique intent), never an overwrite — so
            // create is not a way around the update check.
            requireEditOnReferencedAgents(agentTriggerConfiguration);
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
            // Deleting a trigger stops routing for everyone it serves — gate it on EDIT
            // of the agents it currently commands, so neither a foreign editor nor a
            // mere user of those agents can remove another team's trigger.
            requireEditOnStoredReferencedAgents(intent);
            agentTriggerStore.deleteAgentTrigger(intent);
            agentTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
