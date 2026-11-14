/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * LLM tool for discovering agents by capability using the
 * {@link CapabilityRegistryService}. Constructed per-invocation by
 * {@code DynamicAgentToolsProvider}.
 *
 * <p>
 * The LLM can call this tool to find agents that have registered a specific
 * skill, using strategies like highest_confidence, round_robin, or all.
 *
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by DynamicAgentToolsProvider — must NOT be a CDI
        // bean
public class FindAgentsByCapabilityTool {

    private static final Logger LOGGER = Logger.getLogger(FindAgentsByCapabilityTool.class);

    private final CapabilityRegistryService registryService;

    /**
     * Which agents the conversation's user may use, or {@code null} for no filter.
     * Applied before the strategy, as the REST search does, so agents the caller
     * cannot use are not even named to the model: delegation re-checks USE, but
     * discovery alone used to disclose every agent's id and capability metadata.
     */
    private final Predicate<String> agentFilter;

    public FindAgentsByCapabilityTool(CapabilityRegistryService registryService) {
        this(registryService, null);
    }

    public FindAgentsByCapabilityTool(CapabilityRegistryService registryService, Predicate<String> agentFilter) {
        this.registryService = registryService;
        this.agentFilter = agentFilter;
    }

    @Tool("Find deployed agents that have a specific capability or skill. "
            + "Use this to discover which agents can handle a particular task before delegating work. "
            + "Strategies: 'highest_confidence' (best match), 'round_robin' (load-balanced), 'all' (every match).")
    public String findAgentsByCapability(
                                         @P("The skill or capability to search for (e.g. 'translation', 'summarization', 'code-review')") String skill,
                                         @P("Selection strategy: 'highest_confidence', 'round_robin', or 'all'. Default: 'highest_confidence'") String strategy) {

        try {
            // --- Validate parameters ---
            if (skill == null || skill.isBlank()) {
                return "⚠️ Skill name is required.";
            }

            String effectiveStrategy = (strategy != null && !strategy.isBlank()) ? strategy : "highest_confidence";

            LOGGER.debugf("[CAPABILITY] Searching for skill='%s' with strategy='%s'", skill, effectiveStrategy);

            List<CapabilityMatch> matches = agentFilter == null
                    ? registryService.findBySkill(skill, effectiveStrategy)
                    : registryService.findBySkill(skill, effectiveStrategy, agentFilter);

            if (matches == null || matches.isEmpty()) {
                return "No agents found with skill '%s'.".formatted(skill);
            }

            // --- Format results ---
            String header = "🔍 Found %d agent(s) with skill '%s' (strategy: %s):\n"
                    .formatted(matches.size(), skill, effectiveStrategy);

            String body = matches.stream()
                    .map(this::formatMatch)
                    .collect(Collectors.joining("\n"));

            return header + body;

        } catch (Exception e) {
            LOGGER.errorf("[CAPABILITY] Error searching for skill '%s': %s", skill, e.getMessage());
            return "❌ Error searching for agents: " + e.getMessage();
        }
    }

    private String formatMatch(CapabilityMatch match) {
        var sb = new StringBuilder();
        sb.append("• Agent: ").append(match.agentId());
        sb.append(" | Skill: ").append(match.skill());

        if (match.confidence() != null && !match.confidence().isBlank()) {
            sb.append(" | Confidence: ").append(match.confidence());
        }

        Map<String, String> attributes = match.attributes();
        if (attributes != null && !attributes.isEmpty()) {
            String attrs = attributes.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            sb.append(" | Attributes: {").append(attrs).append("}");
        }

        return sb.toString();
    }
}
