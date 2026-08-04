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
import ai.labs.eddi.modules.llm.tools.spi.ProviderFailure;
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
 * behavior change. {@code AgentOrchestrator.discoverMcpCallTools} remains as a
 * declared delegator, adapting {@link ToolContribution} back to the legacy
 * {@code McpToolProviderManager.McpToolsResult} shape, because the
 * reflection-based characterization tests look that method up by name on
 * {@code AgentOrchestrator} itself.
 * <p>
 * Lives in {@code ai.labs.eddi.modules.llm.impl} — same package as {@code
 * AgentOrchestrator} and {@link HttpCallToolsProvider} — for the same reason:
 * it depends on the package-private {@link WorkflowTraversal}.
 * <p>
 * {@code McpToolsResult.failures()} — per-server discovery failures with a
 * reason, populated by {@code McpToolProviderManager.discoverTools} — used to
 * be computed and then dropped: the pre-SPI {@code discoverMcpCallTools}
 * consulted only {@code toolSpecs()}/{@code executors()}, so an unreachable or
 * misconfigured server had no signal above the manager's own log line. The
 * extraction preserved that; the rewiring fixes it. {@link #discover} now
 * accumulates them across servers and {@link #contribute} maps them onto
 * {@link ProviderFailure}, which {@code ToolSourceRegistry} collects for the
 * turn. The two {@code Kind} enums were already one-to-one.
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
        return new ToolContribution(result.toolSpecs(), result.executors(), Map.of(), Map.of(),
                asProviderFailures(result.failures()), Map.of());
    }

    /**
     * Adapts per-server MCP failures onto the SPI's structured failure shape.
     * <p>
     * Before the rewiring these were computed and then dropped — {@code
     * buildToolSetup} consulted only specs and executors, so a misconfigured or
     * unreachable server had no signal above this manager's own log line. Carrying
     * them into the contribution costs nothing and puts them where a caller can
     * finally surface them; the two {@code Kind} enums were already one-to-one.
     */
    private static List<ProviderFailure> asProviderFailures(List<McpToolProviderManager.McpServerFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return List.of();
        }
        return failures.stream()
                .map(f -> new ProviderFailure("mcp", f.serverName(), switch (f.kind()) {
                    case INVALID_CONFIGURATION -> ProviderFailure.Kind.INVALID_CONFIGURATION;
                    case CONNECTION_FAILURE -> ProviderFailure.Kind.CONNECTION_FAILURE;
                    case CIRCUIT_OPEN -> ProviderFailure.Kind.CIRCUIT_OPEN;
                }, f.message()))
                .toList();
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
        // Accumulated across servers so a misconfigured or unreachable one has a
        // signal above this class's own log line — see asProviderFailures.
        List<McpToolProviderManager.McpServerFailure> failures = new ArrayList<>();

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
                failures.addAll(result.failures());

                // Apply whitelist/blacklist filtering
                List<String> whitelist = mcpCallsConfig.getToolsWhitelist();
                List<String> blacklist = mcpCallsConfig.getToolsBlacklist();

                for (ToolSpecification spec : result.toolSpecs()) {
                    String name = spec.name();
                    if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(name))
                        continue;
                    if (blacklist != null && blacklist.contains(name))
                        continue;

                    // First-write-wins WITHIN this provider, and only when the spec has
                    // an executor. Without it, two MCP servers exposing the same tool
                    // name put two specs in the list while `executors` (a map) kept the
                    // LAST server's — and the registry then pairs the FIRST spec with
                    // that executor. The model is shown one server's signature and a
                    // different server's tool runs, which is a tool-confusion bug one
                    // hostile or careless server can trigger against another's name.
                    ToolExecutor executor = result.executors().get(name);
                    if (executor == null) {
                        LOGGER.warnf("mcpcalls tool '%s' has a specification but no executor — skipping", name);
                        continue;
                    }
                    if (executors.containsKey(name)) {
                        LOGGER.warnf("mcpcalls tool name collision: '%s' is exposed by more than one MCP server — "
                                + "keeping the first and dropping the duplicate. Use 'toolsBlacklist' on the losing "
                                + "server to make the choice explicit.", name);
                        continue;
                    }
                    toolSpecs.add(spec);
                    executors.put(name, executor);
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " mcpcalls tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover mcpcalls tools from workflow", e);
        }

        return new McpToolProviderManager.McpToolsResult(toolSpecs, executors, failures);
    }
}
