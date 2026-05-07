/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.Capability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryServiceTest {

    private CapabilityRegistryService service;

    @BeforeEach
    void setUp() {
        service = new CapabilityRegistryService(new SimpleMeterRegistry());
        service.initMetrics();
    }

    @Test
    void findBySkill_returnsMatchingAgents() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(
                new Capability("language-translation", Map.of("languages", "en,de,fr"), "high")));
        service.register("agent-1", config);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(
                new Capability("language-translation", Map.of("languages", "en,es"), "medium")));
        service.register("agent-2", config2);

        List<CapabilityMatch> matches = service.findBySkill("language-translation", "all");
        assertEquals(2, matches.size());
    }

    @Test
    void findBySkill_caseInsensitive() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability("Code-Review", Map.of(), "high")));
        service.register("agent-1", config);

        List<CapabilityMatch> matches = service.findBySkill("code-review", "all");
        assertEquals(1, matches.size());
        assertEquals("agent-1", matches.get(0).agentId());
    }

    @Test
    void findBySkill_highestConfidenceStrategy() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("analysis", Map.of(), "low")));
        service.register("agent-low", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("analysis", Map.of(), "high")));
        service.register("agent-high", config2);

        var config3 = new AgentConfiguration();
        config3.setCapabilities(List.of(new Capability("analysis", Map.of(), "medium")));
        service.register("agent-med", config3);

        List<CapabilityMatch> matches = service.findBySkill("analysis", "highest_confidence");
        assertEquals(3, matches.size());
        assertEquals("high", matches.get(0).confidence());
        assertEquals("medium", matches.get(1).confidence());
        assertEquals("low", matches.get(2).confidence());
    }

    @Test
    void findBySkill_noMatches() {
        List<CapabilityMatch> matches = service.findBySkill("nonexistent", "all");
        assertTrue(matches.isEmpty());
    }

    @Test
    void findBySkill_nullSkill() {
        List<CapabilityMatch> matches = service.findBySkill(null, "all");
        assertTrue(matches.isEmpty());
    }

    @Test
    void unregister_removesAgent() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability("skill-a", Map.of(), "high")));
        service.register("agent-1", config);

        assertEquals(1, service.findBySkill("skill-a", "all").size());

        service.unregister("agent-1");
        assertTrue(service.findBySkill("skill-a", "all").isEmpty());
    }

    @Test
    void register_replacesExistingCapabilities() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("old-skill", Map.of(), "high")));
        service.register("agent-1", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("new-skill", Map.of(), "high")));
        service.register("agent-1", config2);

        assertTrue(service.findBySkill("old-skill", "all").isEmpty());
        assertEquals(1, service.findBySkill("new-skill", "all").size());
    }

    @Test
    void findBySkillAndAttributes_filtersCorrectly() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(
                new Capability("translation", Map.of("languages", "en,de,fr"), "high")));
        service.register("agent-1", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(
                new Capability("translation", Map.of("languages", "en,es"), "medium")));
        service.register("agent-2", config2);

        // Search for German support
        var matches = service.findBySkillAndAttributes("translation",
                Map.of("languages", "de"), "all");
        assertEquals(1, matches.size());
        assertEquals("agent-1", matches.get(0).agentId());
    }

    @Test
    void getAllSkills_returnsRegisteredSkills() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(
                new Capability("skill-a", Map.of(), "high"),
                new Capability("skill-b", Map.of(), "medium")));
        service.register("agent-1", config);

        Set<String> skills = service.getAllSkills();
        assertTrue(skills.contains("skill-a"));
        assertTrue(skills.contains("skill-b"));
    }

    @Test
    void register_skipsBlankSkills() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(
                new Capability("", Map.of(), "high"),
                new Capability("valid-skill", Map.of(), "medium")));
        service.register("agent-1", config);

        assertEquals(1, service.getAllSkills().size());
        assertTrue(service.getAllSkills().contains("valid-skill"));
    }

    // --- Wave 3: round_robin deterministic rotation ---

    @Test
    void findBySkill_roundRobinRotatesDeterministicallyAcross100Calls() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("routing", Map.of(), "high")));
        service.register("agent-A", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("routing", Map.of(), "medium")));
        service.register("agent-B", config2);

        var config3 = new AgentConfiguration();
        config3.setCapabilities(List.of(new Capability("routing", Map.of(), "low")));
        service.register("agent-C", config3);

        // Over 100 calls, the first-returned agent should cycle A→B→C deterministically
        String[] expectedFirstAgents = new String[100];
        String[] agents = {"agent-A", "agent-B", "agent-C"};
        for (int i = 0; i < 100; i++) {
            expectedFirstAgents[i] = agents[i % 3];
        }

        for (int i = 0; i < 100; i++) {
            List<CapabilityMatch> matches = service.findBySkill("routing", "round_robin");
            assertEquals(3, matches.size());
            assertEquals(expectedFirstAgents[i], matches.get(0).agentId(),
                    "Call #" + i + " should start with " + expectedFirstAgents[i]);
        }
    }

    @Test
    void findBySkill_roundRobinCounterResetsOnRegister() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("rr-skill", Map.of(), "high")));
        service.register("agent-1", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("rr-skill", Map.of(), "medium")));
        service.register("agent-2", config2);

        // Advance the counter a few times
        service.findBySkill("rr-skill", "round_robin");
        service.findBySkill("rr-skill", "round_robin");

        // Now re-register agent-1 — counter should reset to 0
        // After unregister+re-add: list order is [agent-2, agent-1]
        service.register("agent-1", config1);

        // After reset, first call should start at index 0 (which is agent-2)
        List<CapabilityMatch> matches = service.findBySkill("rr-skill", "round_robin");
        assertEquals("agent-2", matches.get(0).agentId());

        // Second call should rotate to agent-1
        matches = service.findBySkill("rr-skill", "round_robin");
        assertEquals("agent-1", matches.get(0).agentId());

        // Third call wraps back to agent-2
        matches = service.findBySkill("rr-skill", "round_robin");
        assertEquals("agent-2", matches.get(0).agentId());
    }

    @Test
    void findBySkill_roundRobinCounterResetsOnUnregister() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("unreg-skill", Map.of(), "high")));
        service.register("agent-1", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("unreg-skill", Map.of(), "medium")));
        service.register("agent-2", config2);

        // Advance the counter
        service.findBySkill("unreg-skill", "round_robin");

        // Unregister agent-2 — counter should reset, agent-1 still there
        service.unregister("agent-2");

        List<CapabilityMatch> matches = service.findBySkill("unreg-skill", "round_robin");
        assertEquals(1, matches.size());
        assertEquals("agent-1", matches.get(0).agentId());
    }

    // --- Wave 3: random strategy ---

    @Test
    void findBySkill_randomStrategyReturnsAllAgents() {
        var config1 = new AgentConfiguration();
        config1.setCapabilities(List.of(new Capability("rand-skill", Map.of(), "high")));
        service.register("agent-1", config1);

        var config2 = new AgentConfiguration();
        config2.setCapabilities(List.of(new Capability("rand-skill", Map.of(), "medium")));
        service.register("agent-2", config2);

        List<CapabilityMatch> matches = service.findBySkill("rand-skill", "random");
        assertEquals(2, matches.size());

        // Verify both agents are present regardless of order
        Set<String> agentIds = matches.stream().map(CapabilityMatch::agentId).collect(java.util.stream.Collectors.toSet());
        assertTrue(agentIds.contains("agent-1"));
        assertTrue(agentIds.contains("agent-2"));
    }

    // --- Wave 3: metrics ---

    @Test
    void findBySkill_missMetricIncrementsOnNoResults() {
        var registry = new SimpleMeterRegistry();
        var svc = new CapabilityRegistryService(registry);
        svc.initMetrics();

        svc.findBySkill("nonexistent-skill", "all");

        double missCount = registry.counter("eddi.capability.miss.count", "skill", "nonexistent-skill").count();
        assertEquals(1.0, missCount);
    }

    @Test
    void findBySkill_strategyMetricIncrementsOnEachCall() {
        var registry = new SimpleMeterRegistry();
        var svc = new CapabilityRegistryService(registry);
        svc.initMetrics();

        svc.findBySkill("anything", "highest_confidence");
        svc.findBySkill("anything", "highest_confidence");
        svc.findBySkill("anything", "round_robin");

        assertEquals(2.0, registry.counter("eddi.capability.strategy.applied", "strategy", "highest_confidence").count());
        assertEquals(1.0, registry.counter("eddi.capability.strategy.applied", "strategy", "round_robin").count());
    }

    @Test
    void findBySkill_nullStrategyDefaultsToAll() {
        var registry = new SimpleMeterRegistry();
        var svc = new CapabilityRegistryService(registry);
        svc.initMetrics();

        svc.findBySkill("anything", null);

        assertEquals(1.0, registry.counter("eddi.capability.strategy.applied", "strategy", "all").count());
    }

    @Test
    void findBySkill_missMetricNotIncrementedWhenSkillExists() {
        var registry = new SimpleMeterRegistry();
        var svc = new CapabilityRegistryService(registry);
        svc.initMetrics();

        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability("existing-skill", Map.of(), "high")));
        svc.register("agent-1", config);

        svc.findBySkill("existing-skill", "all");

        assertEquals(0.0, registry.counter("eddi.capability.miss.count", "skill", "existing-skill").count());
    }

    // --- Wave 3: edge cases ---

    @Test
    void findBySkill_blankSkillReturnsEmpty() {
        List<CapabilityMatch> matches = service.findBySkill("  ", "all");
        assertTrue(matches.isEmpty());
    }

    @Test
    void register_nullCapabilitiesIsNoOp() {
        var config = new AgentConfiguration();
        config.setCapabilities(null);
        service.register("agent-1", config);

        assertTrue(service.getAllSkills().isEmpty());
    }

    @Test
    void register_emptyCapabilitiesIsNoOp() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of());
        service.register("agent-1", config);

        assertTrue(service.getAllSkills().isEmpty());
    }

    @Test
    void unregister_nonexistentAgentIsNoOp() {
        // Should not throw
        assertDoesNotThrow(() -> service.unregister("nonexistent-agent"));
    }

    @Test
    void findBySkillAndAttributes_nullAttributesPassesThrough() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability("skill-x", Map.of("key", "val"), "high")));
        service.register("agent-1", config);

        var matches = service.findBySkillAndAttributes("skill-x", null, "all");
        assertEquals(1, matches.size());
    }

    @Test
    void findBySkillAndAttributes_emptyAttributesPassesThrough() {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability("skill-y", Map.of("key", "val"), "high")));
        service.register("agent-1", config);

        var matches = service.findBySkillAndAttributes("skill-y", Map.of(), "all");
        assertEquals(1, matches.size());
    }
}
