/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;

import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.Tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that all MCP-exposed tool parameter names conform to the JSON
 * schema property key pattern required by LLM API providers.
 *
 * <p>
 * The MCP protocol exposes tool parameters as JSON schema properties. Providers
 * like Claude require property keys to match {@code ^[a-zA-Z0-9_.-]{1,64}$}.
 * For the Quarkus MCP tools that key is the Java parameter name of each
 * {@code @ToolArg}. The langchain4j built-ins are not MCP tools at all — see
 * {@link #langchain4jToolDiscoveryIsDisabledForTheMcpServer()} — so their
 * {@code @P} values are free to be descriptions, and must be.
 *
 * @see <a href=
 *      "https://docs.anthropic.com/en/docs/build-with-claude/tool-use">Claude
 *      Tool Use</a>
 */
class McpToolSchemaValidationTest {

    /**
     * Pattern that MCP property keys must match. Only alphanumeric characters,
     * underscores, dots, and hyphens are allowed. Maximum length is 64 characters.
     */
    private static final Pattern VALID_PROPERTY_KEY = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

    /**
     * All classes that expose @Tool methods (langchain4j or Quarkus MCP). Add any
     * new tool class here to include it in validation.
     */
    private static final Class<?>[] TOOL_CLASSES = {
            // Built-in langchain4j tools (use @P for parameter names)
            CalculatorTool.class, DataFormatterTool.class,
            DateTimeTool.class, PdfReaderTool.class,
            TextSummarizerTool.class, WeatherTool.class,
            WebScraperTool.class, WebSearchTool.class,

            // MCP tools (use @ToolArg — Java parameter names become keys)
            McpConversationTools.class, McpAdminTools.class, McpSetupTools.class,};

    /**
     * The langchain4j built-ins must never be discovered by the MCP server.
     * <p>
     * quarkus-mcp-server registers {@code dev.langchain4j} {@code @Tool} beans as
     * MCP tools unless {@code quarkus.mcp.server.support-langchain4j-annotations}
     * is false, and when it does it reads each langchain4j {@code @P} value as the
     * MCP argument <em>name</em>. That is why this test used to forbid any
     * {@code @P} value that was not an identifier — and why every built-in tool
     * told the model nothing about its parameters ({@code @P("expression")}). With
     * the discovery switched off, {@code @P} is what langchain4j means it to be:
     * the parameter's description.
     */
    @Test
    void langchain4jToolDiscoveryIsDisabledForTheMcpServer() throws Exception {
        Properties properties = new Properties();
        // The production file itself: the test classpath carries its own
        // application.properties, which would shadow it.
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(in);
        }
        assertEquals("false", properties.getProperty("quarkus.mcp.server.support-langchain4j-annotations"),
                "the built-in langchain4j tools must not be registered as MCP tools");
    }

    /**
     * Every built-in tool parameter carries a real description for the model, not
     * its own name repeated.
     */
    @Test
    void builtInToolParameters_P_annotations_describeTheParameter() {
        List<String> violations = new ArrayList<>();

        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                for (Parameter param : method.getParameters()) {
                    P pAnnotation = param.getAnnotation(P.class);
                    if (pAnnotation == null) {
                        violations.add(String.format("  %s.%s — parameter '%s' has no @P description", toolClass.getSimpleName(),
                                method.getName(), param.getName()));
                    } else if (pAnnotation.value().isBlank() || pAnnotation.value().equalsIgnoreCase(param.getName())
                            || !pAnnotation.value().contains(" ")) {
                        violations.add(String.format("  %s.%s — @P(\"%s\") only repeats the parameter name", toolClass.getSimpleName(),
                                method.getName(), pAnnotation.value()));
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "Built-in tool parameters without a real description:\n\n" + String.join("\n", violations));
    }

    /**
     * Validates that every @ToolArg-annotated parameter on Quarkus MCP @Tool
     * methods has a valid Java parameter name (which becomes the schema key).
     */
    @Test
    void allToolParameters_ToolArg_javaNamesAreValidPropertyKeys() {
        List<String> violations = new ArrayList<>();

        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                // Check methods with Quarkus MCP @Tool annotation
                if (!method.isAnnotationPresent(io.quarkiverse.mcp.server.Tool.class)) {
                    continue;
                }

                for (Parameter param : method.getParameters()) {
                    if (param.isAnnotationPresent(io.quarkiverse.mcp.server.ToolArg.class)) {
                        String paramName = param.getName();
                        if (!VALID_PROPERTY_KEY.matcher(paramName).matches()) {
                            violations.add(String.format("  %s.%s — parameter '%s' is not a valid MCP property key.%n" + "    Keys must match: %s",
                                    toolClass.getSimpleName(), method.getName(), paramName, VALID_PROPERTY_KEY.pattern()));
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "Found @ToolArg parameters with invalid MCP property keys " + "(must match " + VALID_PROPERTY_KEY.pattern()
                + "):\n\n" + String.join("\n\n", violations));
    }

    /**
     * Verify the regex pattern correctly accepts and rejects known values.
     */
    @Test
    void validPropertyKeyPattern_acceptsValidKeys() {
        assertTrue(VALID_PROPERTY_KEY.matcher("expression").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("agentId").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("api_key").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("model.name").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("my-param").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("a").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("maxResults").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("cssSelector").matches());
    }

    @Test
    void validPropertyKeyPattern_rejectsInvalidKeys() {
        // Spaces
        assertFalse(VALID_PROPERTY_KEY.matcher("URL of the web page").matches());
        assertFalse(VALID_PROPERTY_KEY.matcher("City name").matches());

        // Parentheses and special characters
        assertFalse(VALID_PROPERTY_KEY.matcher("City name (e.g., 'London')").matches());
        assertFalse(VALID_PROPERTY_KEY.matcher("Mathematical expression (e.g., '2 + 2')").matches());

        // Slashes
        assertFalse(VALID_PROPERTY_KEY.matcher("Date/time in ISO format").matches());

        // Quotes
        assertFalse(VALID_PROPERTY_KEY.matcher("CSS selector (e.g., 'h1')").matches());

        // Empty
        assertFalse(VALID_PROPERTY_KEY.matcher("").matches());

        // Too long (65 chars)
        assertFalse(VALID_PROPERTY_KEY.matcher("a".repeat(65)).matches());
    }

    private static String truncate(String s, int maxLength) {
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }
}
