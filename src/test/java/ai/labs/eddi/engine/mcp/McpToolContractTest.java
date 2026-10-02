/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The argument contract every MCP tool publishes in {@code tools/list}.
 * <p>
 * quarkus-mcp-server 2.x makes a {@code @ToolArg} <b>required unless it says
 * {@code required = false}</b>, and it rejects a call that omits a required
 * argument (or passes {@code null}) with "Missing required argument" before the
 * method runs. Until this test existed, 75 of the 84 tools declared every
 * argument required — 100 arguments whose own descriptions said "optional" or
 * "default: …" were mandatory, so {@code list_agents {}} failed and
 * {@code chat_with_agent} could not be called without a conversationId it
 * promised to create. The schema is generated from exactly these annotations,
 * so reading them is reading the published contract.
 */
class McpToolContractTest {

    /** The same list {@code McpToolFilterCoverageTest} pins against the package. */
    private static final Class<?>[] TOOL_CLASSES = {
            McpAdminTools.class, McpConversationTools.class, McpSetupTools.class,
            McpGroupTools.class, McpHitlTools.class, McpMemoryTools.class,
            McpGdprTools.class, McpDocTools.class};

    /** Words that promise the caller may leave the argument out. */
    private static final Pattern SAYS_OPTIONAL = Pattern.compile(
            "\\(optional|optional\\)|\\boptional[;,]|\\(default|default:|defaults to|\\(deprecated|deprecated —|if omitted|when omitted|omit for",
            Pattern.CASE_INSENSITIVE);

    /**
     * An unconditional "(required)" — "required for CRON" is conditional and fine.
     */
    private static final Pattern SAYS_REQUIRED = Pattern.compile("\\(required\\)", Pattern.CASE_INSENSITIVE);

    record Arg(String tool, String name, ToolArg annotation) {
        @Override
        public String toString() {
            return tool + "." + name;
        }
    }

    static List<Method> tools() {
        var out = new ArrayList<Method>();
        for (Class<?> c : TOOL_CLASSES) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Tool.class)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    static String toolName(Method m) {
        String name = m.getAnnotation(Tool.class).name();
        return name == null || name.isEmpty() || Tool.ELEMENT_NAME.equals(name) ? m.getName() : name;
    }

    static List<Arg> args() {
        var out = new ArrayList<Arg>();
        for (Method m : tools()) {
            for (Parameter p : m.getParameters()) {
                ToolArg a = p.getAnnotation(ToolArg.class);
                if (a != null) {
                    out.add(new Arg(toolName(m), p.getName(), a));
                }
            }
        }
        return out;
    }

    private static boolean hasDefault(ToolArg a) {
        return !a.defaultValue().isEmpty();
    }

    @Test
    @DisplayName("84 tools across the 8 tool classes — the count tools/list publishes")
    void toolCount() {
        assertEquals(84, tools().size(), "a tool was added or removed — update this count, McpToolFilter and docs/mcp-server.md");
        assertEquals(84, McpToolFilter.MCP_TOOLS.size());
    }

    @Test
    @DisplayName("an argument described as optional, defaulted or deprecated is not required in the schema")
    void documentedOptionalArgumentsAreNotRequired() {
        var violations = args().stream()
                .filter(a -> a.annotation().required() && SAYS_OPTIONAL.matcher(a.annotation().description()).find())
                .map(Arg::toString)
                .toList();
        assertTrue(violations.isEmpty(), "Described as optional/defaulted but required in tools/list (null would be rejected): " + violations);
    }

    @Test
    @DisplayName("an argument that is not required never calls itself '(required)'")
    void optionalArgumentsDoNotClaimToBeRequired() {
        var violations = args().stream()
                .filter(a -> !a.annotation().required() && SAYS_REQUIRED.matcher(a.annotation().description()).find())
                .map(Arg::toString)
                .toList();
        assertTrue(violations.isEmpty(), "required=false but described as '(required)': " + violations);
    }

    @Test
    @DisplayName("an argument with a defaultValue is optional, and its description names that default")
    void defaultValuesAreOptionalAndDocumented() {
        var violations = new ArrayList<String>();
        for (Arg a : args()) {
            if (!hasDefault(a.annotation())) {
                continue;
            }
            if (a.annotation().required()) {
                violations.add(a + ": has a defaultValue but is required");
            }
            String desc = a.annotation().description().toLowerCase(Locale.ROOT);
            String dv = a.annotation().defaultValue().toLowerCase(Locale.ROOT);
            if (!desc.contains("default") || !desc.contains(dv)) {
                violations.add(a + ": defaultValue '" + a.annotation().defaultValue() + "' is not the default the description states");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    @DisplayName("no description repeats an option (\"'production' (default), 'production', or 'test'\")")
    void noDuplicatedEnvironmentOption() {
        var violations = args().stream()
                .filter(a -> a.annotation().description().contains("'production' (default), 'production'"))
                .map(Arg::toString)
                .toList();
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @Test
    @DisplayName("every environment argument is optional and defaults to production")
    void environmentArgumentsDefaultToProduction() {
        var violations = args().stream()
                .filter(a -> a.name().equals("environment"))
                .filter(a -> a.annotation().required())
                .map(Arg::toString)
                .toList();
        assertTrue(violations.isEmpty(), "environment is optional everywhere (absent = production): " + violations);
    }

    @Test
    @DisplayName("the arguments a tool cannot work without stay required")
    void essentialArgumentsStayRequired() {
        // Spot checks against over-correction: these are identifiers and payloads, and
        // relaxing them would only move the failure from the schema into the tool.
        var mustBeRequired = List.of("chat_with_agent.agentId", "chat_with_agent.message", "talk_to_agent.conversationId",
                "get_agent.agentId", "deploy_agent.agentId", "deploy_agent.version", "update_resource.config",
                "create_group.memberAgentIds", "resume_conversation.verdict", "delete_user_data.confirmation",
                "setup_agent.agentName", "setup_agent.systemPrompt", "read_docs.name");
        var all = args();
        for (String key : mustBeRequired) {
            var arg = all.stream().filter(a -> a.toString().equals(key)).findFirst().orElseThrow(() -> new AssertionError("no such argument " + key));
            assertTrue(arg.annotation().required(), key + " must stay required");
        }
    }

    @Test
    @DisplayName("the optional arguments the review found mandatory are optional now")
    void reviewedArgumentsAreOptional() {
        var mustBeOptional = List.of("list_agents.environment", "chat_with_agent.conversationId", "chat_with_agent.environment",
                "get_agent.version", "list_agent_configs.filter", "list_agent_configs.limit", "read_conversation.agentId",
                "read_conversation.returningFields", "setup_agent.provider", "setup_agent.apiKey", "setup_agent.deploy",
                "create_group.style", "create_group.maxTurns", "resume_conversation.note", "list_pending_approvals.limit");
        var all = args();
        for (String key : mustBeOptional) {
            var arg = all.stream().filter(a -> a.toString().equals(key)).findFirst().orElseThrow(() -> new AssertionError("no such argument " + key));
            assertTrue(!arg.annotation().required(), key + " must be optional");
        }
    }

    @Test
    @DisplayName("every tool class reports failures as MCP tool errors (isError: true)")
    void everyToolClassMapsErrorsToIsError() {
        for (Class<?> c : TOOL_CLASSES) {
            assertTrue(c.isAnnotationPresent(McpErrorResults.class), c.getSimpleName() + " lacks @McpErrorResults");
        }
    }
}
