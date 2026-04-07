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
}
