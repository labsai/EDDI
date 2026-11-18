/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MCP tool is accounted for in the audit trail. A tool that changes state
 * either carries {@link McpAudited} (the in-process mutators, which the REST
 * {@code AdminActionAuditFilter} never sees), or is named below as one the
 * trail already covers by another route. A new tool that is on none of the
 * lists fails here — the decision "does this need an admin-action entry" is
 * made when the tool is added, not found in a review.
 * <p>
 * Adding a name to {@link #READ_ONLY} is the statement that the tool changes
 * nothing; the verb check below holds that statement to its name.
 */
@DisplayName("MCP tools — admin-action audit coverage")
class McpToolAuditCoverageTest {

    private static final Path MCP = Path.of("src", "main", "java", "ai", "labs", "eddi", "engine", "mcp");

    /** Tools that change nothing. */
    private static final Set<String> READ_ONLY = Set.of(
            // conversations and agents
            "list_agents", "list_agent_configs", "read_conversation", "read_conversation_log", "list_conversations", "get_agent", "read_agent_logs",
            "read_audit_trail", "discover_agents",
            // administration
            "get_deployment_status", "list_workflows", "read_workflow", "read_resource", "list_agent_resources", "list_agent_triggers",
            "list_schedules", "read_schedule", "list_channel_integrations", "read_channel_integration",
            // groups
            "describe_discussion_styles", "list_groups", "read_group", "read_group_conversation", "list_group_conversations", "list_team_backlog",
            "list_group_templates",
            // memory
            "list_user_memories", "get_visible_memories", "search_user_memories", "get_memory_by_key", "count_user_memories",
            // the rest
            "get_eddi_version", "list_vault_keys", "read_model_prices", "read_tenant_quota", "list_spend_control_changes", "list_docs", "read_docs",
            "list_team_cadences", "read_lesson_consolidation", "read_team_budget", "list_team_budgets", "list_team_handoffs", "list_alert_events",
            "list_conversation_ratings", "summarize_conversation_ratings", "count_ratings_needing_attention", "summarize_approvals",
            "list_autonomy_recommendations", "get_autonomy_state", "count_pending_audit_samples", "list_pending_approvals", "get_approval_status",
            "list_group_pending_approvals", "list_all_group_pending_approvals", "get_group_approval_status", "get_agent_test_run",
            "list_agent_test_runs");

    /**
     * Mutators that reach the REST API over the loopback, where
     * {@code AdminActionAuditFilter} records them with the MCP caller's identity.
     */
    private static final Set<String> REST_BACKED = Set.of("create_agent", "delete_agent", "update_resource", "create_resource",
            "delete_resource", "create_agent_trigger", "update_agent_trigger", "delete_agent_trigger", "create_channel_integration",
            "update_channel_integration", "delete_channel_integration");
    // Checked by reading the bodies: these only call getRestStore(...) proxies.
    // update_agent (an in-process
    // redeploy) and apply_agent_changes (in-process AgentCascadeService) are NOT
    // here — they are @McpAudited.

    /** The conversational data plane, audited per turn like a chat message. */
    private static final Set<String> DATA_PLANE = Set.of("create_conversation", "talk_to_agent", "chat_with_agent", "chat_managed",
            "discuss_with_group", "start_group_discussion", "followup_with_member", "continue_group_discussion");

    /**
     * Writes its own ledger entry: the GDPR tools record the call themselves
     * (checked below), and the spend-control tools' changes go through
     * {@code SpendControlChangeService}, which writes {@code SpendControlAudit}
     * entries with the actor and the reason.
     */
    private static final Set<String> SELF_RECORDING = Set.of("delete_user_data", "export_user_data", "update_model_prices", "update_tenant_quota",
            "withdraw_spend_control_change");

    private static final List<String> READ_VERBS = List.of("list_", "read_", "get_", "count_", "search_", "summarize_", "describe_", "discover_");

    private static List<Method> tools() throws IOException, ClassNotFoundException {
        assertTrue(Files.isDirectory(MCP), "run from the project root");
        List<Method> tools = new ArrayList<>();
        try (Stream<Path> files = Files.list(MCP)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("ai.labs.eddi.engine.mcp." + name);
                for (Method method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        tools.add(method);
                    }
                }
            }
        }
        return tools;
    }

    @Test
    @DisplayName("every tool is audited, or named as read-only, REST-backed, data plane or self-recording")
    void everyToolIsAccountedFor() throws Exception {
        Set<String> unclassified = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        for (Method method : tools()) {
            String tool = McpAuditInterceptor.toolName(method);
            seen.add(tool);
            boolean audited = method.isAnnotationPresent(McpAudited.class);
            boolean exempt = READ_ONLY.contains(tool) || REST_BACKED.contains(tool) || DATA_PLANE.contains(tool) || SELF_RECORDING.contains(tool);
            if (!audited && !exempt) {
                unclassified.add(tool);
            }
            assertTrue(!(audited && exempt), tool + " is annotated @McpAudited and also on an exemption list — pick one");
        }
        assertTrue(unclassified.isEmpty(), "These MCP tools neither carry @McpAudited nor are classified in this test. A tool that changes state "
                + "needs @McpAudited (an in-process mutator leaves no admin-action entry otherwise); a read-only one goes on READ_ONLY: "
                + unclassified);
        Set<String> stale = new TreeSet<>();
        for (Set<String> list : List.of(READ_ONLY, REST_BACKED, DATA_PLANE, SELF_RECORDING)) {
            list.stream().filter(name -> !seen.contains(name)).forEach(stale::add);
        }
        assertTrue(stale.isEmpty(), "Classified here but no such tool exists any more: " + stale);
    }

    @Test
    @DisplayName("a class with an @McpAudited method carries the class-level binding that makes the interceptor run, like @McpErrorResults")
    void auditedClassesAreBound() throws Exception {
        Set<String> unbound = new TreeSet<>();
        for (Method method : tools()) {
            if (method.isAnnotationPresent(McpAudited.class) && !method.getDeclaringClass().isAnnotationPresent(McpAuditBinding.class)) {
                unbound.add(method.getDeclaringClass().getSimpleName());
            }
            if (method.isAnnotationPresent(McpAudited.class)) {
                assertTrue(method.getDeclaringClass().isAnnotationPresent(McpErrorResults.class),
                        method.getName() + ": the audit interceptor reads the outcome the error interceptor produces");
            }
        }
        assertTrue(unbound.isEmpty(), "Add @McpAuditBinding to: " + unbound);
        assertTrue(McpAuditInterceptor.class.isAnnotationPresent(McpAuditBinding.class));
    }

    @Test
    @DisplayName("a tool on the read-only list is named like a read")
    void readOnlyListHoldsReads() {
        List<String> misfiled = READ_ONLY.stream().filter(name -> READ_VERBS.stream().noneMatch(name::startsWith)).sorted().toList();
        assertTrue(misfiled.isEmpty(), "Not named like a read, so not read-only without a second look: " + misfiled);
    }

    @Test
    @DisplayName("the GDPR tools record their own entry")
    void gdprToolsRecordThemselves() throws IOException {
        String source = Files.readString(MCP.resolve("McpGdprTools.java"));
        assertTrue(source.contains("adminActionRecorder.recordToolCall("), "McpGdprTools must keep recording its calls");
    }
}
