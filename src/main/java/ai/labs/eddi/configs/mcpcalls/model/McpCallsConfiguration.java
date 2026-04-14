package ai.labs.eddi.configs.mcpcalls.model;

import java.util.List;

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

    /** Transport type: "http" (default, StreamableHTTP) or "sse" */
    private String transport = "http";

    /**
     * Optional API key or vault reference (e.g., "${eddivault:my-api-key}").
     * Resolved at connection time via {@code SecretResolver}.
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
