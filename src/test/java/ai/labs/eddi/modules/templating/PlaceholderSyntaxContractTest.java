/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the placeholder syntax the engine actually honours.
 * <p>
 * This exists because the documentation for two shipped features — Prompt
 * Snippets and Global Variables — was written entirely in {@code {{name}}}
 * form, which Qute does <em>not</em> resolve: it passes straight through to the
 * model as literal text. An agent author copying the guide got the placeholder
 * in their prompt rather than the value, and nothing failed to tell them so.
 * <p>
 * Single braces are the contract. If someone later switches the engine or adds
 * a pre-processor that changes this, these assertions should be what stops
 * them.
 */
@DisplayName("Template placeholder syntax contract")
public class PlaceholderSyntaxContractTest {

    private ITemplatingEngine templatingEngine;

    @BeforeEach
    public void setUp() {
        templatingEngine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
    }

    private String render(String template) throws Exception {
        return templatingEngine.processTemplate(template, Map.of(
                "snippets", Map.of("greeting", "HELLO"),
                "vars", Map.of("model", "gpt-4o-mini", "default-model", "claude-sonnet"),
                "properties", Map.of("company_name", "ACME")), TemplateMode.TEXT);
    }

    @Test
    @DisplayName("single braces resolve — this is the documented and supported form")
    void singleBracesResolve() throws Exception {
        assertEquals("HELLO", render("{snippets.greeting}"));
        assertEquals("gpt-4o-mini", render("{vars.model}"));
        assertEquals("ACME", render("{properties.company_name}"));
    }

    /**
     * Hyphens are not legal Java identifier characters, so dot notation on a key
     * like {@code default-model} looks like it should fail (or parse as a
     * subtraction). It does not: Qute resolves the whole segment as a map key.
     * Pinned because a reviewer reasonably expected otherwise, and the global
     * variable docs use hyphenated keys throughout.
     */
    @Test
    @DisplayName("dot notation resolves a hyphenated map key; bracket notation also works")
    void hyphenatedKeysResolveBothWays() throws Exception {
        assertEquals("claude-sonnet", render("{vars.default-model}"));
        assertEquals("claude-sonnet", render("{vars['default-model']}"));
    }

    /**
     * How a snippet with {@code templateEnabled=false} protects its content. Qute's
     * unparsed block is <code>{|...|}</code>; the Jinja2 <code>{% raw %}</code>
     * form that PromptSnippetService used to emit is doubly wrong — Qute leaves the
     * tags in the prompt verbatim AND still resolves the markers they were meant to
     * protect.
     */
    @Test
    @DisplayName("Qute unparsed blocks protect content; Jinja2 raw tags do not")
    void unparsedBlockProtectsContent() throws Exception {
        assertEquals("Use {properties.company_name} here", render("{|Use {properties.company_name} here|}"));
        assertEquals("{% raw %}Use ACME here{% endraw %}", render("{% raw %}Use {properties.company_name} here{% endraw %}"),
                "the Jinja2 form leaks the value and leaves its own tags behind");
    }

    /**
     * The unparsed block is only safe while the content cannot close it. A naive
     * wrap of content containing the terminator resolves the expression that
     * follows it — the escape defeats itself.
     */
    @Test
    @DisplayName("content containing the terminator closes an unparsed block early")
    void terminatorInContentClosesTheBlock() throws Exception {
        assertEquals("a ACME b|}", render("{|a|} {properties.company_name} b|}"),
                "naive wrapping lets the expression after the terminator resolve");
        // splitting the pair across a block boundary keeps it literal
        assertEquals("a|} {properties.company_name} b", render("{|a||}{|} {properties.company_name} b|}"));
    }

    @Test
    @DisplayName("double braces do NOT resolve — they reach the model as literal text")
    void doubleBracesDoNotResolve() throws Exception {
        assertEquals("{{snippets.greeting}}", render("{{snippets.greeting}}"));
        assertEquals("{{vars.model}}", render("{{vars.model}}"));
    }
}
