/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final GlobalVariableResolver globalVariableResolver;
    private final SecretResolver secretResolver;
    private final CallerIdentityResolver callerIdentityResolver;
    private final CallerIdentityContext callerIdentityContext;

    /**
     * Cache of active MCP clients, keyed by server URL. Connections are reused
     * across conversation turns to avoid reconnect overhead.
     */
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    // ----- Circuit breaker state -----
    /** Maximum failures within the window before the circuit opens. */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** Time window for counting failures (seconds). */
    private static final long CIRCUIT_WINDOW_SECONDS = 60;
    /** Recent failure timestamps per server URL. */
    private final Map<String, List<Instant>> failureTimestamps = new ConcurrentHashMap<>();

    @Inject
    public McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            CallerIdentityResolver callerIdentityResolver, CallerIdentityContext callerIdentityContext) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.callerIdentityResolver = callerIdentityResolver;
        this.callerIdentityContext = callerIdentityContext;
    }

    /**
     * Result of discovering tools from MCP servers.
     *
     * @param toolSpecs
     *            list of tool specifications discovered
     * @param executors
     *            map of tool name → executor for each discovered tool
     */
    public record McpToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
    }

    /**
     * Connect to the configured MCP servers and discover their tools.
     * <p>
     * Returns the combined tool specifications and executors from all servers.
     * Failed connections log a warning but don't prevent other servers from being
     * used.
     *
     * @param mcpServers
     *            list of MCP server configurations
     * @return combined tools from all reachable servers
     */
    public McpToolsResult discoverTools(List<McpServerConfig> mcpServers) {
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

            // Circuit breaker: skip servers that failed too often recently
            if (isCircuitOpen(serverConfig.getUrl())) {
                String serverName = serverConfig.getName() != null ? serverConfig.getName() : serverConfig.getUrl();
                LOGGER.warnf("Circuit breaker OPEN for MCP server '%s' — skipping (>=%d failures in last %ds)",
                        sanitize(serverName), CIRCUIT_FAILURE_THRESHOLD, CIRCUIT_WINDOW_SECONDS);
                continue;
            }

            try {
                McpClient client = getOrCreateClient(serverConfig);
                String serverName = serverConfig.getName() != null ? serverConfig.getName() : serverConfig.getUrl();

                // Use McpToolProvider to discover tools from this server
                McpToolProvider toolProvider = McpToolProvider.builder().mcpClients(List.of(client)).build();

                // Discover tools — McpToolProvider returns ToolProviderResult
                ToolProviderResult result = toolProvider.provideTools(null);

                if (result != null && result.tools() != null) {
                    for (var toolEntry : result.tools().entrySet()) {
                        ToolSpecification spec = toolEntry.getKey();
                        ToolExecutor executor = toolEntry.getValue();

                        allSpecs.add(spec);
                        allExecutors.put(spec.name(), executor);
                    }
                    LOGGER.infof("Discovered %d tools from MCP server '%s'", result.tools().size(), sanitize(serverName));
                }

                // Success — clear failure history for this server
                recordSuccess(serverConfig.getUrl());

            } catch (Exception e) {
                String serverName = serverConfig.getName() != null ? serverConfig.getName() : serverConfig.getUrl();
                LOGGER.warnf(e, "Failed to connect to MCP server '%s': %s", sanitize(serverName), e.getMessage());
                recordFailure(serverConfig.getUrl());
            }
        }

        return new McpToolsResult(allSpecs, allExecutors);
    }

    /**
     * Cache key for a client: the URL plus a digest of the configured credential.
     * <p>
     * Keying on the URL alone let two agents configured against the same server
     * with <em>different</em> credentials share whichever client was constructed
     * first — the second silently borrowed the first's authorization. That is a
     * privilege boundary, not a caching detail.
     * <p>
     * The credential is digested rather than used directly so a literal key never
     * becomes a map key that could reach a heap dump or a log line. The value is
     * taken <em>unresolved</em>, so two configs sharing one vault reference still
     * share a client — the distinction that matters is which credential a config
     * names, not what it resolves to.
     * <p>
     * This does not multiply clients per user: a {@code ${caller:...}} config
     * yields one client whose header supplier reads the caller per request, so
     * every user of that config shares it.
     */
    private static String cacheKey(McpServerConfig config) {
        String apiKey = config.getApiKey();
        if (isNullOrEmpty(apiKey)) {
            return config.getUrl() + "|anonymous";
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return config.getUrl() + "|" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is truly absent, fall back to
            // isolating by identity so distinct credentials still cannot share.
            return config.getUrl() + "|" + System.identityHashCode(apiKey);
        }
    }

    /**
     * Get or create an MCP client for the given server configuration. Clients are
     * cached per URL and credential for connection reuse.
     */
    private McpClient getOrCreateClient(McpServerConfig config) {
        return clientCache.computeIfAbsent(cacheKey(config), key -> {
            String url = config.getUrl();
            LOGGER.infof("Creating MCP client for '%s' (%s transport)", sanitize(config.getName() != null ? config.getName() : url),
                    sanitize(config.getTransport()));

            Duration timeout = Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000L);

            McpTransport transport = createTransport(config, timeout);

            return new DefaultMcpClient.Builder().transport(transport).clientName("eddi-mcp-client").build();
        });
    }

    /**
     * Create the appropriate MCP transport based on configuration.
     */
    private McpTransport createTransport(McpServerConfig config, Duration timeout) {
        // Resolve the static half once: a global variable or vault reference does not
        // change between requests, and the handshake needs a credential before any
        // conversation exists.
        String apiKey = config.getApiKey();
        boolean callerBound = CallerIdentityResolver.containsReference(apiKey);
        if (!isNullOrEmpty(apiKey) && !callerBound) {
            apiKey = globalVariableResolver.resolveValue(apiKey);
            apiKey = secretResolver.resolveValue(apiKey);
        }

        // StreamableHttpMcpTransport (recommended, replaces deprecated
        // HttpMcpTransport)
        var transportBuilder = StreamableHttpMcpTransport.builder().url(config.getUrl()).timeout(timeout);

        if (!isNullOrEmpty(apiKey)) {
            final String configuredKey = apiKey;
            // Per request, not per client. The Map overload would freeze this value
            // into the cached client, which is why a ${caller:...} reference has to be
            // resolved here: the client is shared across conversations, the caller is
            // not. Applies to every POST the transport makes.
            transportBuilder.customHeaders(callContext -> authorizationHeader(configuredKey, config, callerBound, callContext));
        }

        return transportBuilder.build();
    }

    /**
     * Build the {@code Authorization} header for a single MCP request.
     * <p>
     * A static key behaves exactly as before. A {@code ${caller:...}} reference
     * resolves against the caller bound to this thread, so the tool call reaches
     * the MCP server as the person chatting rather than as a standing service
     * principal — the same guarantee {@code ApiCallExecutor} gives apicall headers,
     * including the same-origin restriction and failing closed rather than sending
     * a placeholder.
     * <p>
     * Two request kinds arrive here with no caller: the {@code initialize}
     * handshake and {@code tools/list}, which langchain4j performs with a null
     * invocation context while the client is being constructed. A caller-bound
     * config cannot satisfy them, so they are sent unauthenticated and the server
     * decides — which is correct, because discovery must not run with one user's
     * credential and then be reused for everyone else's calls.
     */
    private Map<String, String> authorizationHeader(String configuredKey, McpServerConfig config, boolean callerBound,
                                                    McpCallContext callContext) {
        if (!callerBound) {
            return Map.of("Authorization", "Bearer " + configuredKey);
        }
        if (callerIdentityContext.current() == null) {
            // Discovery, a scheduled turn, or a retry that landed on an HTTP callback
            // thread where the binding does not exist. Sending the placeholder text
            // would be nonsense and sending nothing is honest; the MCP server refuses
            // if it requires authentication.
            LOGGER.debugf("No caller bound for MCP request to '%s' — sending it unauthenticated", sanitize(config.getUrl()));
            return Map.of();
        }
        String resolved = callerIdentityResolver.resolveValue(configuredKey, URI.create(config.getUrl()));
        return Map.of("Authorization", resolved);
    }

    /**
     * Close every cached client for a server URL and remove them from the cache.
     * <p>
     * A URL can now hold more than one client — one per configured credential — so
     * this closes all of them. Callers identify a server, not a credential.
     */
    void closeClient(String url) {
        if (url == null) {
            return;
        }
        String prefix = url + "|";
        for (var key : List.copyOf(clientCache.keySet())) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            McpClient client = clientCache.remove(key);
            if (client == null) {
                continue;
            }
            try {
                client.close();
                LOGGER.infof("Closed MCP client for '%s'", sanitize(url));
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", sanitize(url));
            }
        }
    }

    /**
     * Close all cached MCP client connections. Called on application shutdown.
     */
    @PreDestroy
    void shutdown() {
        LOGGER.infof("Shutting down %d MCP client connection(s)", clientCache.size());
        for (var entry : clientCache.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warnf(e, "Error closing MCP client for '%s'", sanitize(entry.getKey()));
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

    // ========================== Circuit Breaker ==========================

    /**
     * Check whether the circuit breaker is open for a given server URL. The circuit
     * opens when the server has failed {@value #CIRCUIT_FAILURE_THRESHOLD} or more
     * times within the last {@value #CIRCUIT_WINDOW_SECONDS} seconds.
     */
    boolean isCircuitOpen(String url) {
        List<Instant> failures = failureTimestamps.get(url);
        if (failures == null) {
            return false;
        }
        Instant cutoff = Instant.now().minusSeconds(CIRCUIT_WINDOW_SECONDS);
        long recentFailures = failures.stream().filter(t -> t.isAfter(cutoff)).count();
        return recentFailures >= CIRCUIT_FAILURE_THRESHOLD;
    }

    /**
     * Record a connection failure for circuit breaker tracking. Evicts timestamps
     * older than the window to prevent unbounded growth.
     */
    private void recordFailure(String url) {
        List<Instant> failures = failureTimestamps.computeIfAbsent(url, k -> new CopyOnWriteArrayList<>());
        failures.add(Instant.now());
        // Evict old entries outside the window
        Instant cutoff = Instant.now().minusSeconds(CIRCUIT_WINDOW_SECONDS);
        failures.removeIf(t -> t.isBefore(cutoff));
    }

    /**
     * Record a successful connection — clears the failure history so the circuit
     * breaker resets.
     */
    private void recordSuccess(String url) {
        failureTimestamps.remove(url);
    }
}
