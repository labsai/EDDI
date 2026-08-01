/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;

import java.util.List;
import java.util.Map;

/**
 * Discovers A2A (agent-to-agent) tools for the task's configured peer agents
 * (R2 step 2). Extracted from the inline block at the top of {@code
 * AgentOrchestrator#buildToolSetup} — unlike HTTP/MCP discovery this was never
 * a separate named method, just a five-line config-gated call into
 * {@link A2AToolProviderManager#discoverTools}, so there is nothing to keep as
 * a reflected delegator. {@code buildToolSetup} still calls
 * {@link A2AToolProviderManager} directly for now (unchanged); this provider
 * exists so the later step that rewires {@code buildToolSetup} to iterate
 * providers has all 8 sources uniformly SPI-conformant rather than 7 plus one
 * special-cased inline block.
 * <p>
 * Same package as {@code AgentOrchestrator} as the other two providers, for
 * consistency — this one has no {@link WorkflowTraversal} dependency, so it
 * would work equally well in a separate package, but splitting one provider out
 * from its seven siblings for no functional reason is not an improvement.
 */
class A2AToolsProvider implements ToolSourceProvider {

    private final A2AToolProviderManager a2aToolProviderManager;

    A2AToolsProvider(A2AToolProviderManager a2aToolProviderManager) {
        this.a2aToolProviderManager = a2aToolProviderManager;
    }

    @Override
    public String source() {
        return "a2a";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        List<A2AAgentConfig> a2aAgents = ctx.task().getA2aAgents();
        if (a2aAgents == null || a2aAgents.isEmpty()) {
            return ToolContribution.empty();
        }
        var result = a2aToolProviderManager.discoverTools(a2aAgents);
        return new ToolContribution(result.toolSpecs(), result.executors(), Map.of(), Map.of());
    }
}
