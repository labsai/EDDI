package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
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

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Generates A2A Agent Cards from deployed EDDI agent configurations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentCardService {

    private static final Logger LOGGER = Logger.getLogger(AgentCardService.class);

    private final IRestAgentStore restAgentStore;
    private final String baseUrl;

    @Inject
    public AgentCardService(IRestAgentStore restAgentStore,
            @ConfigProperty(name = "eddi.a2a.base-url", defaultValue = "http://localhost:7070") String baseUrl) {
        this.restAgentStore = restAgentStore;
        this.baseUrl = baseUrl;
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
            var resourceId = restAgentStore.getCurrentResourceId(agentId);
            if (resourceId == null) {
                return null;
            }
            AgentConfiguration config = restAgentStore.readAgent(agentId, resourceId.getVersion());
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
            List<DocumentDescriptor> descriptors = restAgentStore.readAgentDescriptors("", 0, 100);
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
    AgentCard buildAgentCard(String agentId, AgentConfiguration config, Integer version) {
        String name = !isNullOrEmpty(config.getDescription()) ? config.getDescription() : "EDDI Agent " + agentId;

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

        var capabilities = new AgentCapabilities(true, false, true);

        return new AgentCard(name, description, agentUrl, "EDDI", "6.0.0", capabilities, skills);
    }
}
