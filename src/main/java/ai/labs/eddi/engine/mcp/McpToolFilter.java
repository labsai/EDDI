package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Filters MCP tool visibility to only expose the intended EDDI MCP tools.
 * <p>
 * Without this filter, the quarkus-mcp-server extension also picks up
 * {@code dev.langchain4j.agent.tool.Tool} annotations from EDDI's built-in
 * bot tools (calculator, datetime, websearch, etc.), which are meant ONLY
 * for internal bot pipeline use — not for external MCP clients.
 * <p>
 * This whitelist ensures only the 20 intended MCP tools are visible.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpToolFilter implements ToolFilter {

    /**
     * Whitelist of MCP tool names that should be exposed to external clients.
     * All other tools (built-in langchain4j bot tools) are hidden.
     */
    private static final Set<String> MCP_TOOLS = Set.of(
            // Conversation tools
            "list_bots",
            "list_bot_configs",
            "create_conversation",
            "talk_to_bot",
            "chat_with_bot",
            "read_conversation",
            "read_conversation_log",
            "list_conversations",
            "get_bot",
            // Admin tools
            "deploy_bot",
            "undeploy_bot",
            "get_deployment_status",
            "list_packages",
            "create_bot",
            "delete_bot",
            "update_bot",
            "read_package",
            "read_resource",
            // Setup tools
            "setup_bot",
            "create_api_bot"
    );

    @Override
    public boolean test(ToolInfo toolInfo, FilterContext filterContext) {
        return MCP_TOOLS.contains(toolInfo.name());
    }
}
