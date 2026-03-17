package ai.labs.eddi.engine.mcp;

import dev.langchain4j.agent.tool.P;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that all MCP-exposed tool parameter names conform to the
 * JSON schema property key pattern required by LLM API providers.
 *
 * <p>The MCP protocol exposes tool parameters as JSON schema properties.
 * Providers like Claude require property keys to match {@code ^[a-zA-Z0-9_.-]{1,64}$}.
 * The Quarkus MCP server uses {@code @P} annotation values from langchain4j tools
 * as property keys in the generated schema.
 *
 * <p>This test prevents regressions where descriptive sentences in {@code @P} annotations
 * (e.g., "URL of the web page") are used as schema keys, breaking MCP client compatibility.
 *
 * @see <a href="https://docs.anthropic.com/en/docs/build-with-claude/tool-use">Claude Tool Use</a>
 */
class McpToolSchemaValidationTest {

    /**
     * Pattern that MCP property keys must match.
     * Only alphanumeric characters, underscores, dots, and hyphens are allowed.
     * Maximum length is 64 characters.
     */
    private static final Pattern VALID_PROPERTY_KEY = Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

    /**
     * All classes that expose @Tool methods (langchain4j or Quarkus MCP).
     * Add any new tool class here to include it in validation.
     */
    private static final Class<?>[] TOOL_CLASSES = {
            // Built-in langchain4j tools (use @P for parameter names)
            ai.labs.eddi.modules.langchain.tools.impl.CalculatorTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.DataFormatterTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.DateTimeTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.PdfReaderTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.TextSummarizerTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.WeatherTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.WebScraperTool.class,
            ai.labs.eddi.modules.langchain.tools.impl.WebSearchTool.class,
            ai.labs.eddi.modules.langchain.tools.EddiToolBridge.class,

            // MCP tools (use @ToolArg — Java parameter names become keys)
            McpConversationTools.class,
            McpAdminTools.class,
            McpSetupTools.class,
    };

    /**
     * Validates that every @P annotation value across all tool classes
     * is a valid MCP property key (no spaces, special characters, or excessive length).
     *
     * <p>The Quarkus MCP server uses the @P annotation value as the JSON schema
     * property key. If a @P value contains spaces or special characters,
     * MCP clients like Claude will reject the tool definition with HTTP 400.
     */
    @Test
    void allToolParameters_P_annotations_mustBeValidPropertyKeys() {
        List<String> violations = new ArrayList<>();

        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                // Check methods with langchain4j @Tool annotation
                if (!method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    continue;
                }

                for (Parameter param : method.getParameters()) {
                    P pAnnotation = param.getAnnotation(P.class);
                    if (pAnnotation != null) {
                        String propertyKey = pAnnotation.value();
                        if (!VALID_PROPERTY_KEY.matcher(propertyKey).matches()) {
                            violations.add(String.format(
                                    "  %s.%s — @P(\"%s\") is not a valid MCP property key.%n" +
                                            "    Keys must match: %s%n" +
                                            "    Suggestion: use @P(\"%s\") instead.",
                                    toolClass.getSimpleName(),
                                    method.getName(),
                                    truncate(propertyKey, 60),
                                    VALID_PROPERTY_KEY.pattern(),
                                    param.getName()));
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Found @P annotations with invalid MCP property keys " +
                        "(must match " + VALID_PROPERTY_KEY.pattern() + "):\n\n" +
                        String.join("\n\n", violations));
    }

    /**
     * Validates that every @ToolArg-annotated parameter on Quarkus MCP @Tool methods
     * has a valid Java parameter name (which becomes the schema key).
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
                            violations.add(String.format(
                                    "  %s.%s — parameter '%s' is not a valid MCP property key.%n" +
                                            "    Keys must match: %s",
                                    toolClass.getSimpleName(),
                                    method.getName(),
                                    paramName,
                                    VALID_PROPERTY_KEY.pattern()));
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Found @ToolArg parameters with invalid MCP property keys " +
                        "(must match " + VALID_PROPERTY_KEY.pattern() + "):\n\n" +
                        String.join("\n\n", violations));
    }

    /**
     * Verify the regex pattern correctly accepts and rejects known values.
     */
    @Test
    void validPropertyKeyPattern_acceptsValidKeys() {
        assertTrue(VALID_PROPERTY_KEY.matcher("expression").matches());
        assertTrue(VALID_PROPERTY_KEY.matcher("botId").matches());
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
