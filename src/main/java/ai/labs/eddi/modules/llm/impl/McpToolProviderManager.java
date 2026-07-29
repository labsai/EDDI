/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

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
    private final boolean ssrfProtectionEnabled;
    private final int maxDescriptionChars;
    private final long toolCacheTtlMillis;

    /**
     * Cache of active MCP clients, keyed by server URL. Connections are reused
     * across conversation turns to avoid reconnect overhead.
     */
    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Cache of discovered tool specs/executors per server URL. Finding F12: without
     * this, every conversation turn issued a live {@code tools/list} RPC to every
     * configured MCP server (only the connection was cached). Mirrors the 5-minute
     * Agent Card cache in {@link A2AToolProviderManager}.
     */
    private final Map<String, CachedTools> toolCache = new ConcurrentHashMap<>();

    /** Cached discovery result for one server, with the time it was captured. */
    record CachedTools(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors, long timestamp) {
    }

    /** Default TTL for the discovered-tools cache (5 minutes), as for A2A cards. */
    static final long DEFAULT_TOOL_CACHE_TTL_MILLIS = 300_000L;

    /** Default cap for a remote tool description before it reaches the model. */
    static final int DEFAULT_MAX_DESCRIPTION_CHARS = 1024;

    /**
     * Directive-shaped content that a remote MCP server must not be able to inject
     * into the model's tool definitions (finding F16). Matched case-insensitively
     * and replaced with {@code [redacted]} — the tool stays usable, the instruction
     * does not survive.
     */
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?"
                    + "|disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions?"
                    + "|you\\s+are\\s+now\\s+"
                    + "|system\\s*(prompt|message)\\s*[:=]"
                    + "|</?(system|assistant|user)>"
                    + "|\\[/?(INST|SYSTEM)\\]"
                    + "|<\\|im_(start|end)\\|>)");

    // ----- Circuit breaker state -----
    /** Maximum failures within the window before the circuit opens. */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** Time window for counting failures (seconds). */
    private static final long CIRCUIT_WINDOW_SECONDS = 60;
    /** Recent failure timestamps per server URL. */
    private final Map<String, List<Instant>> failureTimestamps = new ConcurrentHashMap<>();

    @Inject
    public McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver,
            @ConfigProperty(name = "eddi.security.ssrf-protection.enabled", defaultValue = "false") boolean ssrfProtectionEnabled,
            @ConfigProperty(name = "eddi.mcp.tool-description.max-chars", defaultValue = "1024") int maxDescriptionChars,
            @ConfigProperty(name = "eddi.mcp.tool-cache.ttl-ms", defaultValue = "300000") long toolCacheTtlMillis) {
        this.globalVariableResolver = globalVariableResolver;
        this.secretResolver = secretResolver;
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.maxDescriptionChars = maxDescriptionChars > 0 ? maxDescriptionChars : DEFAULT_MAX_DESCRIPTION_CHARS;
        this.toolCacheTtlMillis = toolCacheTtlMillis >= 0 ? toolCacheTtlMillis : DEFAULT_TOOL_CACHE_TTL_MILLIS;
    }

    /**
     * Test/back-compat constructor using the shipped defaults (SSRF validation off,
     * 1024-char description cap, 5-minute tool cache).
     */
    McpToolProviderManager(GlobalVariableResolver globalVariableResolver, SecretResolver secretResolver) {
        this(globalVariableResolver, secretResolver, false, DEFAULT_MAX_DESCRIPTION_CHARS, DEFAULT_TOOL_CACHE_TTL_MILLIS);
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

            String serverName = serverConfig.getName() != null ? serverConfig.getName() : serverConfig.getUrl();

            // Circuit breaker: skip servers that failed too often recently
            if (isCircuitOpen(serverConfig.getUrl())) {
                LOGGER.warnf("Circuit breaker OPEN for MCP server '%s' — skipping (>=%d failures in last %ds)",
                        sanitize(serverName), CIRCUIT_FAILURE_THRESHOLD, CIRCUIT_WINDOW_SECONDS);
                continue;
            }

            // A misconfigured transport is a CONFIGURATION error, not a connectivity
            // failure. Left inside the catch below it was logged as "Failed to connect",
            // counted toward the circuit breaker, and would eventually open it — three
            // wrong signals for a problem no retry can fix.
            try {
                validateTransport(serverConfig.getTransport());
            } catch (IllegalArgumentException e) {
                LOGGER.errorf("MCP server '%s' is misconfigured and was skipped: %s (configuration error — not counted "
                        + "toward the circuit breaker)", sanitize(serverName), e.getMessage());
                continue;
            }
            warnOnceAboutDeprecatedTransport(serverConfig.getUrl(), serverConfig.getTransport(), serverName);

            try {
                // F12: serve the tool list from the TTL cache when it is still fresh —
                // a live tools/list RPC per server per turn was pure overhead.
                CachedTools cached = toolCache.get(serverConfig.getUrl());
                if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < toolCacheTtlMillis) {
                    allSpecs.addAll(cached.toolSpecs());
                    allExecutors.putAll(cached.executors());
                    LOGGER.debugf("Serving %d cached tools for MCP server '%s'", cached.toolSpecs().size(), sanitize(serverName));
                    continue;
                }

                McpClient client = getOrCreateClient(serverConfig);

                // Use McpToolProvider to discover tools from this server
                McpToolProvider toolProvider = McpToolProvider.builder().mcpClients(List.of(client)).build();

                // Discover tools — McpToolProvider returns ToolProviderResult
                ToolProviderResult result = toolProvider.provideTools(null);

                List<ToolSpecification> serverSpecs = new ArrayList<>();
                Map<String, ToolExecutor> serverExecutors = new HashMap<>();
                if (result != null && result.tools() != null) {
                    for (var toolEntry : result.tools().entrySet()) {
                        // F16: the description is authored by the REMOTE server and lands
                        // verbatim in the model's tool definitions — cap and sanitize it
                        // before it becomes prompt content.
                        ToolSpecification spec = governDescription(toolEntry.getKey(), serverName);
                        ToolExecutor executor = toolEntry.getValue();

                        // F15: within one server, a duplicate tool name would silently
                        // overwrite the earlier executor while both specs reach the model.
                        if (serverExecutors.containsKey(spec.name())) {
                            LOGGER.warnf("MCP server '%s' advertises tool '%s' more than once — keeping the first, dropping the duplicate",
                                    sanitize(serverName), sanitize(spec.name()));
                            continue;
                        }

                        serverSpecs.add(spec);
                        serverExecutors.put(spec.name(), executor);
                    }
                    LOGGER.infof("Discovered %d tools from MCP server '%s'", serverSpecs.size(), sanitize(serverName));
                }

                toolCache.put(serverConfig.getUrl(), new CachedTools(List.copyOf(serverSpecs), Map.copyOf(serverExecutors),
                        System.currentTimeMillis()));
                allSpecs.addAll(serverSpecs);
                allExecutors.putAll(serverExecutors);

                // Success — clear failure history for this server
                recordSuccess(serverConfig.getUrl());

            } catch (Exception e) {
                LOGGER.warnf(e, "Failed to connect to MCP server '%s': %s", sanitize(serverName), e.getMessage());
                recordFailure(serverConfig.getUrl());
            }
        }

        return new McpToolsResult(allSpecs, allExecutors);
    }

    /**
     * Get or create an MCP client for the given server configuration. Clients are
     * cached by URL for connection reuse.
     */
    private McpClient getOrCreateClient(McpServerConfig config) {
        return clientCache.computeIfAbsent(config.getUrl(), url -> {
            LOGGER.infof("Creating MCP client for '%s' (%s transport)", sanitize(config.getName() != null ? config.getName() : url),
                    sanitize(config.getTransport()));

            Duration timeout = Duration.ofMillis(config.getTimeoutMs() != null ? config.getTimeoutMs() : 30000L);

            McpTransport transport = createTransport(config, timeout);

            return new DefaultMcpClient.Builder().transport(transport).clientName("eddi-mcp-client").build();
        });
    }

    /**
     * Supported MCP transport tokens. Only StreamableHTTP is implemented — every
     * other value (e.g. {@code "stdio"}) used to be accepted, logged, and then
     * silently served over StreamableHTTP anyway (finding I3).
     */
    static final Set<String> SUPPORTED_TRANSPORTS = Set.of("http", "https", "streamable-http", "streamablehttp");

    /**
     * Transport tokens that stored configs still carry and that this manager
     * accepted before finding I3 — served, then as now, over StreamableHTTP.
     * {@code "sse"} was the documented alternative in
     * {@code LlmConfiguration.McpServerConfig}, so agents written against that doc
     * are in the wild. Hard-rejecting it strips EVERY tool from such an agent, and
     * in the agent path the rejection is swallowed by the discovery catch — a
     * silent capability loss, not a loud failure. Accepted with a one-time
     * deprecation warning instead.
     */
    static final Set<String> DEPRECATED_TRANSPORT_ALIASES = Set.of("sse");

    /**
     * Server URLs already warned about a deprecated transport. Discovery runs per
     * turn, so without this the warning would repeat on every turn of every
     * conversation.
     */
    private final Set<String> deprecatedTransportWarned = ConcurrentHashMap.newKeySet();

    /**
     * Validate an MCP server URL before it is used for an outbound request.
     * <p>
     * Finding A10: the MCP client performed no URL validation at all, while
     * {@code GET /mcpcallsstore/mcpcalls/discover-tools?url=...} echoes the
     * response body back to the caller — a full SSRF read primitive. Mirrors
     * {@link A2AToolProviderManager}: private/loopback/link-local/cloud-metadata
     * targets are rejected when {@code eddi.security.ssrf-protection.enabled} is
     * on, and a non-http(s) scheme or unparseable URL is always rejected.
     *
     * @throws IllegalArgumentException
     *             if the URL is unusable or targets an internal address
     */
    void validateServerUrl(String url) {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("MCP server URL must not be empty");
        }
        if (ssrfProtectionEnabled) {
            UrlValidationUtils.validateUrl(url);
            return;
        }
        // Even with SSRF protection off, a non-http(s) scheme is never a valid MCP
        // StreamableHTTP endpoint — reject it rather than handing it to the builder.
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("MCP server URL must use http or https: " + url);
        }
    }

    /**
     * Reject a transport token that this manager does not actually implement
     * (finding I3). {@code null}/blank means "use the default StreamableHTTP", and
     * a {@link #DEPRECATED_TRANSPORT_ALIASES deprecated alias} is accepted so
     * stored configs keep working.
     */
    static void validateTransport(String transport) {
        if (isNullOrEmpty(transport)) {
            return;
        }
        String normalised = transport.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_TRANSPORTS.contains(normalised) || DEPRECATED_TRANSPORT_ALIASES.contains(normalised)) {
            return;
        }
        throw new IllegalArgumentException("Unsupported MCP transport '" + transport + "'. Only StreamableHTTP is implemented — use "
                + "\"http\" (supported: " + SUPPORTED_TRANSPORTS + ").");
    }

    /** Whether {@code transport} is honoured only for backward compatibility. */
    static boolean isDeprecatedTransport(String transport) {
        return !isNullOrEmpty(transport) && DEPRECATED_TRANSPORT_ALIASES.contains(transport.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * One WARN per server URL — discovery runs every turn, so warning per call
     * would repeat forever.
     */
    private void warnOnceAboutDeprecatedTransport(String url, String transport, String serverName) {
        if (isDeprecatedTransport(transport) && deprecatedTransportWarned.add(url)) {
            LOGGER.warnf("MCP server '%s' declares transport '%s', which EDDI does not implement — the connection is served over "
                    + "StreamableHTTP, as it always has been. Update the config to \"http\"; the alias will be removed.",
                    sanitize(serverName), sanitize(transport));
        }
    }

    /**
     * Cap and sanitize a remote tool description before it becomes part of the
     * model's tool definitions (finding F16). Whitelisting operates on tool NAMES,
     * so an approved tool whose description later turns into an instruction is
     * otherwise ungoverned.
     */
    ToolSpecification governDescription(ToolSpecification spec, String serverName) {
        String description = spec.description();
        if (description == null || description.isBlank()) {
            return spec;
        }

        String sanitized = DIRECTIVE_PATTERN.matcher(description).replaceAll("[redacted]");
        if (!sanitized.equals(description)) {
            LOGGER.warnf("MCP tool '%s' from server '%s' had directive-shaped content in its description — redacted before prompting",
                    sanitize(spec.name()), sanitize(serverName));
        }

        if (sanitized.length() > maxDescriptionChars) {
            LOGGER.warnf("MCP tool '%s' from server '%s' description is %d chars — truncated to %d",
                    sanitize(spec.name()), sanitize(serverName), sanitized.length(), maxDescriptionChars);
            sanitized = sanitized.substring(0, maxDescriptionChars) + " […truncated]";
        }

        if (sanitized.equals(description)) {
            return spec;
        }
        return ToolSpecification.builder().name(spec.name()).description(sanitized).parameters(spec.parameters()).build();
    }

    /**
     * Create the appropriate MCP transport based on configuration.
     */
    private McpTransport createTransport(McpServerConfig config, Duration timeout) {
        // A10: never open a connection to an unvalidated, caller-supplied URL.
        validateServerUrl(config.getUrl());
        // I3: fail loudly on a transport this manager does not implement instead of
        // logging it and quietly using StreamableHTTP.
        validateTransport(config.getTransport());

        // Resolve API key if it's a global variable or vault reference
        String apiKey = config.getApiKey();
        if (!isNullOrEmpty(apiKey)) {
            apiKey = globalVariableResolver.resolveValue(apiKey);
            apiKey = secretResolver.resolveValue(apiKey);
        }

        // StreamableHttpMcpTransport (recommended, replaces deprecated
        // HttpMcpTransport)
        var transportBuilder = StreamableHttpMcpTransport.builder().url(config.getUrl()).timeout(timeout);

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
        // The cached executors are bound to this client — drop them with it.
        toolCache.remove(url);
        McpClient client = clientCache.remove(url);
        if (client != null) {
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
        toolCache.clear();
    }

    /**
     * Get the number of active cached connections (for monitoring/testing).
     */
    int getActiveConnectionCount() {
        return clientCache.size();
    }

    /**
     * Number of servers whose tool list is currently cached (monitoring/testing).
     */
    int getCachedToolServerCount() {
        return toolCache.size();
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
