/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.agents;

import ai.labs.eddi.engine.runtime.service.IAgentStoreService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.runtime.IWorkflowFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code enableMemoryTools} is the opt-in; {@code userMemoryConfig} is tuning
 * on top of it.
 * <p>
 * Requiring both made the second a hidden switch. Every field of
 * {@code UserMemoryConfig} already carries a working default, so an agent that
 * turned memory on and configured nothing else was asking for the defaults —
 * but got no memory tool at all, and no explanation, because the skip was
 * silent.
 */
@DisplayName("memory tools attach on enableMemoryTools alone")
class AgentStoreClientLibraryMemoryToolsTest {

    private IAgentStoreService agentStoreService;
    private AgentStoreClientLibrary library;

    @BeforeEach
    void setUp() {
        agentStoreService = mock(IAgentStoreService.class);
        library = new AgentStoreClientLibrary(agentStoreService, mock(IWorkflowFactory.class));
    }

    private AgentConfiguration configuration(boolean enableMemoryTools, AgentConfiguration.UserMemoryConfig memoryConfig) {
        var config = new AgentConfiguration();
        config.setWorkflows(List.of());
        config.setEnableMemoryTools(enableMemoryTools);
        config.setUserMemoryConfig(memoryConfig);
        return config;
    }

    @Test
    @DisplayName("enabled with no config — falls back to the defaults instead of skipping")
    void defaultsWhenConfigOmitted() throws Exception {
        when(agentStoreService.getAgentConfiguration("agent-1", 1)).thenReturn(configuration(true, null));

        var agent = library.getAgent("agent-1", 1);

        assertNotNull(agent.getUserMemoryConfig(),
                "an agent that enabled memory and tuned nothing must still get the tool");
        assertEquals("self", agent.getUserMemoryConfig().getDefaultVisibility());
    }

    @Test
    @DisplayName("enabled with a config — that config is used")
    void explicitConfigWins() throws Exception {
        var memoryConfig = new AgentConfiguration.UserMemoryConfig();
        memoryConfig.setDefaultVisibility("group");
        when(agentStoreService.getAgentConfiguration("agent-1", 1)).thenReturn(configuration(true, memoryConfig));

        var agent = library.getAgent("agent-1", 1);

        assertEquals(memoryConfig, agent.getUserMemoryConfig());
    }

    @Test
    @DisplayName("not enabled — no config, whatever else is set")
    void disabledStaysDisabled() throws Exception {
        when(agentStoreService.getAgentConfiguration("agent-1", 1))
                .thenReturn(configuration(false, new AgentConfiguration.UserMemoryConfig()));

        var agent = library.getAgent("agent-1", 1);

        assertNull(agent.getUserMemoryConfig(), "enableMemoryTools is the opt-in and it says no");
    }
}
