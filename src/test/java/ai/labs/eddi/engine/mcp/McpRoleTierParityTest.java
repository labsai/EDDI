/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupTemplates;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace;
import ai.labs.eddi.configs.properties.IRestUserMemoryStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.api.IRestAgentSetup;
import ai.labs.eddi.engine.api.IRestDocs;
import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.gdpr.IRestGdprAdmin;
import ai.labs.eddi.engine.schedule.IRestScheduleStore;
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

import static ai.labs.eddi.engine.mcp.McpRoles.ADMIN;
import static ai.labs.eddi.engine.mcp.McpRoles.APPROVER;
import static ai.labs.eddi.engine.mcp.McpRoles.EDITOR;
import static ai.labs.eddi.engine.mcp.McpRoles.USER;
import static ai.labs.eddi.engine.mcp.McpRoles.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The MCP role matrix, measured rather than read: every one of the 84 tools is
 * invoked with authorization on, once per single-role identity, and the test
 * records whether the role gate let the call through. The result must equal the
 * documented tier of the tool, and each tier must equal the
 * {@code @RolesAllowed} of the REST resource it mirrors.
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
            McpGroupTools.class, McpHitlTools.class, McpMemoryTools.class, McpGdprTools.class, McpDocTools.class};

    /**
     * The tools whose callers are authorized per conversation by HitlAccessGuard,
     * not by role.
     */
    private static final Set<String> HITL_GUARDED = Set.of("list_pending_approvals", "get_approval_status", "resume_conversation",
            "cancel_conversation", "list_group_pending_approvals", "list_all_group_pending_approvals", "get_group_approval_status",
            "approve_group_phase", "cancel_group_discussion", "submit_group_human_input");

    private static final Set<String> ADMIN_ONLY_TOOLS = Set.of("setup_agent", "create_api_agent", "create_schedule", "list_schedules",
            "read_schedule", "delete_schedule", "fire_schedule_now", "retry_failed_schedule", "upsert_user_memory", "delete_user_memory",
            "delete_all_user_memories", "delete_user_data", "export_user_data",
            // unscoped: the shared server log buffer (see readAgentLogsScoping)
            "read_agent_logs");

    private static final Set<String> CONVERSE_TOOLS = Set.of("create_conversation", "talk_to_agent", "chat_with_agent", "chat_managed",
            "read_conversation", "read_conversation_log", "list_conversations", "read_audit_trail", "discover_agents",
            "describe_discussion_styles", "discuss_with_group", "start_group_discussion", "read_group_conversation",
            "list_group_conversations", "followup_with_member", "continue_group_discussion", "close_group_conversation",
            "delete_group_conversation", "list_user_memories", "get_visible_memories", "search_user_memories", "get_memory_by_key",
            "count_user_memories");

    private static final Set<String> DOCS_TOOLS = Set.of("list_docs", "read_docs");

    /**
     * Everything else is AUTHOR: McpAdminTools minus schedules, config reads, group
     * configuration.
     */
    private static List<String> expectedTier(String tool) {
        if (HITL_GUARDED.contains(tool)) {
            return null;
        }
        if (ADMIN_ONLY_TOOLS.contains(tool)) {
            return McpRoles.ADMIN_ONLY;
        }
        if (CONVERSE_TOOLS.contains(tool)) {
            return McpRoles.CONVERSE;
        }
        if (DOCS_TOOLS.contains(tool)) {
            return McpRoles.DOCS;
        }
        return McpRoles.AUTHOR;
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
        assertEquals(84, measured.size());

        var violations = new ArrayList<String>();
        for (var entry : measured.entrySet()) {
            List<String> tier = expectedTier(entry.getKey());
            Set<String> expected = tier == null ? new HashSet<>(ALL_ROLES) : new HashSet<>(tier);
            if (!expected.equals(entry.getValue())) {
                violations.add(entry.getKey() + ": admitted " + new TreeSet<>(entry.getValue()) + ", expected " + new TreeSet<>(expected));
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("hierarchy for reads: an admin or editor WITHOUT eddi-viewer can use the conversation tools")
    void editorWithoutViewerCanRead() throws Exception {
        for (String role : List.of(ADMIN, EDITOR)) {
            Object tools = instantiate(McpConversationTools.class, identityWith(Set.of(role)));
            for (String tool : List.of("readConversation", "readConversationLog", "listConversations", "discoverAgents")) {
                Method m = Arrays.stream(McpConversationTools.class.getMethods()).filter(x -> x.getName().equals(tool)).findFirst().orElseThrow();
                assertTrue(admitted(tools, m, new Object[m.getParameterCount()]), role + " refused " + tool);
            }
        }
    }

    @Test
    @DisplayName("read_agent_logs: conversation-scoped reads are in the conversation tier, unscoped reads are admin-only")
    void readAgentLogsScoping() throws Exception {
        for (String role : ALL_ROLES) {
            Object tools = instantiate(McpConversationTools.class, identityWith(Set.of(role)));
            Method m = McpConversationTools.class.getMethod("readAgentLogs", String.class, String.class, String.class, Integer.class);
            boolean scoped = admitted(tools, m, new Object[]{null, "conv-1", null, null});
            assertEquals(McpRoles.CONVERSE.contains(role), scoped, "scoped read for " + role);
        }
    }

    @Test
    @DisplayName("a caller with no EDDI role passes only the HITL tools' role gate (they are guarded per conversation)")
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
        assertEquals(new HashSet<>(HITL_GUARDED), new HashSet<>(admittedTools));
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

        for (var entry : mirrors.entrySet()) {
            assertEquals(restRoles(entry.getKey()), new HashSet<>(entry.getValue()), entry.getKey().getSimpleName());
        }

        // The conversation tier is REST's conversation tier plus eddi-viewer, the MCP
        // read-and-converse role (every tool in it is ownership-guarded).
        var converseWithoutViewer = new HashSet<>(McpRoles.CONVERSE);
        converseWithoutViewer.remove(VIEWER);
        assertEquals(restRoles(IRestAgentEngine.class), converseWithoutViewer);
        assertEquals(restRoles(IRestGroupConversation.class), converseWithoutViewer);
    }

    @Test
    @DisplayName("where MCP is deliberately narrower than REST, it is narrower — never wider")
    void deliberateNarrowingsAreSubsets() {
        // Schedules: the MCP tools address the schedule store directly, without the
        // per-owner scoping RestScheduleStore applies, so they stay admin-only.
        assertTrue(restRoles(IRestScheduleStore.class).containsAll(McpRoles.ADMIN_ONLY));
        // Memory writes for an arbitrary userId: admin-only; REST lets a user write
        // only their own.
        assertTrue(restRoles(IRestUserMemoryStore.class).containsAll(McpRoles.ADMIN_ONLY));
    }
}
