/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.a2a.A2AModels.AgentAuthentication;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCapabilities;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCard;
import ai.labs.eddi.engine.a2a.A2AModels.AgentSkill;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Generates A2A Agent Cards from deployed EDDI agent configurations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentCardService {

    private static final Logger LOGGER = Logger.getLogger(AgentCardService.class);

    private final IAgentStore agentStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final String baseUrl;
    private final boolean authEnabled;
    private final String oidcAuthServerUrl;

    @Inject
    public AgentCardService(IAgentStore agentStore, IDocumentDescriptorStore documentDescriptorStore,
            @ConfigProperty(name = "eddi.a2a.base-url", defaultValue = "http://localhost:7070") String baseUrl,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> oidcAuthServerUrl) {
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.baseUrl = baseUrl;
        this.authEnabled = authEnabled;
        this.oidcAuthServerUrl = oidcAuthServerUrl.orElse(null);
    }

    /**
     * Generate an AgentCard for a specific agent.
     *
     * @param agentId
     *            the agent's ID
     *
     * @return the AgentCard, or null if the agent doesn't exist or isn't
     *         A2A-enabled
     */
    public AgentCard getAgentCard(String agentId) {
        try {
            var resourceId = agentStore.getCurrentResourceId(agentId);
            if (resourceId == null) {
                return null;
            }
            AgentConfiguration config = agentStore.read(agentId, resourceId.getVersion());
            if (config == null || !config.isA2aEnabled()) {
                return null;
            }

            return buildAgentCard(agentId, config, resourceId.getVersion());
        } catch (Exception e) {
            LOGGER.warnf("Failed to build Agent Card for agentId=%s: %s", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * List Agent Cards for all A2A-enabled agents.
     *
     * @return list of AgentCards (may be empty)
     */
    public List<AgentCard> listA2AAgents() {
        List<AgentCard> cards = new ArrayList<>();
        try {
            // Unrestricted deliberately: an Agent Card is published to A2A *peers*, which
            // are
            // remote systems rather than EDDI users, so the caller has no workspace to
            // scope
            // to. The gate for this surface is `isA2aEnabled()` on the agent plus whatever
            // authentication fronts the A2A endpoints — not the workspace model.
            List<DocumentDescriptor> descriptors = documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0, 100, false);
            if (descriptors == null) {
                return cards;
            }

            for (DocumentDescriptor descriptor : descriptors) {
                URI resourceUri = descriptor.getResource();
                if (resourceUri == null) {
                    continue;
                }
                String path = resourceUri.getPath();
                if (isNullOrEmpty(path)) {
                    continue;
                }
                // URI format: eddi://ai.labs.agent/agentstore/agents/{agentId}?version=N
                String[] segments = path.split("/");
                if (segments.length < 2) {
                    continue;
                }
                String agentId = segments[segments.length - 1];

                AgentCard card = getAgentCard(agentId);
                if (card != null) {
                    cards.add(card);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to list A2A agents: %s", e.getMessage());
        }
        return cards;
    }

    /**
     * Build an AgentCard from an agent configuration.
     */
    /**
     * The agent's human name, as its descriptor records it.
     * <p>
     * A2A Agent Cards are how other systems discover what an agent <em>is</em>, and
     * every card announced "EDDI Agent 6a1f…" — the raw id, for every agent. The
     * name an operator gave the agent lives on its {@link DocumentDescriptor}, not
     * on {@link AgentConfiguration}, which is why it was never reached for. Falls
     * back to the old form when the descriptor is missing or unnamed, so a card is
     * still served rather than dropped.
     */
    private String agentDisplayName(String agentId, Integer version) {
        try {
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(agentId, version);
            if (descriptor != null && !isNullOrEmpty(descriptor.getName())) {
                return descriptor.getName();
            }
        } catch (Exception e) {
            LOGGER.debugf("No descriptor name for A2A agent %s: %s", sanitize(agentId), e.getMessage());
        }
        return "EDDI Agent " + agentId;
    }

    AgentCard buildAgentCard(String agentId, AgentConfiguration config, Integer version) {
        String name = agentDisplayName(agentId, version);

        String description = !isNullOrEmpty(config.getDescription()) ? config.getDescription() : "EDDI conversational AI agent";

        String agentUrl = baseUrl + "/a2a/agents/" + agentId;

        // Build skills
        List<AgentSkill> skills = new ArrayList<>();
        if (config.getA2aSkills() != null && !config.getA2aSkills().isEmpty()) {
            for (String skillName : config.getA2aSkills()) {
                skills.add(new AgentSkill(skillName.toLowerCase().replace(' ', '-'), skillName, "Skill: " + skillName, null, null));
            }
        } else {
            // Default skill
            skills.add(new AgentSkill("chat", "Conversational AI", "General conversational AI agent powered by EDDI", List.of("chat", "ai"), null));
        }

        var capabilities = new AgentCapabilities(false, false, true);

        // Build authentication info if auth is enabled
        AgentAuthentication authentication = null;
        if (authEnabled) {
            String credentials = oidcAuthServerUrl != null ? oidcAuthServerUrl + "/protocol/openid-connect/token" : null;
            authentication = new AgentAuthentication(List.of("Bearer"), credentials);
        }

        return new AgentCard(name, description, agentUrl, "EDDI", "6.0.0", capabilities, skills, authentication);
    }
}
