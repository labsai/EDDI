/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers mcpcalls configurations from the agent's workflow and connects to
 * each configured MCP server, applying per-config whitelist/blacklist filtering
 * (R2 step 2). Extracted from {@code AgentOrchestrator} as a pure move — no
 * behavior change; {@code AgentOrchestrator.discoverMcpCallTools} is kept as a
 * declared delegator (adapting {@link ToolContribution} back to the legacy
 * {@code McpToolProviderManager.McpToolsResult} shape) since
 * {@code buildToolSetup}'s merge flow is not yet rewired to iterate
 * {@link ToolSourceProvider}s.
 * <p>
 * Lives in {@code ai.labs.eddi.modules.llm.impl} — same package as {@code
 * AgentOrchestrator} and {@link HttpCallToolsProvider} — for the same reason:
 * it depends on the package-private {@link WorkflowTraversal}.
 * <p>
 * {@code McpToolsResult.failures()} — per-server discovery failures with a
 * reason, populated by {@code McpToolProviderManager.discoverTools} — is READ
 * nowhere in this class, matching the pre-extraction behavior exactly: the
 * original {@code discoverMcpCallTools} only ever consulted {@code
 * result.toolSpecs()}/{@code result.executors()} from the per-server result.
 * Preserved as-is (pure move); flagged separately as a possible follow-up
 * (per-server misconfiguration currently has no signal above the manager's own
 * logging), not fixed inline here.
 */
class McpToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(McpToolsProvider.class);
    private static final String MCPCALLS_TYPE = "eddi://ai.labs.mcpcalls";

    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final McpToolProviderManager mcpToolProviderManager;

    McpToolsProvider(IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore,
            IResourceClientLibrary resourceClientLibrary, McpToolProviderManager mcpToolProviderManager) {
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.mcpToolProviderManager = mcpToolProviderManager;
    }

    @Override
    public String source() {
        return "mcp";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        boolean enabled = ctx.task().getEnableMcpCallTools() == null || ctx.task().getEnableMcpCallTools();
        if (!enabled) {
            return ToolContribution.empty();
        }
        var result = discover(ctx.memory());
        return new ToolContribution(result.toolSpecs(), result.executors(), Map.of(), Map.of());
    }

    /**
     * Discovers mcpcalls configurations from the workflow and creates filtered
     * ToolSpecification + ToolExecutor pairs via McpToolProviderManager.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter mcpCalls steps → load McpCallsConfiguration →
     * apply whitelist/blacklist → return filtered tools.
     */
    McpToolProviderManager.McpToolsResult discover(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        try {
            LOGGER.infof("Discovering mcpcalls tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, MCPCALLS_TYPE, McpCallsConfiguration.class, restAgentStore, restWorkflowStore,
                    resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                McpCallsConfiguration mcpCallsConfig = stepConfig.config();

                // Build server config from McpCallsConfiguration
                McpServerConfig serverConfig = new McpServerConfig();
                serverConfig.setUrl(mcpCallsConfig.getMcpServerUrl());
                serverConfig.setName(mcpCallsConfig.getName());
                serverConfig.setTransport(mcpCallsConfig.getTransport());
                serverConfig.setApiKey(mcpCallsConfig.getApiKey());
                serverConfig.setTimeoutMs(mcpCallsConfig.getTimeoutMs());

                // Discover tools from this MCP server
                McpToolProviderManager.McpToolsResult result = mcpToolProviderManager.discoverTools(List.of(serverConfig));

                // Apply whitelist/blacklist filtering
                List<String> whitelist = mcpCallsConfig.getToolsWhitelist();
                List<String> blacklist = mcpCallsConfig.getToolsBlacklist();

                for (ToolSpecification spec : result.toolSpecs()) {
                    String name = spec.name();
                    if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(name))
                        continue;
                    if (blacklist != null && blacklist.contains(name))
                        continue;

                    toolSpecs.add(spec);
                    ToolExecutor executor = result.executors().get(name);
                    if (executor != null) {
                        executors.put(name, executor);
                    }
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " mcpcalls tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover mcpcalls tools from workflow", e);
        }

        return new McpToolProviderManager.McpToolsResult(toolSpecs, executors);
    }
}
