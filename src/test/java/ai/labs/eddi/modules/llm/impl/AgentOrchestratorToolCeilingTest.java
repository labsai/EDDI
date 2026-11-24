/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.tools.impl.DiscoverToolsTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tool assembly under an email sender role's tool ceiling (email plan v3 §9):
 * the model is offered only the permitted tools, and LAZY loading is switched
 * off so {@code discover_tools} neither needs an exemption nor advertises
 * hidden tools.
 */
@DisplayName("AgentOrchestrator — tools under a sender-role ceiling")
class AgentOrchestratorToolCeilingTest {

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static AgentOrchestrator.ToolSetup lazySetup() {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (String name : List.of("kb_search", "http_post", "handoff_to_team", DiscoverToolsTool.NAME)) {
            executors.put(name, mock(ToolExecutor.class));
        }
        Map<String, String> sources = new HashMap<>(Map.of("kb_search", "http", "http_post", "http", "handoff_to_team", "team"));
        return new AgentOrchestrator.ToolSetup(
                List.of(spec("kb_search"), spec("http_post"), spec("handoff_to_team"), spec(DiscoverToolsTool.NAME)), executors, sources,
                List.of(spec("kb_search"), spec("http_post")), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    @Test
    @DisplayName("only permitted tools are offered and executable; spawning tools and discover_tools are dropped")
    void filtersToTheCeiling() {
        var filtered = AgentOrchestrator.withinToolCeiling(lazySetup(), Set.of("kb_search", "handoff_to_team"));

        assertEquals(List.of("kb_search"), filtered.toolSpecs().stream().map(ToolSpecification::name).toList());
        assertEquals(Set.of("kb_search"), filtered.toolExecutors().keySet());
        assertEquals(Set.of("kb_search"), filtered.toolSources().keySet());
        assertTrue(filtered.builtInSpecs().isEmpty(), "LAZY is off under a ceiling: everything permitted is offered at once");
    }

    @Test
    @DisplayName("an empty ceiling offers no tool at all")
    void emptyCeiling() {
        assertTrue(AgentOrchestrator.withinToolCeiling(lazySetup(), Set.of()).toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("without a ceiling the setup is untouched")
    void noCeiling() {
        var setup = lazySetup();
        assertSame(setup, AgentOrchestrator.withinToolCeiling(setup, null));
    }
}
