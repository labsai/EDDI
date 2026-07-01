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

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
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
            "delete_channel_integration"
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

    // --- Structural regression: all Mcp* @Tool methods must be whitelisted ---

    /**
     * Scans all {@code Mcp*} classes in the MCP package for methods annotated with
     * {@code @Tool(name = "...")} and verifies every declared tool name is present
     * in the {@link McpToolFilter} whitelist.
     * <p>
     * This prevents the common regression where a new MCP tool is added to a
     * {@code Mcp*Tools} class but the developer forgets to register it in the
     * filter — making the tool invisible to MCP clients.
     */
    @Test
    void test_allMcpToolMethods_areWhitelisted() {
        // Collect all @Tool-annotated method names from Mcp* classes
        Set<String> declaredTools = new LinkedHashSet<>();
        Class<?>[] mcpClasses = {
                McpConversationTools.class,
                McpAdminTools.class,
                McpSetupTools.class,
                McpGroupTools.class,
        };

        for (Class<?> clazz : mcpClasses) {
            for (Method method : clazz.getDeclaredMethods()) {
                var toolAnnotation = method.getAnnotation(
                        io.quarkiverse.mcp.server.Tool.class);
                if (toolAnnotation != null) {
                    String annotatedName = toolAnnotation.name();
                    // Quarkus MCP uses "<<element name>>" as the sentinel default
                    // when no explicit name is set — fall back to method name.
                    String name = (annotatedName.isEmpty()
                            || annotatedName.startsWith("<<"))
                                    ? method.getName()
                                    : annotatedName;
                    declaredTools.add(name);
                }
            }
        }

        assertFalse(declaredTools.isEmpty(),
                "Should find at least one @Tool method in Mcp* classes");

        // Verify each declared tool passes the filter
        Set<String> missingTools = new LinkedHashSet<>();
        for (String toolName : declaredTools) {
            var toolInfo = mock(ToolInfo.class);
            when(toolInfo.name()).thenReturn(toolName);
            if (!filter.test(toolInfo, (FilterContext) null)) {
                missingTools.add(toolName);
            }
        }

        assertTrue(missingTools.isEmpty(),
                "The following @Tool methods in Mcp* classes are NOT whitelisted in McpToolFilter. "
                        + "Add them to the MCP_TOOLS set: " + missingTools);
    }
}
