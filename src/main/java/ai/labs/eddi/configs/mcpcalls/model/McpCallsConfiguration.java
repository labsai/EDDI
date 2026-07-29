/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration for connecting to an external MCP server and optionally
 * defining deterministic (action-triggered) MCP tool calls.
 *
 * <p>
 * This is a first-class versioned workflow extension — the MCP equivalent of
 * {@link ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration}.
 * </p>
 *
 * <h3>Dual-mode operation:</h3>
 * <ul>
 * <li><strong>Pipeline mode</strong>: Behavior rules emit actions that trigger
 * specific {@link McpCall} entries deterministically (no LLM involved).</li>
 * <li><strong>Agent mode</strong>: The LLM agent auto-discovers allowed tools
 * from the workflow via {@code AgentOrchestrator.discoverMcpCallTools()} and
 * calls them reactively.</li>
 * </ul>
 */
public class McpCallsConfiguration {

    /** URL of the MCP server (required). Example: "http://localhost:7070/mcp" */
    private String mcpServerUrl;

    /** Optional display name for this MCP server connection */
    private String name;

    /**
     * Transport type. Only StreamableHTTP is implemented, so the accepted tokens
     * are {@link #SUPPORTED_TRANSPORTS} — {@code "http"} (the default),
     * {@code "https"}, {@code "streamable-http"} or {@code "streamablehttp"}.
     * {@code "sse"} and {@code "stdio"} were once documented here but never
     * implemented; a stored config carrying one still loads (with a logged error)
     * but its MCP server will not connect.
     */
    private String transport = "http";

    /**
     * Optional API key or vault reference (e.g., "${vault:my-api-key}"). Resolved
     * at connection time via {@code SecretResolver}.
     */
    private String apiKey;

    /** Timeout for MCP operations in milliseconds (default: 30000) */
    private Long timeoutMs = 30000L;

    /**
     * If non-empty, only these tools are available (both pipeline and agent modes).
     * Tool names as returned by the MCP server's tools/list.
     */
    private List<String> toolsWhitelist;

    /**
     * Tools to exclude. Applied after whitelist. Useful when you want "all tools
     * except X".
     */
    private List<String> toolsBlacklist;

    /**
     * Optional list of deterministic MCP tool call bindings. Each entry maps
     * behavior-rule actions to a specific MCP tool invocation. If empty, this
     * config is agent-mode only.
     */
    private List<McpCall> mcpCalls;

    /**
     * Transport tokens this engine actually implements. Anything else used to be
     * accepted, propagated, logged, and then silently served over StreamableHTTP
     * (finding I3) — it is now rejected instead of ignored.
     */
    public static final Set<String> SUPPORTED_TRANSPORTS = Set.of("http", "https", "streamable-http", "streamablehttp");

    /**
     * Validate this configuration.
     * <p>
     * This is the <em>write-boundary</em> validator: it is meant for REST
     * create/update and import, where a rejection is recoverable by the author.
     * Loading an already-stored config is deliberately lenient — {@code
     * McpCallsTask.configure()} only logs what this method reports, so a config
     * that predates these rules cannot brick an entire agent (the MCP step itself
     * still fails closed at connection time).
     * <p>
     * Two silently-ignored settings are rejected here rather than accepted and
     * dropped:
     * <ul>
     * <li>a {@code transport} the engine does not implement (finding I3)</li>
     * <li>a {@code mcpServerUrl} with a scheme other than http/https — the MCP
     * client only speaks StreamableHTTP, and a non-http scheme is a
     * misconfiguration worth surfacing (finding A10)</li>
     * </ul>
     *
     * @throws IllegalArgumentException
     *             with an actionable message when the configuration cannot be
     *             honoured as written
     */
    public void validate() {
        if (mcpServerUrl == null || mcpServerUrl.isBlank()) {
            throw new IllegalArgumentException("mcpServerUrl is required for an MCP calls configuration");
        }
        String lowerUrl = mcpServerUrl.trim().toLowerCase(Locale.ROOT);
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new IllegalArgumentException("mcpServerUrl must use http or https (the MCP client only speaks StreamableHTTP): " + mcpServerUrl);
        }
        if (transport != null && !transport.isBlank() && !SUPPORTED_TRANSPORTS.contains(transport.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported MCP transport '" + transport + "'. Only StreamableHTTP is implemented — "
                    + "use \"http\" (supported: " + SUPPORTED_TRANSPORTS + ").");
        }
    }

    // --- Getters and Setters ---

    public String getMcpServerUrl() {
        return mcpServerUrl;
    }

    public void setMcpServerUrl(String mcpServerUrl) {
        this.mcpServerUrl = mcpServerUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public List<String> getToolsWhitelist() {
        return toolsWhitelist;
    }

    public void setToolsWhitelist(List<String> toolsWhitelist) {
        this.toolsWhitelist = toolsWhitelist;
    }

    public List<String> getToolsBlacklist() {
        return toolsBlacklist;
    }

    public void setToolsBlacklist(List<String> toolsBlacklist) {
        this.toolsBlacklist = toolsBlacklist;
    }

    public List<McpCall> getMcpCalls() {
        return mcpCalls;
    }

    public void setMcpCalls(List<McpCall> mcpCalls) {
        this.mcpCalls = mcpCalls;
    }
}
