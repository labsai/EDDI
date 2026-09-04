/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.Capability;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.utils.RestUtilities;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * In-memory capability registry for A2A agent discovery.
 * <p>
 * Agents register their structured {@link Capability} declarations at
 * deployment time. Other agents (or external systems via REST / MCP) can query
 * the registry to find agents that match a required skill. Selection strategies
 * (highest_confidence, round_robin, all) are deterministic algorithms, not LLM
 * guesses.
 *
 * <h3>How the index is maintained</h3> It is seeded once at startup from
 * {@link IAgentStore} (see {@link #onStartup}), and kept current by
 * <em>explicit</em> {@link #register}/{@link #unregister} calls from
 * {@code RestAgentStore}'s create/update/delete. There is no observer
 * mechanism: {@code @ConfigurationUpdate} is an interceptor binding with no
 * interceptor behind it (AGENTS.md §4.3), and the Javadoc here used to claim
 * otherwise — which is why agent-creating paths that bypass the REST facade
 * (ZIP import, MCP) were written without a {@code register} call and their
 * agents were never discoverable. Any new creation path must call
 * {@link #register} itself.
 * <p>
 * The index is process-local. A cluster node learns about another node's edits
 * only on its own restart.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class CapabilityRegistryService {
    private static final Logger LOGGER = Logger.getLogger(CapabilityRegistryService.class);
    private static final String AGENT_DESCRIPTOR_TYPE = "ai.labs.agent";

    /**
     * Index: skill name → list of (agentId, capability) pairs. Rebuilt on agent
     * configuration changes.
     */
    private final Map<String, List<AgentCapabilityEntry>> skillIndex = new ConcurrentHashMap<>();

    /**
     * Per-skill round-robin counters. Reset when agents are registered or
     * unregistered to avoid drift on topology changes.
     */
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;
    private final IAgentStore agentStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private Counter queryCounter;
    private Timer queryTimer;

    @Inject
    public CapabilityRegistryService(MeterRegistry meterRegistry, IAgentStore agentStore,
            IDocumentDescriptorStore documentDescriptorStore) {
        this.meterRegistry = meterRegistry;
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @PostConstruct
    void initMetrics() {
        queryCounter = meterRegistry.counter("eddi.capability.query.count");
        queryTimer = meterRegistry.timer("eddi.capability.query.time");
    }

    /**
     * Seeds the index from every stored agent, once, at startup.
     *
     * <p>
     * This used to be a {@code @PostConstruct} on {@code RestAgentStore} — an
     * {@code @ApplicationScoped} JAX-RS resource that ArC instantiates lazily, on
     * first client-proxy call. Nothing eager touches that bean, so on a fresh node
     * the index stayed EMPTY until an admin happened to open the Manager: a
     * {@code capabilityMatch} behaviour rule simply never fired, and A2A discovery
     * returned nothing, with no error to explain it. Observing {@link StartupEvent}
     * on the bean that owns the index makes the seeding independent of who is
     * injected where.
     * </p>
     */
    void onStartup(@Observes StartupEvent event) {
        populateFromStore();
    }

    void populateFromStore() {
        try {
            var descriptors = documentDescriptorStore.readDescriptors(AGENT_DESCRIPTOR_TYPE, null, 0, IDescriptorStore.NO_LIMIT, false);
            int registered = 0;
            for (var descriptor : descriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(descriptor.getResource());
                    var config = agentStore.read(resourceId.getId(), resourceId.getVersion());
                    if (config.getCapabilities() != null && !config.getCapabilities().isEmpty()) {
                        register(resourceId.getId(), config);
                        registered++;
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Skipping capability registration for agent: %s", e.getMessage());
                }
            }
            if (registered > 0) {
                LOGGER.infof("Capability registry populated: %d agent(s) with capabilities", registered);
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to populate capability registry at startup: %s", e.getMessage());
        }
    }

    /**
     * Register all capabilities for an agent. Replaces any previous registration.
     *
     * @param agentId
     *            the agent's unique ID
     * @param config
     *            the agent's configuration containing capabilities
     */
    public synchronized void register(String agentId, AgentConfiguration config) {
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
            // Reset round-robin counter on topology change
            roundRobinCounters.put(skill, new AtomicInteger(0));
        }

        LOGGER.debugf("Registered %d capabilities for agent '%s'",
                config.getCapabilities().size(), agentId);
    }

    /**
     * Remove all capability entries for an agent.
     *
     * <p>
     * {@code synchronized}, like {@link #register}, because the two are not safe
     * against each other on a {@code ConcurrentHashMap} alone: registering is
     * get-list-then-add, and an interleaved {@code removeIf} could observe the
     * freshly created EMPTY list between those two steps, drop the map entry, and
     * leave the registering thread adding to an orphaned list — the skill silently
     * missing from the index until the next full re-registration. These are rare
     * admin operations, so a lock costs nothing that matters.
     * </p>
     */
    public synchronized void unregister(String agentId) {
        skillIndex.values().forEach(entries -> entries.removeIf(e -> e.agentId().equals(agentId)));
        // Clean up empty skill entries and reset round-robin counters
        skillIndex.entrySet().removeIf(entry -> {
            if (entry.getValue().isEmpty()) {
                roundRobinCounters.remove(entry.getKey());
                return true;
            }
            // Reset counter on topology change even if skill still has entries
            roundRobinCounters.put(entry.getKey(), new AtomicInteger(0));
            return false;
        });
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
            String resolvedStrategy = strategy != null ? strategy.toLowerCase(Locale.ROOT) : "all";
            meterRegistry.counter("eddi.capability.strategy.applied", "strategy", knownStrategy(resolvedStrategy)).increment();

            List<CapabilityMatch> matches = lookupBySkill(skill);
            if (matches.isEmpty()) {
                return matches;
            }

            String normalizedSkill = skill.toLowerCase(Locale.ROOT).trim();
            return applyStrategy(matches, resolvedStrategy, normalizedSkill);
        });
    }

    /**
     * Internal lookup — returns all matches for a skill without emitting strategy
     * metrics. Used by {@link #findBySkillAndAttributes} to avoid double-counting.
     */
    private List<CapabilityMatch> lookupBySkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedSkill = skill.toLowerCase(Locale.ROOT).trim();
        List<AgentCapabilityEntry> entries = skillIndex.getOrDefault(normalizedSkill, Collections.emptyList());

        if (entries.isEmpty()) {
            // Deliberately untagged. The skill string arrives from GET /capabilities,
            // A2A discovery, a templated capabilityMatch rule, and the LLM-invoked
            // FindAgentsByCapabilityTool — a model that invents skill names mints an
            // unbounded number of Meters, one per distinct miss, for the lifetime of the
            // process. Which skill was missed belongs in a log line, not a metric label.
            meterRegistry.counter("eddi.capability.miss.count").increment();
            LOGGER.debugf("No agent registered for skill '%s'", normalizedSkill);
            return Collections.emptyList();
        }

        return entries.stream()
                .map(e -> new CapabilityMatch(e.agentId(), e.capability().getSkill(),
                        e.capability().getConfidence(), e.capability().getAttributes()))
                .collect(Collectors.toList());
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
        List<CapabilityMatch> matches = lookupBySkill(skill);
        String normalizedSkill = skill != null ? skill.toLowerCase(Locale.ROOT).trim() : "";
        String resolvedStrategy = strategy != null ? strategy.toLowerCase(Locale.ROOT) : "all";

        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return applyStrategy(matches, resolvedStrategy, normalizedSkill);
        }

        List<CapabilityMatch> filtered = matches.stream().filter(m -> {
            for (Map.Entry<String, String> req : requiredAttributes.entrySet()) {
                String attrValue = m.attributes().get(req.getKey());
                if (attrValue == null) {
                    return false;
                }
                // Support comma-separated lists: "en,de,fr" matches "de"
                var items = Arrays.stream(attrValue.split(","))
                        .map(String::trim)
                        .collect(Collectors.toSet());
                if (!items.contains(req.getValue())) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toList());

        return applyStrategy(filtered, resolvedStrategy, normalizedSkill);
    }

    /**
     * Get all registered skills across all agents.
     */
    public Set<String> getAllSkills() {
        return Collections.unmodifiableSet(skillIndex.keySet());
    }

    private List<CapabilityMatch> applyStrategy(List<CapabilityMatch> matches, String strategy, String skill) {
        if (matches.isEmpty()) {
            return matches;
        }

        return switch (strategy) {
            case "highest_confidence" -> {
                matches.sort(Comparator.comparingInt(m -> confidenceOrder(m.confidence())));
                yield matches;
            }
            case "round_robin" -> {
                // Deterministic rotation: per-skill AtomicInteger counter
                AtomicInteger counter = roundRobinCounters.computeIfAbsent(skill, k -> new AtomicInteger(0));
                int index = Math.floorMod(counter.getAndIncrement(), matches.size());
                List<CapabilityMatch> rotated = new ArrayList<>(matches.size());
                for (int i = 0; i < matches.size(); i++) {
                    rotated.add(matches.get((index + i) % matches.size()));
                }
                yield rotated;
            }
            case "random" -> {
                List<CapabilityMatch> shuffled = new ArrayList<>(matches);
                Collections.shuffle(shuffled);
                yield shuffled;
            }
            default -> matches; // "all" — return in natural order
        };
    }

    /**
     * Folds an arbitrary caller-supplied strategy onto the four the switch in
     * {@link #applyStrategy} actually understands, so the {@code strategy} meter
     * tag has a bounded value set. Anything else behaves as "all" and is tagged
     * {@code other}.
     */
    private static String knownStrategy(String strategy) {
        return switch (strategy) {
            case "highest_confidence", "round_robin", "random", "all" -> strategy;
            default -> "other";
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
