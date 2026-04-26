/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.Capability;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory capability registry for A2A agent discovery.
 * <p>
 * Agents register their structured {@link Capability} declarations at
 * deployment time. Other agents (or external systems via REST / MCP) can query
 * the registry to find agents that match a required skill. Selection strategies
 * (highest_confidence, round_robin, all) are deterministic algorithms, not LLM
 * guesses.
 * <p>
 * The registry is rebuilt from {@code AgentConfiguration} on agent
 * create/update/delete via {@code @ConfigurationUpdate} observer events.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class CapabilityRegistryService {
    private static final Logger LOGGER = Logger.getLogger(CapabilityRegistryService.class);

    /**
     * Index: skill name → list of (agentId, capability) pairs. Rebuilt on agent
     * configuration changes.
     */
    private final Map<String, List<AgentCapabilityEntry>> skillIndex = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private Counter queryCounter;
    private Timer queryTimer;

    @Inject
    public CapabilityRegistryService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        queryCounter = meterRegistry.counter("eddi.capability.query.count");
        queryTimer = meterRegistry.timer("eddi.capability.query.time");
    }

    /**
     * Register all capabilities for an agent. Replaces any previous registration.
     *
     * @param agentId
     *            the agent's unique ID
     * @param config
     *            the agent's configuration containing capabilities
     */
    public void register(String agentId, AgentConfiguration config) {
        // Remove any previous entries for this agent
        unregister(agentId);

        if (config.getCapabilities() == null || config.getCapabilities().isEmpty()) {
            return;
        }

        for (Capability cap : config.getCapabilities()) {
            if (cap.getSkill() == null || cap.getSkill().isBlank()) {
                continue;
            }
            String skill = cap.getSkill().toLowerCase(Locale.ROOT).trim();
            skillIndex.computeIfAbsent(skill, k -> new CopyOnWriteArrayList<>())
                    .add(new AgentCapabilityEntry(agentId, cap));
        }

        LOGGER.debugf("Registered %d capabilities for agent '%s'",
                config.getCapabilities().size(), agentId);
    }

    /**
     * Remove all capability entries for an agent.
     */
    public void unregister(String agentId) {
        skillIndex.values().forEach(entries -> entries.removeIf(e -> e.agentId().equals(agentId)));
        // Clean up empty skill entries
        skillIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Find all agents that declare a specific skill.
     *
     * @param skill
     *            the required skill name (case-insensitive)
     * @param strategy
     *            selection strategy: "highest_confidence", "round_robin", or "all"
     * @return matching agents ordered by the selection strategy
     */
    public List<CapabilityMatch> findBySkill(String skill, String strategy) {
        return queryTimer.record(() -> {
            queryCounter.increment();

            if (skill == null || skill.isBlank()) {
                return Collections.emptyList();
            }

            String normalizedSkill = skill.toLowerCase(Locale.ROOT).trim();
            List<AgentCapabilityEntry> entries = skillIndex.getOrDefault(normalizedSkill, Collections.emptyList());

            if (entries.isEmpty()) {
                return Collections.emptyList();
            }

            List<CapabilityMatch> matches = entries.stream()
                    .map(e -> new CapabilityMatch(e.agentId(), e.capability().getSkill(),
                            e.capability().getConfidence(), e.capability().getAttributes()))
                    .collect(Collectors.toList());

            return applyStrategy(matches, strategy);
        });
    }

    /**
     * Find agents matching a skill and optional attribute constraints.
     *
     * @param skill
     *            the required skill
     * @param requiredAttributes
     *            attribute key-value pairs that must match
     * @param strategy
     *            selection strategy
     * @return filtered and ordered matches
     */
    public List<CapabilityMatch> findBySkillAndAttributes(String skill,
                                                          Map<String, String> requiredAttributes, String strategy) {
        List<CapabilityMatch> matches = findBySkill(skill, "all");

        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return applyStrategy(matches, strategy);
        }

        List<CapabilityMatch> filtered = matches.stream().filter(m -> {
            for (Map.Entry<String, String> req : requiredAttributes.entrySet()) {
                String attrValue = m.attributes().get(req.getKey());
                if (attrValue == null) {
                    return false;
                }
                // Support comma-separated lists: "en,de,fr" contains "de"
                if (!attrValue.contains(req.getValue())) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toList());

        return applyStrategy(filtered, strategy);
    }

    /**
     * Get all registered skills across all agents.
     */
    public Set<String> getAllSkills() {
        return Collections.unmodifiableSet(skillIndex.keySet());
    }

    private List<CapabilityMatch> applyStrategy(List<CapabilityMatch> matches, String strategy) {
        if (matches.isEmpty()) {
            return matches;
        }

        return switch (strategy != null ? strategy.toLowerCase(Locale.ROOT) : "all") {
            case "highest_confidence" -> {
                matches.sort(Comparator.comparingInt(m -> confidenceOrder(m.confidence())));
                yield matches;
            }
            case "round_robin" -> {
                // Simple shuffle — for true round-robin, state tracking would be needed
                List<CapabilityMatch> shuffled = new ArrayList<>(matches);
                Collections.shuffle(shuffled);
                yield shuffled;
            }
            default -> matches; // "all" — return in natural order
        };
    }

    /**
     * Convert confidence string to ordinal for sorting (highest first).
     */
    private int confidenceOrder(String confidence) {
        return switch (confidence != null ? confidence.toLowerCase(Locale.ROOT) : "") {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 3;
        };
    }

    // --- Data classes ---

    public record AgentCapabilityEntry(String agentId, Capability capability) {
    }

    public record CapabilityMatch(String agentId, String skill, String confidence,
            Map<String, String> attributes) {
    }
}
