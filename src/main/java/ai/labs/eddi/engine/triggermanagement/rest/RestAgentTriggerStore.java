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
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
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
        for (String agentId : targets(configuration)) {
            resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
        }
    }

    /** The non-blank agent ids a trigger routes to. */
    private static List<String> targets(AgentTriggerConfiguration configuration) {
        if (configuration == null || configuration.getAgentDeployments() == null) {
            return List.of();
        }
        return configuration.getAgentDeployments().stream()
                .filter(deployment -> deployment != null && deployment.getAgentId() != null && !deployment.getAgentId().isBlank())
                .map(deployment -> deployment.getAgentId().trim())
                .toList();
    }

    /**
     * A trigger written through this API must route somewhere, and every deployment
     * must name its agent. Who may change a trigger is decided by the agents it
     * routes to, so a trigger that routes to none — or carries an entry that names
     * no agent — would be changeable by anybody: there would be nothing to check
     * against.
     */
    private static void requireTargets(AgentTriggerConfiguration configuration) {
        if (configuration == null || configuration.getAgentDeployments() == null || configuration.getAgentDeployments().isEmpty()) {
            throw new BadRequestException("A trigger must route to at least one agent ('agentDeployments').");
        }
        for (var deployment : configuration.getAgentDeployments()) {
            if (deployment == null || deployment.getAgentId() == null || deployment.getAgentId().isBlank()) {
                throw new BadRequestException("Every entry of 'agentDeployments' must name an 'agentId'.");
            }
        }
    }

    /**
     * Whether the caller may change or remove the stored trigger: {@code EDIT} on
     * every agent it routes to. A stored trigger that routes to no agent — written
     * before {@link #requireTargets} existed, or straight into the store — has
     * nothing to derive a right from, so only an administrator may change it while
     * workspaces are enforced (with them off, every editor holds every right
     * anyway).
     */
    private void requireMayChange(AgentTriggerConfiguration stored) {
        if (targets(stored).isEmpty()) {
            if (!resourceAccessGuard.seesEverything()) {
                throw new ForbiddenException("Access denied: this trigger routes to no agent, so only an administrator may change it");
            }
            return;
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

    /**
     * The write is conditional on the trigger still routing as the version the
     * decision was made against
     * ({@code IAgentTriggerStore#updateAgentTriggerIfUnchanged}), so a re-point
     * that lands between the check and the write fails this request instead of
     * being silently overwritten — or deleted — by someone who was never checked
     * against it.
     */
    private static WebApplicationException changedMeanwhile(String intent) {
        String message = "The trigger '" + intent + "' changed while this request was being checked; re-read it and try again.";
        return new WebApplicationException(message, Response.status(Response.Status.CONFLICT).entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    @Override
    public Response updateAgentTrigger(String intent, AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            requireIntentMatchesPath(intent, agentTriggerConfiguration);
            requireTargets(agentTriggerConfiguration);
            // Guard BOTH the agents the trigger currently routes to (may I edit this
            // trigger at all?) and the agents the new config would route to (may I
            // aim it there?). Guarding only the new config let any editor re-point
            // another team's trigger; guarding with USE let anybody the agents were
            // shared with for chatting do it.
            AgentTriggerConfiguration stored = agentTriggerStore.readAgentTrigger(intent);
            requireMayChange(stored);
            requireEditOnReferencedAgents(agentTriggerConfiguration);
            if (!agentTriggerStore.updateAgentTriggerIfUnchanged(intent, stored, agentTriggerConfiguration)) {
                throw changedMeanwhile(intent);
            }
            agentTriggersCache.put(intent, agentTriggerConfiguration);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createAgentTrigger(AgentTriggerConfiguration agentTriggerConfiguration) {
        try {
            requireTargets(agentTriggerConfiguration);
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
            AgentTriggerConfiguration stored = agentTriggerStore.readAgentTrigger(intent);
            requireMayChange(stored);
            if (!agentTriggerStore.deleteAgentTriggerIfUnchanged(intent, stored)) {
                throw changedMeanwhile(intent);
            }
            agentTriggersCache.remove(intent);
            return Response.ok().build();
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
