/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.mcp.McpApiToolBuilder;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The setup wizard appends a generated OpenAPI endpoint summary to the agent's
 * system prompt, and {@code LlmTask} renders that prompt as a Qute template on
 * every turn. An OpenAPI path parameter is valid Qute — {@code /docs/{name}}
 * <em>is</em> <code>{name}</code> — so the summary has to be escaped before it
 * is concatenated.
 * <p>
 * Unescaped, the render failed on <em>every</em> turn with
 * {@code Key "name" not
 * found in the template data map}, pointing at a key nobody wrote in a prompt
 * fragment its author never saw. {@code LlmTask} logs that per parameter and
 * keeps the raw value, so the failure was not clean: the model got the entire
 * system prompt unrendered, caller's own <code>{#if}</code> sections and all.
 * That is what these tests pin.
 */
@DisplayName("Setup wizard system prompt is safe to render as a template")
class SetupPromptTemplateSafetyTest {

    /**
     * Shaped after EDDI's own spec, which the Platform Operator is provisioned
     * from: a {@code {name}} path parameter (from {@code /administration/docs}) and
     * an {@code {id}} one (from the config stores).
     */
    private static final String SPEC_WITH_PATH_PARAMS = """
            {
              "openapi": "3.0.3",
              "info": { "title": "EDDI API", "version": "1.0.0" },
              "servers": [{ "url": "https://eddi.example.com" }],
              "paths": {
                "/administration/docs/{name}": {
                  "get": {
                    "operationId": "readDoc",
                    "summary": "Read a documentation page",
                    "tags": ["docs"],
                    "parameters": [
                      { "name": "name", "in": "path", "required": true, "schema": { "type": "string" } }
                    ]
                  }
                },
                "/groupstore/groups/{id}": {
                  "get": {
                    "operationId": "readGroup",
                    "summary": "Read an agent group",
                    "tags": ["groups"],
                    "parameters": [
                      { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } }
                    ]
                  }
                }
              }
            }
            """;

    /** The kind of prompt the Manager sends: a live template of its own. */
    private static final String CALLER_PROMPT = """
            You are the EDDI Platform Operator.
            {#if context.screen}The administrator is currently viewing: {context.screen}.{/if}""";

    private static String apiSummary() {
        return McpApiToolBuilder.parseAndBuild(SPEC_WITH_PATH_PARAMS, "", "https://eddi.example.com", null).apiSummary();
    }

    private static String render(Engine engine, String template) throws Exception {
        ITemplatingEngine templatingEngine = new TemplatingEngine(engine);
        return templatingEngine.processTemplate(template, Map.of("context", Map.of("screen", "Agents")), TemplateMode.TEXT);
    }

    @Test
    @DisplayName("the generated summary really does carry Qute-significant braces")
    void summaryContainsPathParameters() {
        String summary = apiSummary();
        assertTrue(summary.contains("/administration/docs/{name}"), summary);
        assertTrue(summary.contains("/groupstore/groups/{id}"), summary);
    }

    /**
     * A strict engine is Qute's default: the render throws rather than quietly
     * dropping the expression. What the caller does with that throw is a separate
     * question — {@code LlmTask} logs and keeps the raw prompt — but at this level
     * the failure is loud, and this is the shape the production log showed.
     */
    @Nested
    @DisplayName("with a strict engine (Qute's default)")
    class Strict {

        private final Engine engine = Engine.builder().addDefaults().build();

        @Test
        @DisplayName("raw concatenation aborts the render — the shipped defect")
        void rawConcatenationThrows() {
            String unescaped = CALLER_PROMPT + "\n\n" + apiSummary();
            var e = assertThrows(ITemplatingEngine.TemplateEngineException.class, () -> render(engine, unescaped));
            // Assert on the CAUSE, not the whole message: the message appends a preview
            // of the template, which itself contains the literal "{name}" — so a bare
            // contains("name") would pass for any failure at all.
            String cause = e.getMessage().substring(0, e.getMessage().indexOf(" | Template preview:"));
            assertTrue(cause.contains("Key \"name\" not found"), cause);
        }

        @Test
        @DisplayName("the enriched prompt renders, keeping the paths literal and the caller's template live")
        void enrichedPromptRenders() throws Exception {
            String rendered = assertDoesNotThrow(() -> render(engine, AgentSetupService.enrichSystemPrompt(CALLER_PROMPT, apiSummary())));

            assertTrue(rendered.contains("/administration/docs/{name}"), rendered);
            assertTrue(rendered.contains("/groupstore/groups/{id}"), rendered);
            // The caller's own half is still evaluated — escaping is one-sided.
            assertTrue(rendered.contains("currently viewing: Agents."), rendered);
            assertTrue(rendered.startsWith("You are the EDDI Platform Operator."), rendered);
            assertFalse(rendered.contains("{|"), "escape delimiters must not survive into the prompt: " + rendered);
        }
    }

    /**
     * Pinned separately because a lenient engine turns the same defect from an
     * exception into silent corruption — the endpoint the agent was told about
     * simply loses its path parameter. Asserting on both means this test keeps its
     * meaning if {@code quarkus.qute.strict-rendering} or the property-not-found
     * strategy is ever changed.
     */
    @Nested
    @DisplayName("with a lenient engine")
    class Lenient {

        private final Engine engine = Engine.builder().addDefaults().strictRendering(false).build();

        @Test
        @DisplayName("raw concatenation silently drops the path parameter")
        void rawConcatenationCorruptsPaths() throws Exception {
            String rendered = render(engine, CALLER_PROMPT + "\n\n" + apiSummary());
            assertTrue(rendered.contains("/administration/docs/"), rendered);
            assertFalse(rendered.contains("/administration/docs/{name}"), rendered);
        }

        @Test
        @DisplayName("the enriched prompt keeps the path parameter")
        void enrichedPromptKeepsPaths() throws Exception {
            String rendered = render(engine, AgentSetupService.enrichSystemPrompt(CALLER_PROMPT, apiSummary()));
            assertTrue(rendered.contains("/administration/docs/{name}"), rendered);
            assertFalse(rendered.contains("{|"), rendered);
        }
    }

    /**
     * The escape must not become a leak of its own.
     * <p>
     * A spec with no path parameters produces a summary with no Qute markers, and a
     * caller may well send a prompt with none either — at which point the wrapped
     * template contains nothing that looks like a control character except the
     * wrapper itself. {@code TemplatingEngine} skips such templates as "no
     * templating needed", so the delimiters shipped verbatim into the system
     * prompt. The engine's control-character pattern now counts <code>{|</code>;
     * this is the case that proves it, from the wizard's own entry point.
     */
    @Nested
    @DisplayName("when nothing in the prompt looks like a template")
    class NoMarkersAnywhere {

        private static final String SPEC_WITHOUT_PATH_PARAMS = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Simple API", "version": "1.0.0" },
                  "servers": [{ "url": "https://simple.example.com" }],
                  "paths": {
                    "/pets": { "get": { "operationId": "listPets", "summary": "List all pets", "tags": ["pets"] } }
                  }
                }
                """;

        @Test
        @DisplayName("the escape delimiters do not reach the model")
        void delimitersAreStripped() throws Exception {
            String summary = McpApiToolBuilder.parseAndBuild(SPEC_WITHOUT_PATH_PARAMS, "", "https://simple.example.com", null).apiSummary();
            assertFalse(summary.contains("{"), "fixture must have no markers of its own: " + summary);

            String enriched = AgentSetupService.enrichSystemPrompt("You are a plain assistant with no template markers.", summary);
            String rendered = render(Engine.builder().addDefaults().build(), enriched);

            assertFalse(rendered.contains("{|"), "unparsed-block opener leaked into the prompt: " + rendered);
            assertFalse(rendered.contains("|}"), "unparsed-block terminator leaked into the prompt: " + rendered);
            assertTrue(rendered.contains("GET /pets"), rendered);
            assertTrue(rendered.startsWith("You are a plain assistant"), rendered);
        }
    }
}
