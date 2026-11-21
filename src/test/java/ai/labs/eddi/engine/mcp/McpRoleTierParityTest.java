/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.agenttests.IRestAgentTestRuns;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupLessons;
import ai.labs.eddi.configs.groups.IRestGroupTemplates;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs;
import ai.labs.eddi.configs.properties.IRestUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.api.IRestAgentSetup;
import ai.labs.eddi.engine.api.IRestAlertEvents;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin;
import ai.labs.eddi.engine.api.IRestDocs;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.api.IRestLogAdmin;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.gdpr.IRestGdprAdmin;
import ai.labs.eddi.engine.groups.budget.rest.IRestGroupBudget;
import ai.labs.eddi.engine.groups.budget.rest.IRestTeamBudgets;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestApprovalAnalytics;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAuditSamples;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAutonomy;
import ai.labs.eddi.engine.pricing.rest.IRestModelPriceCatalog;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
import ai.labs.eddi.engine.spendcontrol.rest.IRestSpendControlChanges;
import ai.labs.eddi.engine.tenancy.rest.IRestTenantQuota;
import ai.labs.eddi.secrets.rest.IRestSecretStore;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.engine.mcp.McpRoles.ADMIN;
import static ai.labs.eddi.engine.mcp.McpRoles.APPROVER;
import static ai.labs.eddi.engine.mcp.McpRoles.EDITOR;
import static ai.labs.eddi.engine.mcp.McpRoles.USER;
import static ai.labs.eddi.engine.mcp.McpRoles.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The MCP role matrix, measured rather than read: every tool is invoked with
 * authorization on, once per single-role identity, and the test records whether
 * the role gate let the call through. The result must equal the tool's entry in
 * {@link McpToolTiers}, which every tool's gate reads, and each tier must equal
 * the {@code @RolesAllowed} of the REST resource it mirrors.
 * <p>
 * Because the gates read {@link McpToolTiers}, "measured equals the map" alone
 * would compare the map with itself. So the sets below are a second,
 * hand-written record of which tool belongs to which tier, and
 * {@link #tierMapEqualsTheHandWrittenRecord()} requires the map to equal it:
 * moving a tool to another tier takes an edit in both places.
 * <p>
 * This replaces three divergences: MCP agent setup open to editors while REST
 * setup is admin-only, MCP deploy admin-only while REST deploy admits editors,
 * and config reads ({@code get_agent}, {@code list_agents}) open to viewers who
 * cannot make the same read over REST. It also pins the hierarchy for read
 * tools: an editor or admin who was not separately granted {@code eddi-viewer}
 * is no longer refused every conversation tool.
 */
class McpRoleTierParityTest {

    private static final List<String> ALL_ROLES = List.of(ADMIN, EDITOR, USER, VIEWER, APPROVER);

    private static final Class<?>[] TOOL_CLASSES = {McpAdminTools.class, McpConversationTools.class, McpSetupTools.class,
            McpGroupTools.class, McpHitlTools.class, McpMemoryTools.class, McpGdprTools.class, McpDocTools.class,
            McpAgentTestTools.class, McpRatingTools.class, McpAlertTools.class,
            McpPricingTools.class, McpTeamHandoffTools.class, McpOversightTools.class, McpTeamBudgetTools.class,
            McpSpendControlTools.class, McpTeamCadenceTools.class, McpVaultTools.class, McpVersionTools.class};

    /**
     * The HITL tools: the REST HITL tier first (L5 — they used to carry no role
     * tier at all, so an eddi-viewer could decide its own gated tool calls over
     * MCP), then HitlAccessGuard per conversation.
     */
    private static final Set<String> HITL_TOOLS = Set.of("list_pending_approvals", "get_approval_status", "resume_conversation",
            "cancel_conversation", "list_group_pending_approvals", "list_all_group_pending_approvals", "get_group_approval_status",
            "approve_group_phase", "cancel_group_discussion", "submit_group_human_input");

    private static final Set<String> ADMIN_ONLY_TOOLS = Set.of("setup_agent", "create_api_agent", "create_schedule", "list_schedules",
            "read_schedule", "delete_schedule", "fire_schedule_now", "retry_failed_schedule", "upsert_user_memory", "delete_user_memory",
            "delete_all_user_memories", "delete_user_data", "export_user_data",
            // the running build (McpVersionTools)
            "get_eddi_version",
            // a team's cap is a spending lever (IRestGroupBudget#setCurrentMonthCap,
            // IRestTeamBudgets#transfer)
            "adjust_team_budget", "transfer_team_budget",
            // spending controls: the quota resource and the catalog write are
            // admin-only; a loosening write only files a proposal an admin approves
            "read_tenant_quota", "update_model_prices", "update_tenant_quota", "list_spend_control_changes",
            "withdraw_spend_control_change",
            // vault key names (IRestSecretStore is admin-only)
            "list_vault_keys");

    private static final Set<String> CONVERSE_TOOLS = Set.of("create_conversation", "talk_to_agent", "chat_with_agent", "chat_managed",
            "read_conversation", "read_conversation_log", "list_conversations", "discover_agents",
            "describe_discussion_styles", "discuss_with_group", "start_group_discussion", "read_group_conversation",
            "list_group_conversations", "followup_with_member", "continue_group_discussion", "close_group_conversation",
            "delete_group_conversation", "list_user_memories", "get_visible_memories", "search_user_memories", "get_memory_by_key",
            "count_user_memories");

    /**
     * Conversation diagnostics: admin plus the MCP observer role. Their REST
     * counterparts (audit, logs) are admin-only; see {@link McpRoles#OBSERVE}.
     * read_agent_logs is here under the union of its tiers; see
     * {@link #MEASURED_WITHOUT_ARGUMENTS}.
     */
    private static final Set<String> OBSERVE_TOOLS = Set.of("read_agent_logs", "read_audit_trail");

    /**
     * Tools whose tier depends on their arguments. {@link McpToolTiers} lists them
     * under the union of their tiers, but the matrix calls every tool with null
     * arguments, which takes the narrowest branch. The value is the tier that
     * branch admits; a dedicated test covers the other branch.
     * <p>
     * read_agent_logs without a conversationId reads the shared server log buffer,
     * which is admin-only; readAgentLogsScoping covers the conversation-scoped
     * read.
     */
    private static final Map<String, List<String>> MEASURED_WITHOUT_ARGUMENTS = Map.of("read_agent_logs", McpRoles.ADMIN_ONLY);

    private static final Set<String> DOCS_TOOLS = Set.of("list_docs", "read_docs");

    /** HITL oversight reads: REST's admin/editor/approver tier. */
    private static final Set<String> OVERSIGHT_TOOLS = Set.of("summarize_approvals", "list_autonomy_recommendations", "get_autonomy_state",
            "count_pending_audit_samples");

    /** Team budget reads: REST's admin/editor/viewer tier. */
    private static final Set<String> BUDGET_READ_TOOLS = Set.of("read_team_budget", "list_team_budgets");

    /**
     * Authoring: McpAdminTools minus schedules, config reads, group configuration,
     * and the authoring tools of the smaller classes. Listed explicitly, not as a
     * fall-through: a tool that is in no set has no expected tier, so a new tool
     * filed under AUTHOR in McpToolTiers cannot pass without an entry here.
     */
    private static final Set<String> AUTHOR_TOOLS = Set.of(
            // McpConversationTools: config reads
            "list_agents", "list_agent_configs", "get_agent",
            // McpAdminTools
            "deploy_agent", "undeploy_agent", "upgrade_conversations", "get_deployment_status", "list_workflows", "create_agent",
            "delete_agent", "update_agent", "read_workflow", "read_resource", "update_resource", "create_resource", "delete_resource",
            "apply_agent_changes", "list_agent_resources", "list_agent_triggers", "create_agent_trigger", "update_agent_trigger",
            "delete_agent_trigger", "list_channel_integrations", "read_channel_integration", "create_channel_integration",
            "update_channel_integration", "delete_channel_integration",
            // McpGroupTools
            "list_groups", "read_group", "create_group", "update_group", "delete_group", "add_team_task", "list_team_backlog",
            "list_group_templates", "create_group_from_template",
            // McpTeamHandoffTools, McpTeamCadenceTools
            "list_team_handoffs", "accept_team_handoff", "decline_team_handoff", "list_team_cadences", "create_team_cadence",
            "delete_team_cadence", "read_lesson_consolidation", "schedule_lesson_consolidation", "unschedule_lesson_consolidation",
            // McpAgentTestTools, McpRatingTools, McpAlertTools, McpPricingTools
            "run_agent_tests", "get_agent_test_run", "list_agent_test_runs", "cancel_agent_test_run", "list_conversation_ratings",
            "summarize_conversation_ratings", "count_ratings_needing_attention", "review_conversation_rating", "list_alert_events",
            "acknowledge_alert_event", "read_model_prices");

    private static final Map<List<String>, Set<String>> RECORD = recordByTier();

    private static Map<List<String>, Set<String>> recordByTier() {
        Map<List<String>, Set<String>> record = new LinkedHashMap<>();
        record.put(McpRoles.HITL, HITL_TOOLS);
        record.put(McpRoles.ADMIN_ONLY, ADMIN_ONLY_TOOLS);
        record.put(McpRoles.CONVERSE, CONVERSE_TOOLS);
        record.put(McpRoles.OBSERVE, OBSERVE_TOOLS);
        record.put(McpRoles.DOCS, DOCS_TOOLS);
        record.put(McpRoles.OVERSIGHT, OVERSIGHT_TOOLS);
        record.put(McpRoles.BUDGET_READ, BUDGET_READ_TOOLS);
        record.put(McpRoles.AUTHOR, AUTHOR_TOOLS);
        return record;
    }

    /** The hand-written tier of {@code tool}, or null when the record has none. */
    private static List<String> expectedTier(String tool) {
        if (HITL_TOOLS.contains(tool)) {
            return McpRoles.HITL;
        }
        if (ADMIN_ONLY_TOOLS.contains(tool)) {
            return McpRoles.ADMIN_ONLY;
        }
        if (CONVERSE_TOOLS.contains(tool)) {
            return McpRoles.CONVERSE;
        }
        if (OBSERVE_TOOLS.contains(tool)) {
            return McpRoles.OBSERVE;
        }
        if (DOCS_TOOLS.contains(tool)) {
            return McpRoles.DOCS;
        }
        if (OVERSIGHT_TOOLS.contains(tool)) {
            return McpRoles.OVERSIGHT;
        }
        if (BUDGET_READ_TOOLS.contains(tool)) {
            return McpRoles.BUDGET_READ;
        }
        if (AUTHOR_TOOLS.contains(tool)) {
            return McpRoles.AUTHOR;
        }
        return null;
    }

    // ------------------------------------------------------------------ harness

    private static SecurityIdentity identityWith(Set<String> roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn("alice");
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        lenient().when(identity.getRoles()).thenReturn(roles);
        return identity;
    }

    private static Object instantiate(Class<?> toolClass, SecurityIdentity identity) throws Exception {
        Constructor<?> ctor = Arrays.stream(toolClass.getConstructors())
                .filter(c -> c.isAnnotationPresent(Inject.class))
                .findFirst()
                .orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == SecurityIdentity.class) {
                args[i] = identity;
            } else if (types[i] == boolean.class) {
                args[i] = true; // authorization.enabled (and the HITL mutation switch)
            } else {
                args[i] = mock(types[i]);
            }
        }
        return ctor.newInstance(args);
    }

    /**
     * Whether the role gate admitted the call. A refusal by role is a
     * ForbiddenException whose message names the roles; anything else — a result, a
     * validation error, an exception from a mocked collaborator — means the call
     * got past the gate.
     */
    private static boolean admitted(Object tools, Method tool, Object[] args) throws Exception {
        try {
            tool.invoke(tools, args);
            return true;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            return !(cause instanceof ForbiddenException && cause.getMessage() != null && cause.getMessage().contains("requires"));
        }
    }

    private static Map<String, Set<String>> measureMatrix() throws Exception {
        Map<String, Set<String>> admittedRoles = new TreeMap<>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (String role : ALL_ROLES) {
                Object tools = instantiate(toolClass, identityWith(Set.of(role)));
                for (Method m : toolClass.getDeclaredMethods()) {
                    if (!m.isAnnotationPresent(Tool.class)) {
                        continue;
                    }
                    String name = McpToolContractTest.toolName(m);
                    admittedRoles.computeIfAbsent(name, k -> new HashSet<>());
                    if (admitted(tools, m, new Object[m.getParameterCount()])) {
                        admittedRoles.get(name).add(role);
                    }
                }
            }
        }
        return admittedRoles;
    }

    // ------------------------------------------------------------------ the matrix

    @Test
    @DisplayName("every tool admits exactly the roles of its tier — measured, one role at a time")
    void measuredMatrixEqualsTheDocumentedTiers() throws Exception {
        Map<String, Set<String>> measured = measureMatrix();
        assertEquals(McpToolTiers.TIERS.keySet(), measured.keySet(), "the measured tools are exactly the tools with a tier");

        var violations = new ArrayList<String>();
        for (var entry : measured.entrySet()) {
            Set<String> expected = new HashSet<>(
                    MEASURED_WITHOUT_ARGUMENTS.getOrDefault(entry.getKey(), McpToolTiers.of(entry.getKey())));
            if (!expected.equals(entry.getValue())) {
                violations.add(entry.getKey() + ": admitted " + new TreeSet<>(entry.getValue()) + ", expected " + new TreeSet<>(expected));
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("McpToolTiers equals the hand-written tier record — a tier change needs both edits")
    void tierMapEqualsTheHandWrittenRecord() {
        var violations = new ArrayList<String>();
        for (var entry : McpToolTiers.TIERS.entrySet()) {
            List<String> recorded = expectedTier(entry.getKey());
            if (recorded == null) {
                violations.add(entry.getKey() + ": has a tier in McpToolTiers but none in this test's record");
            } else if (!new HashSet<>(recorded).equals(new HashSet<>(entry.getValue()))) {
                violations.add(entry.getKey() + ": McpToolTiers says " + new TreeSet<>(entry.getValue()) + ", the record says "
                        + new TreeSet<>(recorded));
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));

        // The record names every tool exactly once, and only real tools
        var named = new TreeSet<String>();
        var duplicates = new TreeSet<String>();
        for (Set<String> set : RECORD.values()) {
            for (String tool : set) {
                if (!named.add(tool)) {
                    duplicates.add(tool);
                }
            }
        }
        assertEquals(Set.of(), duplicates, "names in more than one tier of the record");
        assertEquals(new TreeSet<>(McpToolTiers.TIERS.keySet()), named, "the record and McpToolTiers must name the same tools");
    }

    /**
     * Each gate reads its own tool's tier. Nothing else would notice a gate in
     * deploy_agent that reads {@code McpToolTiers.of("undeploy_agent")} while both
     * happen to share a tier, so the sources are scanned: every
     * {@code McpToolTiers.of("x")} must sit in the body of the {@code @Tool} named
     * x, and every tool must have exactly one.
     */
    @Test
    @DisplayName("every tool's gate reads its own tool name — once")
    void eachGateNamesItsOwnTool() throws Exception {
        Pattern lookup = Pattern.compile("McpToolTiers\\.of\\(\"([a-z_]+)\"\\)");
        Pattern explicitName = Pattern.compile("^@Tool\\((?:[^()]|\\([^()]*\\))*?\\bname\\s*=\\s*\"([a-z_]+)\"", Pattern.DOTALL);
        Pattern signature = Pattern.compile("public\\s+[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\(");
        var violations = new ArrayList<String>();
        var gatesPerTool = new TreeMap<String, Integer>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            String src = Files.readString(Path.of("src/main/java", toolClass.getName().replace('.', '/') + ".java"));
            List<Integer> starts = new ArrayList<>();
            Matcher tool = Pattern.compile("@Tool\\(").matcher(src);
            while (tool.find()) {
                starts.add(tool.start());
            }
            for (int i = 0; i < starts.size(); i++) {
                String segment = src.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : src.length());
                Matcher sig = signature.matcher(segment);
                assertTrue(sig.find(), "no method after an @Tool in " + toolClass.getSimpleName());
                Matcher named = explicitName.matcher(segment.substring(0, sig.start()));
                String name = named.find() ? named.group(1) : sig.group(1);
                Matcher gate = lookup.matcher(segment.substring(sig.start()));
                int count = 0;
                while (gate.find()) {
                    count++;
                    if (!gate.group(1).equals(name)) {
                        violations.add(name + " reads the tier of " + gate.group(1));
                    }
                }
                gatesPerTool.put(name, count);
            }
        }
        assertEquals(McpToolTiers.TIERS.keySet(), gatesPerTool.keySet(), "the scan must see every tool");
        gatesPerTool.forEach((name, count) -> {
            if (count != 1) {
                violations.add(name + " has " + count + " McpToolTiers gates, expected 1");
            }
        });
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("an unknown tool name has no tier, so its gate fails closed")
    void unknownToolHasNoTier() {
        assertThrows(IllegalArgumentException.class, () -> McpToolTiers.of("no_such_tool"));
    }

    @Test
    @DisplayName("hierarchy for reads: an admin or editor WITHOUT eddi-viewer can use the conversation tools")
    void editorWithoutViewerCanRead() throws Exception {
        for (String role : List.of(ADMIN, EDITOR)) {
            Object tools = instantiate(McpConversationTools.class, identityWith(Set.of(role)));
            for (String tool : List.of("readConversation", "readConversationLog", "listConversations", "discoverAgents", "chatWithAgent")) {
                Method m = Arrays.stream(McpConversationTools.class.getMethods()).filter(x -> x.getName().equals(tool)).findFirst().orElseThrow();
                assertTrue(admitted(tools, m, new Object[m.getParameterCount()]), role + " refused " + tool);
            }
        }
    }

    @Test
    @DisplayName("read_agent_logs: conversation-scoped reads are in the observer tier, unscoped reads are admin-only")
    void readAgentLogsScoping() throws Exception {
        for (String role : ALL_ROLES) {
            Object tools = instantiate(McpConversationTools.class, identityWith(Set.of(role)));
            Method m = McpConversationTools.class.getMethod("readAgentLogs", String.class, String.class, String.class, Integer.class);
            boolean scoped = admitted(tools, m, new Object[]{null, "conv-1", null, null});
            assertEquals(McpRoles.OBSERVE.contains(role), scoped, "scoped read for " + role);
        }
    }

    @Test
    @DisplayName("a caller with no EDDI role passes no tool's role gate — the HITL tools included (L5)")
    void noRoleCaller() throws Exception {
        var admittedTools = new ArrayList<String>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            Object tools = instantiate(toolClass, identityWith(Set.of()));
            for (Method m : toolClass.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Tool.class) && admitted(tools, m, new Object[m.getParameterCount()])) {
                    admittedTools.add(McpToolContractTest.toolName(m));
                }
            }
        }
        assertEquals(Set.of(), new HashSet<>(admittedTools));
    }

    @Test
    @DisplayName("eddi-viewer is admitted by no HITL tool — it is an MCP read role, and REST refuses it at every HITL endpoint")
    void viewerIsRefusedByEveryHitlTool() throws Exception {
        Object tools = instantiate(McpHitlTools.class, identityWith(Set.of(VIEWER)));
        for (Method m : McpHitlTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                assertTrue(!admitted(tools, m, new Object[m.getParameterCount()]), "viewer admitted to " + McpToolContractTest.toolName(m));
            }
        }
    }

    // ------------------------------------------------------------------ parity
    // with REST

    private static Set<String> restRoles(Class<?> restInterface) {
        RolesAllowed annotation = restInterface.getAnnotation(RolesAllowed.class);
        return new HashSet<>(Arrays.asList(annotation.value()));
    }

    @Test
    @DisplayName("each MCP tier equals the @RolesAllowed of the REST resources it mirrors")
    void tiersMatchRest() {
        Map<Class<?>, List<String>> mirrors = new LinkedHashMap<>();
        mirrors.put(IRestAgentSetup.class, McpRoles.ADMIN_ONLY); // setup_agent, create_api_agent
        mirrors.put(IRestGdprAdmin.class, McpRoles.ADMIN_ONLY); // delete_user_data, export_user_data
        mirrors.put(IRestAgentAdministration.class, McpRoles.AUTHOR); // deploy/undeploy/status, list_agents
        mirrors.put(IRestAgentStore.class, McpRoles.AUTHOR); // get_agent, list_agent_configs, agent CRUD
        mirrors.put(IRestWorkflowStore.class, McpRoles.AUTHOR); // list_workflows, read_workflow
        mirrors.put(IRestAgentTriggerStore.class, McpRoles.AUTHOR); // *_agent_trigger
        mirrors.put(IRestChannelIntegrationStore.class, McpRoles.AUTHOR); // *_channel_integration
        mirrors.put(IRestAgentGroupStore.class, McpRoles.AUTHOR); // group configuration
        mirrors.put(IRestGroupTemplates.class, McpRoles.AUTHOR); // list_group_templates, create_group_from_template
        mirrors.put(IRestGroupWorkspace.class, McpRoles.AUTHOR); // add_team_task, list_team_backlog
        mirrors.put(IRestDocs.class, McpRoles.DOCS); // list_docs, read_docs
        mirrors.put(IRestAgentTestRuns.class, McpRoles.AUTHOR); // run_agent_tests, get_agent_test_run, list_agent_test_runs,
                                                                // cancel_agent_test_run
        mirrors.put(IRestConversationRatingAdmin.class, McpRoles.AUTHOR); // *_conversation_ratings, review_conversation_rating
        mirrors.put(IRestAlertEvents.class, McpRoles.AUTHOR); // list_alert_events, acknowledge_alert_event
        mirrors.put(IRestTeamHandoffs.class, McpRoles.AUTHOR); // list/accept/decline_team_handoff
        mirrors.put(IRestGroupLessons.class, McpRoles.AUTHOR); // *_lesson_consolidation
        mirrors.put(IRestTenantQuota.class, McpRoles.ADMIN_ONLY); // read_tenant_quota, update_tenant_quota
        mirrors.put(IRestSpendControlChanges.class, McpRoles.ADMIN_ONLY); // list/withdraw_spend_control_change
        mirrors.put(IRestSecretStore.class, McpRoles.ADMIN_ONLY); // list_vault_keys
        mirrors.put(IRestApprovalAnalytics.class, McpRoles.OVERSIGHT); // summarize_approvals
        mirrors.put(IRestAutonomy.class, McpRoles.OVERSIGHT); // list_autonomy_recommendations, get_autonomy_state
        mirrors.put(IRestAuditSamples.class, McpRoles.OVERSIGHT); // count_pending_audit_samples

        for (var entry : mirrors.entrySet()) {
            assertEquals(restRoles(entry.getKey()), new HashSet<>(entry.getValue()), entry.getKey().getSimpleName());
        }

        // The conversation tier is REST's conversation tier plus eddi-viewer, the MCP
        // read-and-converse role (every tool in it is ownership-guarded).
        var converseWithoutViewer = new HashSet<>(McpRoles.CONVERSE);
        converseWithoutViewer.remove(VIEWER);
        assertEquals(restRoles(IRestAgentEngine.class), converseWithoutViewer);
        assertEquals(restRoles(IRestGroupConversation.class), converseWithoutViewer);

        // The HITL tier is the method-level @RolesAllowed of every REST HITL endpoint
        // — the 1:1 surface and the group surface alike (L5).
        Map<Class<?>, List<String>> hitlEndpoints = new LinkedHashMap<>();
        hitlEndpoints.put(IRestAgentEngine.class, List.of("resumeConversation", "getApprovalStatus", "cancelConversation", "listPendingApprovals"));
        hitlEndpoints.put(IRestGroupConversation.class, List.of("approveGroupPhase", "cancelDiscussion", "submitHumanInput",
                "listGroupPendingApprovals", "listAllGroupPendingApprovals"));
        for (var entry : hitlEndpoints.entrySet()) {
            for (String methodName : entry.getValue()) {
                Method rest = Arrays.stream(entry.getKey().getMethods()).filter(x -> x.getName().equals(methodName)).findFirst().orElseThrow();
                RolesAllowed annotation = rest.getAnnotation(RolesAllowed.class);
                assertTrue(annotation != null, methodName + " carries no @RolesAllowed of its own");
                assertEquals(new HashSet<>(Arrays.asList(annotation.value())), new HashSet<>(McpRoles.HITL),
                        entry.getKey().getSimpleName() + "#" + methodName);
            }
        }

        // Resources whose tiers sit on the methods, not the class: each read or write
        // an MCP tool wraps carries exactly the tool's tier.
        record MethodTier(Class<?> rest, String method, List<String> tier) {
        }
        for (MethodTier mt : List.of(
                new MethodTier(IRestModelPriceCatalog.class, "readCatalog", McpRoles.AUTHOR), // read_model_prices
                new MethodTier(IRestModelPriceCatalog.class, "resolve", McpRoles.AUTHOR),
                new MethodTier(IRestModelPriceCatalog.class, "unpriced", McpRoles.AUTHOR),
                new MethodTier(IRestGroupBudget.class, "readBudget", McpRoles.BUDGET_READ), // read_team_budget
                new MethodTier(IRestGroupBudget.class, "readHistory", McpRoles.BUDGET_READ),
                new MethodTier(IRestTeamBudgets.class, "listBudgets", McpRoles.BUDGET_READ), // list_team_budgets
                new MethodTier(IRestGroupBudget.class, "setCurrentMonthCap", McpRoles.ADMIN_ONLY), // adjust_team_budget
                new MethodTier(IRestTeamBudgets.class, "transfer", McpRoles.ADMIN_ONLY), // transfer_team_budget
                new MethodTier(IRestModelPriceCatalog.class, "replaceCatalog", McpRoles.ADMIN_ONLY))) { // update_model_prices
            Method rest = Arrays.stream(mt.rest().getMethods()).filter(x -> x.getName().equals(mt.method())).findFirst().orElseThrow();
            RolesAllowed annotation = rest.getAnnotation(RolesAllowed.class);
            assertTrue(annotation != null, mt.method() + " carries no @RolesAllowed of its own");
            assertEquals(new HashSet<>(Arrays.asList(annotation.value())), new HashSet<>(mt.tier()),
                    mt.rest().getSimpleName() + "#" + mt.method());
        }

        // The observer tier is REST's audit/log tier (admin-only) plus eddi-viewer.
        // It must never admit eddi-user or eddi-editor: an audit entry carries the
        // agent's system prompt and tool-call arguments, which neither can read over
        // REST.
        var observeWithoutViewer = new HashSet<>(McpRoles.OBSERVE);
        observeWithoutViewer.remove(VIEWER);
        assertEquals(restRoles(IRestAuditStore.class), observeWithoutViewer, "IRestAuditStore");
        assertEquals(restRoles(IRestLogAdmin.class), observeWithoutViewer, "IRestLogAdmin");
    }

    @Test
    @DisplayName("the MCP memory reads admit every REST memory role; the extra roles reach only the caller's own userId")
    void memoryReadsCoverRest() {
        assertTrue(McpRoles.CONVERSE.containsAll(restRoles(IRestUserMemoryStore.class)));
        var extra = new HashSet<>(McpRoles.CONVERSE);
        extra.removeAll(restRoles(IRestUserMemoryStore.class));
        // eddi-editor and eddi-viewer: both pass OwnershipValidator.validateUserAccess
        // only for their own userId (it exempts eddi-admin alone) — documented in
        // McpRoles and docs/mcp-server.md.
        assertEquals(Set.of(EDITOR, VIEWER), extra);
    }

    @Test
    @DisplayName("where MCP is deliberately narrower than REST, it is narrower — never wider")
    void deliberateNarrowingsAreSubsets() {
        // Schedules: admin-only, narrower than REST's admin/editor tier. The tools run
        // through RestScheduleStore under the caller's identity, so widening them is
        // now a decision about the tier, not about missing owner checks.
        assertTrue(restRoles(IRestScheduleStore.class).containsAll(McpRoles.ADMIN_ONLY));
        // Memory writes for an arbitrary userId: admin-only; REST lets a user write
        // only their own.
        assertTrue(restRoles(IRestUserMemoryStore.class).containsAll(McpRoles.ADMIN_ONLY));
    }
}
