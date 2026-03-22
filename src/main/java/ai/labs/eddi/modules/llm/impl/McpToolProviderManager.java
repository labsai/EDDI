package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Manages MCP client connections for agents that consume external MCP servers
 * as tool providers.
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Create and cache {@link McpClient} connections by URL</li>
 * <li>Discover tools from remote MCP servers via {@link McpToolProvider}</li>
 * <li>Return tool specifications and executors for the
 * {@link AgentOrchestrator}</li>
 * <li>Resolve vault references in API keys via {@link SecretResolver}</li>
 * <li>Graceful cleanup on shutdown via {@code @PreDestroy}</li>
 * </ul>
 */
@ApplicationScoped
public class McpToolProviderManager {

    private static final Logger LOGGER = Logger.getLogger(McpToolProviderManager.class);

    private final SecretResolver secretResolver;

    /**
     * Cache of active MCP clients, keyed by server URL.
     * Connections are reused across conversation turns to avoid reconnect overhead.
     */
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    @Inject
    public McpToolProviderManager(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * Result of discovering tools from MCP servers.
     *
     * @param toolSpecs list of tool specifications discovered
     * @param executors map of tool name → executor for each discovered tool
     */
    record McpToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
    }

    /**
     * Connect to the configured MCP servers and discover their tools.
     * <p>
     * Returns the combined tool specifications and executors from all servers.
     * Failed connections log a warning but don't prevent other servers from being
     * used.
     *
     * @param mcpServers list of MCP server configurations
     * @return combined tools from all reachable servers
     */
    McpToolsResult discoverTools(List<McpServerConfig> mcpServers) {
        if (mcpServers == null || mcpServers.isEmpty()) {
            return new McpToolsResult(List.of(), Map.of());
        }

        List<ToolSpecification> allSpecs = new ArrayList<>();
        Map<String, ToolExecutor> allExecutors = new HashMap<>();

        for (McpServerConfig serverConfig : mcpServers) {
            if (isNullOrEmpty(serverConfig.getUrl())) {
                LOGGER.warn("Skipping MCP server with empty URL");
                continue;
            }

            try {
                McpClient client = getOrCreateClient(serverConfig);
                String serverName = serverConfig.getName() != null
                        ? serverConfig.getName()
                        : serverConfig.getUrl();

                // Use McpToolProvider to discover tools from this server
                McpToolProvider toolProvider = McpToolProvider.builder()
                        .mcpClients(List.of(client))
                        .build();

                // Discover tools — McpToolProvider returns ToolProviderResult
                ToolProviderResult result = toolProvider.provideTools(null);

                if (result != null && result.tools() != null) {
                    for (var toolEntry : result.tools().entrySet()) {
                        ToolSpecification spec = toolEntry.getKey();
                        ToolExecutor executor = toolEntry.getValue();

                        allSpecs.add(spec);
                        allExecutors.put(spec.name(), executor);
                    }
                    LOGGER.infof("Discovered %d tools from MCP server '%s'",
                            result.tools().size(), serverName);
                }

            } catch (Exception e) {
                String serverName = serverConfig.getName() != null
                        ? serverConfig.getName()
                        : serverConfig.getUrl();
                LOGGER.warnf(e, "Failed to connect to MCP server '%s': %s",
                        serverName, e.getMessage());
            }
        }

        return new McpToolsResult(allSpecs, allExecutors);
    }

    /**
     * Get or create an MCP client for the given server configuration.
     * Clients are cached by URL for connection reuse.
     */
    private McpClient getOrCreateClient(McpServerConfig config) {
        return clientCache.computeIfAbsent(config.getUrl(), url -> {
            LOGGER.infof("Creating MCP client for '%s' (%s transport)",
                    config.getName() != null ? config.getName() : url,
                    config.getTransport());

            Duration timeout = Duration.ofMillis(
                    config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000L);

            McpTransport transport = createTransport(config, timeout);

            return new DefaultMcpClient.Builder()
                    .transport(transport)
                    .clientName("eddi-mcp-client")
                    .build();
        });
    }

    /**
     * Create the appropriate MCP transport based on configuration.
     */
    private McpTransport createTransport(McpServerConfig config, Duration timeout) {
        // Resolve API key if it's a vault reference
        String apiKey = config.getApiKey();
        if (!isNullOrEmpty(apiKey)) {
            apiKey = secretResolver.resolveValue(apiKey);
        }

        // StreamableHttpMcpTransport (recommended, replaces deprecated
        // HttpMcpTransport)
        var transportBuilder = StreamableHttpMcpTransport.builder()
                .url(config.getUrl())
                .timeout(timeout);

        // Add API key as Authorization header if configured
        if (!isNullOrEmpty(apiKey)) {
            final String resolvedKey = apiKey;
            transportBuilder.customHeaders(Map.of("Authorization", "Bearer " + resolvedKey));
        }

        return transportBuilder.build();
    }

    /**
     * Close a specific MCP client connection and remove it from the cache.
     */
    void closeClient(String url) {
        McpClient client = clientCache.remove(url);
        if (client != null) {
            try {
                client.close();
                LOGGER.infof("Closed MCP client for '%s'", url);
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", url);
            }
        }
    }

    /**
     * Close all cached MCP client connections.
     * Called on application shutdown.
     */
    @PreDestroy
    void shutdown() {
        LOGGER.infof("Shutting down %d MCP client connection(s)", clientCache.size());
        for (var entry : clientCache.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", entry.getKey());
            }
        }
        clientCache.clear();
    }

    /**
     * Get the number of active cached connections (for monitoring/testing).
     */
    int getActiveConnectionCount() {
        return clientCache.size();
    }
}
