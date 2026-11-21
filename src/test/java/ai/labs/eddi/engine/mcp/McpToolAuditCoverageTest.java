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
import java.util.Arrays;
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
 * The lists are explicit, tool by tool, and were drawn up by reading each
 * tool's body (and the service it calls): a name is no evidence. Adding a name
 * to {@link #READ_ONLY} is the statement that the tool changes nothing.
 */
@DisplayName("MCP tools — admin-action audit coverage")
class McpToolAuditCoverageTest {

    private static final Path MCP = Path.of("src", "main", "java", "ai", "labs", "eddi", "engine", "mcp");

    /** Tools that change nothing — checked body by body. */
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
            // memory (get_visible_memories is not here: most_accessed counts the read as an
            // access)
            "list_user_memories", "search_user_memories", "get_memory_by_key", "count_user_memories",
            // the rest (list_team_cadences is not here: it reconciles cadence runs)
            "get_eddi_version", "list_vault_keys", "read_model_prices", "list_docs", "read_docs", "read_lesson_consolidation", "read_team_budget",
            "list_team_budgets", "list_alert_events", "list_conversation_ratings", "summarize_conversation_ratings",
            "count_ratings_needing_attention", "summarize_approvals", "list_autonomy_recommendations", "get_autonomy_state",
            "count_pending_audit_samples", "list_pending_approvals", "get_approval_status", "list_group_pending_approvals",
            "list_all_group_pending_approvals", "get_group_approval_status", "get_agent_test_run", "list_agent_test_runs");

    /**
     * Reads with a deterministic, system-driven write the caller does not direct:
     * {@code list_team_handoffs} inserts an empty workspace the first time a group
     * is viewed; {@code read_tenant_quota} and {@code list_spend_control_changes}
     * expire pending changes whose deadline passed (as {@code system:expiry}). Not
     * an administrative action of the caller, so not recorded as one.
     */
    private static final Set<String> READ_WITH_BOOKKEEPING = Set.of("list_team_handoffs", "read_tenant_quota", "list_spend_control_changes");

    /**
     * Mutators that reach the REST API over the loopback ({@code getRestStore} →
     * {@code RestInterfaceFactory}, a real HTTP request), where
     * {@code AdminActionAuditFilter} records them with the MCP caller's identity.
     * Checked by reading the bodies: these change state only through those proxies.
     * {@code update_agent} (an in-process redeploy) and {@code apply_agent_changes}
     * (in-process {@code AgentCascadeService}) are NOT here — they are
     * {@code @McpAudited}.
     */
    private static final Set<String> REST_BACKED = Set.of("create_agent", "delete_agent", "update_resource", "create_resource",
            "delete_resource", "create_agent_trigger", "update_agent_trigger", "delete_agent_trigger", "create_channel_integration",
            "update_channel_integration", "delete_channel_integration");

    /**
     * The conversational data plane, audited per pipeline task like a chat message
     * — the REST filter skips the same surface ({@code /agents}, {@code /chat}).
     */
    private static final Set<String> DATA_PLANE = Set.of("create_conversation", "talk_to_agent", "chat_with_agent", "chat_managed",
            "discuss_with_group", "start_group_discussion", "followup_with_member", "continue_group_discussion");

    /**
     * Writes its own admin-action entry on every path, refusals included (checked
     * below). The spend-control tools are {@code @McpAudited}: their service writes
     * a {@code spendcontrol} ledger entry for an applied, proposed or withdrawn
     * change only, so the refusals would otherwise go unrecorded.
     */
    private static final Set<String> SELF_RECORDING = Set.of("delete_user_data", "export_user_data");

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
    @DisplayName("every tool is audited, or named as read-only, read-with-bookkeeping, REST-backed, data plane or self-recording")
    void everyToolIsAccountedFor() throws Exception {
        Set<String> unclassified = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        List<Set<String>> lists = List.of(READ_ONLY, READ_WITH_BOOKKEEPING, REST_BACKED, DATA_PLANE, SELF_RECORDING);
        for (Method method : tools()) {
            String tool = McpAuditInterceptor.toolName(method);
            seen.add(tool);
            boolean audited = method.isAnnotationPresent(McpAudited.class);
            long listed = lists.stream().filter(list -> list.contains(tool)).count();
            if (!audited && listed == 0) {
                unclassified.add(tool);
            }
            assertTrue(!(audited && listed > 0), tool + " is annotated @McpAudited and also on an exemption list — pick one");
            assertTrue(listed <= 1, tool + " is on more than one list");
        }
        assertTrue(unclassified.isEmpty(), "These MCP tools neither carry @McpAudited nor are classified in this test. A tool that changes state "
                + "needs @McpAudited (an in-process mutator leaves no admin-action entry otherwise); a read-only one goes on READ_ONLY, "
                + "after reading its body and the service it calls: " + unclassified);
        Set<String> stale = new TreeSet<>();
        for (Set<String> list : lists) {
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
    @DisplayName("every audited subject and target index names a String argument of the tool's own signature")
    void auditedIndicesMatchTheSignature() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Method method : tools()) {
            McpAudited audited = method.getAnnotation(McpAudited.class);
            if (audited == null) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            List<Integer> indices = new ArrayList<>();
            if (audited.subject() != -1) {
                indices.add(audited.subject());
            }
            for (int target : audited.target()) {
                indices.add(target);
            }
            for (int index : indices) {
                if (index < 0 || index >= parameters.length || parameters[index] != String.class) {
                    wrong.add(McpAuditInterceptor.toolName(method) + " index " + index);
                }
            }
            if (audited.subject() != -1 && Arrays.stream(audited.target()).anyMatch(target -> target == audited.subject())) {
                wrong.add(McpAuditInterceptor.toolName(method) + ": the subject is also a target, so it would be recorded raw");
            }
        }
        assertTrue(wrong.isEmpty(), "@McpAudited indices that do not name a String argument: " + wrong);
    }

    @Test
    @DisplayName("the GDPR tools record their own entry")
    void gdprToolsRecordThemselves() throws IOException {
        String source = Files.readString(MCP.resolve("McpGdprTools.java"));
        assertTrue(source.contains("adminActionRecorder.recordToolCall("), "McpGdprTools must keep recording its calls");
    }
}
