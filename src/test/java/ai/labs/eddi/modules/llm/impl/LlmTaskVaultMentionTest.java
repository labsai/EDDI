/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A prompt may TALK ABOUT vault references without crashing templating.
 * <p>
 * The Platform Operator's system prompt instructs the model to write secrets as
 * {@code ${vault:key-name}}. Qute parses the brace part as a namespaced
 * expression, and there is deliberately no {@code vault} namespace resolver
 * (see {@code CallerNamespaceResolver} for the security reasoning), so before
 * {@link LlmTask#escapeVaultMentions} every turn of such an agent failed
 * templating for that parameter — and the fallback used the RAW string,
 * skipping every legitimate expression alongside the mention.
 */
@DisplayName("LLM params mentioning vault references")
class LlmTaskVaultMentionTest {

    private ITemplatingEngine templatingEngine;

    @BeforeEach
    void setUp() {
        templatingEngine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
    }

    private static final String OPERATOR_PROMPT_EXCERPT = "You are the EDDI Platform Operator. Secrets must be written as a ${vault:key-name} "
            + "reference (the platform resolves it at call time). Greet {userName} by name.";

    @Test
    @DisplayName("the un-escaped mention reproduces the crash — the guard this fix exists for")
    void unescapedMentionFailsTemplating() {
        // If this stops throwing (an engine upgrade, someone adds a vault
        // resolver), escapeVaultMentions is dead code AND the CallerNamespaceResolver
        // security doc no longer holds — both need to be revisited together.
        assertThrows(ITemplatingEngine.TemplateEngineException.class,
                () -> templatingEngine.processTemplate(OPERATOR_PROMPT_EXCERPT, Map.of("userName", "Ada")));
    }

    @Test
    @DisplayName("the escaped prompt renders: mention verbatim, expressions beside it resolved")
    void escapedMentionRoundTripsAndNeighboursRender() throws Exception {
        String rendered = templatingEngine.processTemplate(
                LlmTask.escapeVaultMentions(OPERATOR_PROMPT_EXCERPT), Map.of("userName", "Ada"));

        assertTrue(rendered.contains("${vault:key-name}"),
                "the mention must reach the model verbatim; got: " + rendered);
        assertTrue(rendered.contains("Greet Ada by name"),
                "expressions beside the mention must still render; got: " + rendered);
        assertFalse(rendered.contains("{|"),
                "no raw-section markers may leak into the prompt; got: " + rendered);
    }

    @Test
    @DisplayName("a bare {vault:...} mention (no leading $) is escaped too")
    void bareMentionFormEscapes() throws Exception {
        String value = "Use {vault:a-key} or {vault:b_key} here.";

        String rendered = templatingEngine.processTemplate(LlmTask.escapeVaultMentions(value), Map.of());

        assertEquals("Use {vault:a-key} or {vault:b_key} here.", rendered);
    }

    @Test
    @DisplayName("values without a mention are returned untouched — same instance, no cost")
    void noMentionIsUntouched() {
        String value = "Plain prompt with {userName} and no secrets syntax.";
        assertSame(value, LlmTask.escapeVaultMentions(value));
        assertNull(LlmTask.escapeVaultMentions(null));
    }
}
