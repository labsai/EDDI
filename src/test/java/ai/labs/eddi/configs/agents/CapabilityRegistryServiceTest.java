/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.Capability;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityRegistryServiceTest {

    private CapabilityRegistryService service;

    /**
     * The store arguments are only used by the startup seeding, which these tests
     * do not exercise, so plain mocks suffice. They used to be {@code null}, which
     * forced a null guard into {@code populateFromStore} for two
     * constructor-injected CDI beans that can never be null in production —
     * production code shaped by a test. {@code StartupSeeding} builds its own
     * stubbed stores.
     */
    private static CapabilityRegistryService newService(MeterRegistry registry) {
        var service = new CapabilityRegistryService(registry, mock(IAgentStore.class), mock(IDocumentDescriptorStore.class));
        service.initMetrics();
        return service;
    }

    @BeforeEach
    void setUp() {
        service = newService(new SimpleMeterRegistry());
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
        Set<String> agentIds = matches.stream().map(CapabilityMatch::agentId).collect(Collectors.toSet());
        assertTrue(agentIds.contains("agent-1"));
        assertTrue(agentIds.contains("agent-2"));
    }

    // --- Wave 3: metrics ---

    @Test
    void findBySkill_missMetricIncrementsOnNoResults() {
        var registry = new SimpleMeterRegistry();
        var svc = newService(registry);

        svc.findBySkill("nonexistent-skill", "all");

        // Counted in aggregate, NOT tagged with the skill. This assertion used to read
        // counter(name, "skill", "nonexistent-skill"), which pinned an unbounded meter
        // cardinality: the skill string comes from HTTP query params, A2A discovery and
        // an LLM-invoked tool, so every invented name minted a Meter for the lifetime
        // of the process.
        assertEquals(1.0, registry.counter("eddi.capability.miss.count").count());
        assertTrue(registry.find("eddi.capability.miss.count").counters().stream()
                .allMatch(c -> c.getId().getTag("skill") == null), "the miss counter must carry no per-skill tag");
    }

    @Test
    void findBySkill_strategyMetricIncrementsOnEachCall() {
        var registry = new SimpleMeterRegistry();
        var svc = newService(registry);

        svc.findBySkill("anything", "highest_confidence");
        svc.findBySkill("anything", "highest_confidence");
        svc.findBySkill("anything", "round_robin");

        assertEquals(2.0, registry.counter("eddi.capability.strategy.applied", "strategy", "highest_confidence").count());
        assertEquals(1.0, registry.counter("eddi.capability.strategy.applied", "strategy", "round_robin").count());
    }

    /**
     * The {@code strategy} tag came straight from the caller — a query parameter on
     * {@code GET /capabilities}, or whatever an LLM-invoked tool passed — so an
     * unrecognised value minted a Meter that lives for the process, exactly the
     * unbounded cardinality the untagged miss counter fixes on the other side.
     * Folding onto the four the strategy switch understands bounds the tag set.
     */
    @Test
    void findBySkill_unknownStrategyIsFoldedOntoABoundedTagSet() {
        var registry = new SimpleMeterRegistry();
        var svc = newService(registry);

        svc.findBySkill("anything", "invented-by-a-model-42");
        svc.findBySkill("anything", "another-invention");

        assertEquals(2.0, registry.counter("eddi.capability.strategy.applied", "strategy", "other").count());
        Set<String> tags = registry.find("eddi.capability.strategy.applied").counters().stream()
                .map(counter -> counter.getId().getTag("strategy")).collect(Collectors.toSet());
        assertEquals(Set.of("other"), tags, "an unrecognised strategy must not mint its own meter");
    }

    @Test
    void findBySkill_nullStrategyDefaultsToAll() {
        var registry = new SimpleMeterRegistry();
        var svc = newService(registry);

        svc.findBySkill("anything", null);

        assertEquals(1.0, registry.counter("eddi.capability.strategy.applied", "strategy", "all").count());
    }

    @Test
    void findBySkill_missMetricNotIncrementedWhenSkillExists() {
        var registry = new SimpleMeterRegistry();
        var svc = newService(registry);

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

    /**
     * Seeding at startup, moved here from {@code RestAgentStoreExpandedTest} along
     * with the code: it was a {@code @PostConstruct} on {@code RestAgentStore}, an
     * {@code @ApplicationScoped} JAX-RS bean ArC only instantiates on first use —
     * so on a fresh node nothing ran it and the index stayed empty until an admin
     * happened to open the Manager.
     */
    @Nested
    @DisplayName("startup seeding")
    class StartupSeeding {

        private static final String AGENT_ID = "aabbccddeeff112233445566";

        private DocumentDescriptor agentDescriptor() {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));
            return descriptor;
        }

        @Test
        @DisplayName("registers agents that declare capabilities")
        void registersCapabilities() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var descriptorStore = mock(IDocumentDescriptorStore.class);
            when(descriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false)).thenReturn(List.of(agentDescriptor()));

            var config = new AgentConfiguration();
            config.setCapabilities(List.of(new Capability("greeting", Map.of(), "medium"),
                    new Capability("farewell", Map.of(), "medium")));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(config);

            var svc = new CapabilityRegistryService(new SimpleMeterRegistry(), agentStore, descriptorStore);
            svc.initMetrics();
            svc.onStartup(null);

            assertEquals(Set.of("greeting", "farewell"), svc.getAllSkills());
            assertEquals(1, svc.findBySkill("greeting", "all").size());
        }

        @Test
        @DisplayName("skips agents without capabilities")
        void skipsNonCapableAgents() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var descriptorStore = mock(IDocumentDescriptorStore.class);
            when(descriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false)).thenReturn(List.of(agentDescriptor()));
            when(agentStore.read(AGENT_ID, 1)).thenReturn(new AgentConfiguration());

            var svc = new CapabilityRegistryService(new SimpleMeterRegistry(), agentStore, descriptorStore);
            svc.initMetrics();
            svc.onStartup(null);

            assertTrue(svc.getAllSkills().isEmpty());
        }

        @Test
        @DisplayName("one unreadable agent does not abort the seeding")
        void individualAgentFailure() throws Exception {
            var agentStore = mock(IAgentStore.class);
            var descriptorStore = mock(IDocumentDescriptorStore.class);
            when(descriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false)).thenReturn(List.of(agentDescriptor()));
            when(agentStore.read(anyString(), any())).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            var svc = new CapabilityRegistryService(new SimpleMeterRegistry(), agentStore, descriptorStore);
            svc.initMetrics();

            assertDoesNotThrow(() -> svc.onStartup(null));
            assertTrue(svc.getAllSkills().isEmpty());
        }

        @Test
        @DisplayName("an unreachable store does not break startup")
        void totalFailure() throws Exception {
            var descriptorStore = mock(IDocumentDescriptorStore.class);
            when(descriptorStore.readDescriptors("ai.labs.agent", null, 0, 0, false)).thenThrow(new RuntimeException("db error"));

            var svc = new CapabilityRegistryService(new SimpleMeterRegistry(), mock(IAgentStore.class), descriptorStore);
            svc.initMetrics();

            assertDoesNotThrow(() -> svc.onStartup(null));
            assertTrue(svc.getAllSkills().isEmpty(), "an unreachable store must leave the index empty, not half-built");
        }

        /**
         * The other three tests call {@code onStartup} by hand, which proves what the
         * seeding does but not that anything ever calls it — and "nothing calls it" is
         * exactly the defect this change fixes. Nothing in the runnable unit suite
         * boots ArC, so the wiring is asserted structurally instead: the bean is
         * {@code @ApplicationScoped} and the method takes an {@code @Observes}
         * {@code StartupEvent}, which is what makes ArC create the bean eagerly and
         * fire it. (Whether the new IAgentStore/IDocumentDescriptorStore injection
         * points resolve without a CDI cycle is still only provable at boot, i.e. in
         * CI.)
         */
        @Test
        @DisplayName("the seeding is wired as a StartupEvent observer on an @ApplicationScoped bean")
        void seedingIsAStartupObserver() throws Exception {
            assertTrue(CapabilityRegistryService.class.isAnnotationPresent(ApplicationScoped.class),
                    "a StartupEvent observer only fires on a CDI bean");

            Method onStartup = CapabilityRegistryService.class.getDeclaredMethod("onStartup", StartupEvent.class);
            Annotation[] parameterAnnotations = onStartup.getParameterAnnotations()[0];
            assertTrue(Stream.of(parameterAnnotations).anyMatch(a -> a.annotationType() == Observes.class),
                    "without @Observes this is an ordinary method nothing calls, and the index stays empty on a fresh node");
        }
    }
}
