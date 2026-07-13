/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
 * Agent tools (calculator, datetime, websearch, etc.), which are meant ONLY for
 * internal Agent workflow use — not for external MCP clients.
 * <p>
 * This whitelist ensures only the intended MCP tools are visible.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpToolFilter implements ToolFilter {

    /**
     * Whitelist of MCP tool names that should be exposed to external clients. All
     * other tools (built-in langchain4j Agent tools) are hidden.
     */
    private static final Set<String> MCP_TOOLS = Set.of(
            // Conversation tools
            "list_agents", "list_agent_configs", "create_conversation", "talk_to_agent", "chat_with_agent", "read_conversation",
            "read_conversation_log", "list_conversations", "get_agent",
            // Admin tools
            "deploy_agent", "undeploy_agent", "get_deployment_status", "list_workflows", "create_agent", "delete_agent", "update_agent",
            "read_workflow", "read_resource",
            // Setup tools
            "setup_agent", "create_api_agent",
            // Resource CRUD + Cascade (Phase 8a.2)
            "update_resource", "create_resource", "delete_resource", "apply_agent_changes", "list_agent_resources",
            // Diagnostic tools (Phase 8a.2)
            "read_agent_logs", "read_audit_trail",
            // Discovery + Managed Agents (Phase 8a.3)
            "discover_agents", "chat_managed",
            // Agent Trigger CRUD (Phase 8a.3)
            "list_agent_triggers", "create_agent_trigger", "update_agent_trigger", "delete_agent_trigger",
            // Group Conversations (Phase 10)
            "describe_discussion_styles", "list_groups", "read_group", "create_group", "update_group", "delete_group", "discuss_with_group",
            "read_group_conversation", "list_group_conversations", "start_group_discussion", "delete_group_conversation",
            // Schedule Management
            "create_schedule", "list_schedules", "read_schedule", "delete_schedule", "fire_schedule_now", "retry_failed_schedule",
            // Channel Integrations
            "list_channel_integrations", "read_channel_integration", "create_channel_integration", "update_channel_integration",
            "delete_channel_integration",
            // HITL approval surface (McpHitlTools) — mirrors the REST HITL endpoints
            "list_pending_approvals", "get_approval_status", "resume_conversation", "cancel_conversation",
            "list_group_pending_approvals", "list_all_group_pending_approvals", "get_group_approval_status",
            "approve_group_phase", "cancel_group_discussion",
            // Persistent user memory (McpMemoryTools) — role-guarded (viewer read / admin
            // write) + per-user ownership
            "list_user_memories", "get_visible_memories", "search_user_memories", "get_memory_by_key",
            "upsert_user_memory", "delete_user_memory", "delete_all_user_memories", "count_user_memories",
            // GDPR/CCPA data-subject operations (McpGdprTools) — admin-only
            "delete_user_data", "export_user_data");

    @Override
    public boolean test(ToolInfo toolInfo, FilterContext filterContext) {
        return MCP_TOOLS.contains(toolInfo.name());
    }
}
