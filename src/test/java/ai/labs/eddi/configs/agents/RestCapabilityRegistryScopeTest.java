/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.rest.RestCapabilityRegistry;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.Capability;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F5 — {@code GET /capabilities} answers with agents the caller may use. */
@DisplayName("RestCapabilityRegistry scope")
class RestCapabilityRegistryScopeTest {

    private CapabilityRegistryService registry;
    private ResourceAccessGuard guard;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistryService(new SimpleMeterRegistry(), mock(IAgentStore.class), mock(IDocumentDescriptorStore.class));
        registry.initMetrics();
        register("mine", "support", "low");
        register("theirs", "support", "high");
        register("theirs", "support", "high");
        register("secret-only", "payroll", "high");
        guard = mock(ResourceAccessGuard.class);
        when(guard.hasAccess("mine", AccessLevel.USE)).thenReturn(true);
    }

    private void register(String agentId, String skill, String confidence) {
        var config = new AgentConfiguration();
        config.setCapabilities(List.of(new Capability(skill, Map.of(), confidence)));
        registry.register(agentId, config);
    }

    @Test
    @DisplayName("workspaces on: another team's agent is neither listed nor picked")
    void scopedToUsableAgents() {
        var rest = new RestCapabilityRegistry(registry, guard);

        assertEquals(List.of("mine"), rest.searchBySkill("support", "all").stream().map(CapabilityMatch::agentId).toList());
        // The best match the caller may use — not the foreign one, and not nothing.
        assertEquals(List.of("mine"), rest.searchBySkill("support", "highest_confidence").stream().map(CapabilityMatch::agentId).toList());
        assertEquals(Set.of("support"), rest.listSkills());
    }

    @Test
    @DisplayName("workspaces off: unfiltered")
    void unfilteredWhenSeeingEverything() {
        when(guard.seesEverything()).thenReturn(true);
        var rest = new RestCapabilityRegistry(registry, guard);

        assertEquals(2, rest.searchBySkill("support", "all").size());
        assertEquals(Set.of("support", "payroll"), rest.listSkills());
    }
}
