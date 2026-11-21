/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The role tier of every MCP tool, by tool name. This map is the only place a
 * tool's tier is written.
 * <p>
 * Each tool's role gate reads its tier from here
 * ({@code requireAnyRole(identity, authEnabled, McpToolTiers.of("deploy_agent"))}),
 * and {@link McpToolFilter} exposes exactly this map's key set. So a tool that
 * is not in this map is neither listed nor callable, and the tier a tool
 * enforces is the tier written here. Before this map existed, each tool method
 * held its tier as a literal, and the list of tools exposed to clients was a
 * separate set.
 * <p>
 * <b>Argument-dependent tools are listed under the union of their tiers.</b>
 * {@code read_agent_logs} admits {@link McpRoles#OBSERVE} for a read scoped to
 * one conversation and only {@link McpRoles#ADMIN_ONLY} for an unscoped one.
 * Its entry here is the union ({@code OBSERVE}, which contains the admin), and
 * the method applies the narrower gate itself.
 * <p>
 * {@code McpRoleTierParityTest} keeps an independent, hand-written record of
 * which tool belongs to which tier. It asserts that this map equals that
 * record, and that each tool, invoked once per role, admits exactly the roles
 * of its entry. Moving a tool to another tier therefore takes two edits, here
 * and in the test. That is deliberate.
 */
final class McpToolTiers {

    /** Tool name → the roles any one of which admits a caller. Unmodifiable. */
    static final Map<String, List<String>> TIERS = build();

    private McpToolTiers() {
    }

    /**
     * The tier of {@code tool}.
     *
     * @throws IllegalArgumentException
     *             if the tool has no entry. That is a programming error: a gate
     *             that names a tool missing from this map fails closed on every
     *             call, as an internal error rather than a {@code FORBIDDEN} tool
     *             error, since no role was refused. {@code McpRoleTierParityTest}
     *             ({@code eachGateNamesItsOwnTool}, the measured matrix) fails the
     *             build first.
     */
    static List<String> of(String tool) {
        List<String> tier = TIERS.get(tool);
        if (tier == null) {
            throw new IllegalArgumentException("No MCP role tier is defined for tool: " + tool);
        }
        return tier;
    }

    private static Map<String, List<String>> build() {
        Map<String, List<String>> tiers = new LinkedHashMap<>();

        // Conversing and reading what the caller owns (every tool is ownership-guarded)
        put(tiers, McpRoles.CONVERSE,
                // McpConversationTools
                "create_conversation", "talk_to_agent", "chat_with_agent", "read_conversation", "read_conversation_log",
                "list_conversations", "discover_agents", "chat_managed",
                // McpGroupTools: group conversations
                "describe_discussion_styles", "discuss_with_group", "read_group_conversation", "list_group_conversations",
                "start_group_discussion", "delete_group_conversation", "followup_with_member", "continue_group_discussion",
                "close_group_conversation",
                // McpMemoryTools: reads, for the caller's own userId
                "list_user_memories", "get_visible_memories", "search_user_memories", "get_memory_by_key", "count_user_memories");

        // Conversation diagnostics: admin plus the MCP observer role
        put(tiers, McpRoles.OBSERVE,
                // read_agent_logs: union tier. Unscoped reads are admin-only, gated inside the
                // method
                "read_agent_logs", "read_audit_trail");

        // Authoring: the REST configuration and administration tier
        put(tiers, McpRoles.AUTHOR,
                // McpConversationTools: config reads
                "list_agents", "list_agent_configs", "get_agent",
                // McpAdminTools: deployment, agents, workflows, resources, triggers, channels
                "deploy_agent", "undeploy_agent", "upgrade_conversations", "get_deployment_status", "list_workflows",
                "create_agent", "delete_agent", "update_agent", "read_workflow", "read_resource", "update_resource",
                "create_resource", "delete_resource", "apply_agent_changes", "list_agent_resources", "list_agent_triggers",
                "create_agent_trigger", "update_agent_trigger", "delete_agent_trigger", "list_channel_integrations",
                "read_channel_integration", "create_channel_integration", "update_channel_integration",
                "delete_channel_integration",
                // McpGroupTools: group configuration, templates and the team backlog
                "list_groups", "read_group", "create_group", "update_group", "delete_group", "add_team_task",
                "list_team_backlog", "list_group_templates", "create_group_from_template",
                // McpTeamHandoffTools: VIEW/EDIT on the group still applies per call
                "list_team_handoffs", "accept_team_handoff", "decline_team_handoff",
                // McpTeamCadenceTools: VIEW/EDIT on the group still applies per call
                "list_team_cadences", "create_team_cadence", "delete_team_cadence", "read_lesson_consolidation",
                "schedule_lesson_consolidation", "unschedule_lesson_consolidation",
                // McpAgentTestTools
                "run_agent_tests", "get_agent_test_run", "list_agent_test_runs", "cancel_agent_test_run",
                // McpRatingTools
                "list_conversation_ratings", "summarize_conversation_ratings", "count_ratings_needing_attention",
                "review_conversation_rating",
                // McpAlertTools
                "list_alert_events", "acknowledge_alert_event",
                // McpPricingTools: the catalog read only. Prices are a budget lever an
                // administrator changes in the Manager (update_model_prices is ADMIN_ONLY)
                "read_model_prices");

        // Operator-only
        put(tiers, McpRoles.ADMIN_ONLY,
                // McpVersionTools
                "get_eddi_version",
                // McpSetupTools
                "setup_agent", "create_api_agent",
                // McpAdminTools: schedules
                "create_schedule", "list_schedules", "read_schedule", "delete_schedule", "fire_schedule_now",
                "retry_failed_schedule",
                // McpMemoryTools: writes for an arbitrary userId
                "upsert_user_memory", "delete_user_memory", "delete_all_user_memories",
                // McpGdprTools
                "delete_user_data", "export_user_data",
                // McpTeamBudgetTools: every budget write
                "adjust_team_budget", "transfer_team_budget",
                // McpSpendControlTools: a stricter write applies, a looser one is only filed
                // for an administrator's approval
                "read_tenant_quota", "update_model_prices", "update_tenant_quota", "list_spend_control_changes",
                "withdraw_spend_control_change",
                // McpVaultTools: key names, never a value
                "list_vault_keys");

        // Published documentation: every EDDI role. The tool counterpart to the
        // eddi://docs/* resources, for agentic clients that consume tools/list and
        // never call resources/read
        put(tiers, McpRoles.DOCS, "list_docs", "read_docs");

        // Human-in-the-loop decisions and their reads (McpHitlTools). HitlAccessGuard
        // still runs per conversation after this gate
        put(tiers, McpRoles.HITL, "list_pending_approvals", "get_approval_status", "resume_conversation",
                "cancel_conversation", "list_group_pending_approvals", "list_all_group_pending_approvals",
                "get_group_approval_status", "approve_group_phase", "cancel_group_discussion", "submit_group_human_input");

        // HITL oversight reads (McpOversightTools): analytics, earned-autonomy states,
        // the sampled-audit count. No oversight write is ever a tool
        put(tiers, McpRoles.OVERSIGHT, "summarize_approvals", "list_autonomy_recommendations", "get_autonomy_state",
                "count_pending_audit_samples");

        // Team budget reads (McpTeamBudgetTools)
        put(tiers, McpRoles.BUDGET_READ, "read_team_budget", "list_team_budgets");

        return Collections.unmodifiableMap(tiers);
    }

    /**
     * Package-visible for {@code McpRoleTierParityTest}, which pins the duplicate
     * guard.
     */
    static void put(Map<String, List<String>> tiers, List<String> tier, String... tools) {
        for (String tool : tools) {
            if (tiers.putIfAbsent(tool, tier) != null) {
                throw new IllegalStateException("MCP tool listed in two tiers: " + tool);
            }
        }
    }
}
