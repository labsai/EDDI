/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpToolFilterTest {

    private final McpToolFilter filter = new McpToolFilter();

    // --- Whitelisted tool names ---

    @ParameterizedTest
    @ValueSource(strings = {
            "list_agents", "create_conversation", "talk_to_agent",
            "chat_with_agent", "read_conversation", "read_conversation_log",
            "list_conversations", "get_agent", "deploy_agent",
            "undeploy_agent", "get_deployment_status", "list_workflows",
            "create_agent", "delete_agent", "update_agent",
            "read_workflow", "read_resource", "setup_agent",
            "create_api_agent", "update_resource", "create_resource",
            "delete_resource", "apply_agent_changes", "list_agent_resources",
            "read_agent_logs", "read_audit_trail", "discover_agents",
            "chat_managed", "list_agent_triggers", "create_agent_trigger",
            "update_agent_trigger", "delete_agent_trigger",
            "describe_discussion_styles", "list_groups", "read_group",
            "create_group", "update_group", "delete_group",
            "discuss_with_group", "read_group_conversation",
            "list_group_conversations", "list_agent_configs",
            "start_group_discussion", "delete_group_conversation",
            "create_schedule", "list_schedules", "read_schedule",
            "delete_schedule", "fire_schedule_now", "retry_failed_schedule",
            "list_channel_integrations", "read_channel_integration",
            "create_channel_integration", "update_channel_integration",
            "delete_channel_integration",
            // HITL approval surface (McpHitlTools)
            "list_pending_approvals", "get_approval_status", "resume_conversation",
            "cancel_conversation", "list_group_pending_approvals",
            "list_all_group_pending_approvals", "get_group_approval_status",
            "approve_group_phase", "cancel_group_discussion",
            // Persistent user memory (McpMemoryTools)
            "list_user_memories", "get_visible_memories", "search_user_memories",
            "get_memory_by_key", "upsert_user_memory", "delete_user_memory",
            "delete_all_user_memories", "count_user_memories",
            // GDPR/CCPA (McpGdprTools)
            "delete_user_data", "export_user_data"
    })
    void test_whitelistedTools_returnsTrue(String toolName) {
        var toolInfo = mock(ToolInfo.class);
        when(toolInfo.name()).thenReturn(toolName);
        assertTrue(filter.test(toolInfo, (FilterContext) null));
    }

    // --- Non-whitelisted tools (langchain4j built-in Agent tools) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "calculate", "get_current_date_time", "search_web",
            "scrape_url", "summarize_text", "read_pdf",
            "format_data", "get_weather", "unknown_tool",
            "recall_conversation", "store_user_memory"
    })
    void test_nonWhitelistedTools_returnsFalse(String toolName) {
        var toolInfo = mock(ToolInfo.class);
        when(toolInfo.name()).thenReturn(toolName);
        assertFalse(filter.test(toolInfo, (FilterContext) null));
    }

    // --- Edge cases ---

    @Test
    void test_nullToolName_throwsNpe() {
        var toolInfo = mock(ToolInfo.class);
        when(toolInfo.name()).thenReturn(null);
        assertThrows(NullPointerException.class,
                () -> filter.test(toolInfo, (FilterContext) null));
    }

    @Test
    void test_emptyToolName_returnsFalse() {
        var toolInfo = mock(ToolInfo.class);
        when(toolInfo.name()).thenReturn("");
        assertFalse(filter.test(toolInfo, (FilterContext) null));
    }

    @Test
    void test_caseSensitive_wrongCase_returnsFalse() {
        var toolInfo = mock(ToolInfo.class);
        when(toolInfo.name()).thenReturn("List_Agents");
        assertFalse(filter.test(toolInfo, (FilterContext) null));
    }

    // --- Structural regression: every quarkus-MCP @Tool must be whitelisted ---

    /**
     * Auto-discovers <em>every</em> class in the {@code ai.labs.eddi.engine.mcp}
     * package (by scanning the compiled-classes directory), collects each method
     * annotated with the quarkus MCP {@code @Tool}, resolves its effective tool
     * name (explicit {@code name} or, when defaulted, the method name), and
     * verifies each one is present in the {@link McpToolFilter} whitelist.
     * <p>
     * This is the guard that prevents the class of bug where a new
     * {@code Mcp*Tools} class (or a new method on an existing one) ships with a
     * {@code @Tool} annotation but is never added to {@code MCP_TOOLS} — making the
     * tool invisible to MCP clients despite being implemented. Because a
     * quarkus-MCP {@code @Tool} has <em>no other</em> invocation path, a
     * non-whitelisted one is unreachable dead code: it must be either whitelisted
     * (usable) or removed.
     * <p>
     * Discovery is intentionally reflection/filesystem-based (not a hardcoded class
     * list) so that adding a brand-new MCP tool class is automatically covered
     * without touching this test.
     */
    @Test
    void test_allMcpToolMethods_areWhitelisted() {
        Map<String, String> declaredToolToClass = new LinkedHashMap<>();
        for (Class<?> clazz : discoverClassesUnder(McpToolFilter.class.getPackageName(), false)) {
            for (Method method : clazz.getDeclaredMethods()) {
                var toolAnnotation = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
                if (toolAnnotation == null) {
                    continue;
                }
                String annotatedName = toolAnnotation.name();
                // Quarkus MCP uses "<<element name>>" as the sentinel default when no
                // explicit name is set — fall back to the method name (this is how
                // McpGroupTools names its tools).
                String name = (annotatedName.isEmpty() || annotatedName.startsWith("<<"))
                        ? method.getName()
                        : annotatedName;
                declaredToolToClass.put(name, clazz.getSimpleName());
            }
        }

        assertFalse(declaredToolToClass.isEmpty(),
                "Discovery found no @Tool methods in package " + McpToolFilter.class.getPackageName()
                        + " — the classpath scan is broken");

        // Guard the discovery itself: each anchor lives in a distinct Mcp*Tools
        // class, so their combined presence proves the scan reached every known
        // tool class. If a refactor breaks discovery, this fails loudly rather than
        // passing vacuously.
        Set<String> anchors = new LinkedHashSet<>(List.of(
                "list_agents", // McpConversationTools
                "deploy_agent", // McpAdminTools
                "setup_agent", // McpSetupTools
                "describe_discussion_styles", // McpGroupTools
                "resume_conversation", // McpHitlTools
                "list_user_memories", // McpMemoryTools
                "delete_user_data")); // McpGdprTools
        Set<String> missingAnchors = new LinkedHashSet<>(anchors);
        missingAnchors.removeAll(declaredToolToClass.keySet());
        assertTrue(missingAnchors.isEmpty(),
                "MCP tool discovery did not reach these anchor tools (the scan is incomplete): " + missingAnchors);

        // Every discovered @Tool must pass the whitelist filter.
        Map<String, String> missing = new LinkedHashMap<>();
        for (var entry : declaredToolToClass.entrySet()) {
            var toolInfo = mock(ToolInfo.class);
            when(toolInfo.name()).thenReturn(entry.getKey());
            if (!filter.test(toolInfo, (FilterContext) null)) {
                missing.put(entry.getKey(), entry.getValue());
            }
        }

        assertTrue(missing.isEmpty(),
                "The following quarkus-MCP @Tool methods are NOT whitelisted in McpToolFilter. A non-whitelisted "
                        + "MCP tool is unreachable dead code — either add it to the MCP_TOOLS set (to make it usable) "
                        + "or remove the tool. Missing {toolName=class}: " + missing);
    }

    /**
     * The inverse guard: no INTERNAL langchain4j agent tool
     * ({@code dev.langchain4j.agent.tool.Tool}) may share a name with a whitelisted
     * MCP tool. Those tools are meant for agent-pipeline execution only, but the
     * {@code ToolFilter} keys purely on the tool NAME — so a name collision would
     * expose an internal tool (e.g. arbitrary configured-httpcall execution via
     * {@code EddiToolBridge}) to external MCP clients. This turns the one-time
     * manual "no collision" check into a build-time invariant: rename either side
     * or the build goes red.
     * <p>
     * Effective names are structurally disjoint today (built-ins use camelCase
     * method names, MCP tools use snake_case), but a future snake_case
     * {@code @Tool(name = ...)} or renamed method could collide silently without
     * this guard.
     */
    @Test
    void test_noLangchain4jBuiltinToolIsWhitelisted() {
        Map<String, String> builtinTools = new LinkedHashMap<>();
        for (Class<?> clazz : discoverClassesUnder("ai.labs.eddi.modules.llm.tools", true)) {
            for (Method method : clazz.getDeclaredMethods()) {
                var tool = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                if (tool == null) {
                    continue;
                }
                // langchain4j: @Tool value() is the description; name() defaults to ""
                // and the effective tool name is then the method name.
                String explicit = tool.name();
                String name = (explicit == null || explicit.isEmpty()) ? method.getName() : explicit;
                builtinTools.put(name, clazz.getSimpleName());
            }
        }

        assertFalse(builtinTools.isEmpty(),
                "Discovery found no langchain4j @Tool methods under modules.llm.tools — the scan is broken");

        // Guard the scan against a vacuous pass — these are real built-in tool names.
        Set<String> anchors = new LinkedHashSet<>(List.of("calculate", "searchWeb", "rememberFact"));
        Set<String> missingAnchors = new LinkedHashSet<>(anchors);
        missingAnchors.removeAll(builtinTools.keySet());
        assertTrue(missingAnchors.isEmpty(),
                "langchain4j tool discovery is incomplete (missing anchors): " + missingAnchors);

        Map<String, String> leaked = new LinkedHashMap<>();
        for (var entry : builtinTools.entrySet()) {
            var toolInfo = mock(ToolInfo.class);
            when(toolInfo.name()).thenReturn(entry.getKey());
            if (filter.test(toolInfo, (FilterContext) null)) {
                leaked.put(entry.getKey(), entry.getValue());
            }
        }

        assertTrue(leaked.isEmpty(),
                "These internal langchain4j agent tools share a name with a whitelisted MCP tool and would be "
                        + "exposed to external MCP clients — rename the tool or remove the whitelist entry. "
                        + "{toolName=class}: " + leaked);
    }

    /**
     * Enumerates the classes under a package by scanning the compiled
     * {@code .class} files next to {@link McpToolFilter} (which resolves to
     * {@code target/classes/...} under a Maven/Surefire run with exploded output).
     * Classes are loaded WITHOUT running their static initializers
     * ({@code Class.forName(name, false, ...)}) — reflecting over annotations needs
     * no initialization, so the scan is free of any bean/config static-init side
     * effects.
     *
     * @param pkg
     *            fully-qualified package name to scan
     * @param recursive
     *            whether to descend into sub-packages
     */
    private static List<Class<?>> discoverClassesUnder(String pkg, boolean recursive) {
        var codeSource = McpToolFilter.class.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource,
                "Cannot locate the compiled-classes code source to scan for tools");
        Path classesRoot;
        try {
            classesRoot = Paths.get(codeSource.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid classes-root URI: " + codeSource.getLocation(), e);
        }
        Path pkgDir = classesRoot.resolve(pkg.replace('.', '/'));
        assertTrue(Files.isDirectory(pkgDir),
                "Expected the compiled package directory at " + pkgDir
                        + " (run under `mvn test` with exploded classes)");

        List<Class<?>> classes = new ArrayList<>();
        try (var paths = recursive ? Files.walk(pkgDir) : Files.list(pkgDir)) {
            paths.filter(Files::isRegularFile)
                    .map(p -> classesRoot.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> rel.endsWith(".class"))
                    .map(rel -> rel.substring(0, rel.length() - ".class".length()).replace('/', '.'))
                    // Skip nested/inner classes ($) and package-info — tool methods are top-level.
                    .filter(fqcn -> !fqcn.contains("$") && !fqcn.endsWith("package-info"))
                    .sorted()
                    .forEach(fqcn -> {
                        try {
                            classes.add(Class.forName(fqcn, false, McpToolFilterTest.class.getClassLoader()));
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException("Cannot load discovered class " + fqcn, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan package directory " + pkgDir, e);
        }
        return classes;
    }
}
