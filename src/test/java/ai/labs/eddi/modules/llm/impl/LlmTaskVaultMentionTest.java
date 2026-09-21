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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An LLM parameter may CARRY or TALK ABOUT a configuration reference without
 * crashing templating.
 * <p>
 * Every reference namespace — {@code vault}, the legacy {@code eddivault},
 * {@code vars}, {@code connection} and {@code caller} — is resolved AFTER
 * templating, and none has a Qute namespace resolver (see
 * {@code CallerNamespaceResolver} for the security reasoning). So Qute treats
 * each as an unresolvable namespaced expression and throws, and
 * {@link LlmTask#escapeConfigReferenceMentions} is what keeps the value intact.
 * <p>
 * This covers two live shapes: the Platform Operator's prompt, which documents
 * {@code ${vault:key-name}} to the model, and an agent whose
 * {@code "modelName"} IS {@code ${vars:gemini-model}} — the latter logged "No
 * namespace resolver found for [vars]" on every turn until the escape covered
 * more than {@code vault}.
 */
@DisplayName("LLM params mentioning configuration references")
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
        // resolver), escapeConfigReferenceMentions is dead code AND the
        // CallerNamespaceResolver security doc no longer holds — both need to be
        // revisited together.
        assertThrows(ITemplatingEngine.TemplateEngineException.class,
                () -> templatingEngine.processTemplate(OPERATOR_PROMPT_EXCERPT, Map.of("userName", "Ada")));
    }

    @Test
    @DisplayName("the escaped prompt renders: mention verbatim, expressions beside it resolved")
    void escapedMentionRoundTripsAndNeighboursRender() throws Exception {
        String rendered = templatingEngine.processTemplate(
                LlmTask.escapeConfigReferenceMentions(OPERATOR_PROMPT_EXCERPT), Map.of("userName", "Ada"));

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

        String rendered = templatingEngine.processTemplate(LlmTask.escapeConfigReferenceMentions(value), Map.of());

        assertEquals("Use {vault:a-key} or {vault:b_key} here.", rendered);
    }

    /**
     * The live case: three of these four namespaces were NOT escaped, so a
     * parameter carrying one failed templating every turn. {@code modelName:
     * ${vars:gemini-model}} is the one that was observed on a deployment.
     * <p>
     * These four are spelled out so each carries its own realistic key shape and
     * its own "still throws un-escaped" assertion.
     * {@link #preCheckCoversEveryNamespaceInThePattern} is the exhaustive guard and
     * additionally covers {@code eddivault}.
     */
    @ParameterizedTest(name = "{0} survives templating verbatim")
    @ValueSource(strings = {"${vault:gemini-api-key}", "${vars:gemini-model}", "${connection:crm-api}", "${caller:token}"})
    @DisplayName("every configuration-reference namespace survives templating")
    void everyNamespaceSurvives(String reference) throws Exception {
        assertThrows(ITemplatingEngine.TemplateEngineException.class,
                () -> templatingEngine.processTemplate(reference, Map.of()),
                "un-escaped, this reference must still be what crashes templating: " + reference);

        String rendered = templatingEngine.processTemplate(LlmTask.escapeConfigReferenceMentions(reference), Map.of());

        assertEquals(reference, rendered);
    }

    /**
     * Why this is more than log noise. The fallback keeps the parameter's RAW
     * value, which is harmless when the value is only a reference — and silently
     * wrong when a real expression sits beside it, because the whole render is
     * abandoned.
     */
    @Test
    @DisplayName("a reference beside a real expression no longer abandons the render")
    void referenceBesideExpressionStillRenders() throws Exception {
        String value = "Model {vars:gemini-model} answering {properties.agentName}.";

        String rendered = templatingEngine.processTemplate(
                LlmTask.escapeConfigReferenceMentions(value), Map.of("properties", Map.of("agentName", "Ada")));

        assertEquals("Model {vars:gemini-model} answering Ada.", rendered);
    }

    @Test
    @DisplayName("the legacy ${eddivault:...} prefix is escaped as well")
    void legacyVaultPrefixEscapes() throws Exception {
        String value = "${eddivault:default/gemini-api-key}";

        assertEquals(value, templatingEngine.processTemplate(LlmTask.escapeConfigReferenceMentions(value), Map.of()));
    }

    /**
     * The pre-check in {@code escapeConfigReferenceMentions} is a hand-written
     * superset of the pattern, so the two can drift: a namespace added to the
     * pattern with no substring in the pre-check list would never reach the regex
     * and would silently keep crashing templating — the exact bug this fix is
     * about, reintroduced for one namespace.
     * <p>
     * So derive the namespaces FROM the pattern rather than restating them, and
     * assert each one actually round-trips. This fails if a namespace is added to
     * the pattern alone, and it is what makes {@code eddivault}'s reliance on
     * {@code "vault:"} being a substring safe to leave implicit.
     */
    @Test
    @DisplayName("every namespace in the pattern survives the pre-check — the two cannot drift")
    void preCheckCoversEveryNamespaceInThePattern() throws Exception {
        var alternation = Pattern.compile("\\\\\\{\\(\\?:([^)]+)\\):").matcher(LlmTask.CONFIG_REF_MENTION.pattern());
        assertTrue(alternation.find(), "pattern shape changed; this guard needs updating: " + LlmTask.CONFIG_REF_MENTION);

        var namespaces = alternation.group(1).split("\\|");
        assertTrue(namespaces.length >= 5, "expected at least the 5 known namespaces, got " + Arrays.toString(namespaces));

        for (var namespace : namespaces) {
            String reference = "${" + namespace + ":some-key}";
            assertEquals(reference, templatingEngine.processTemplate(
                    LlmTask.escapeConfigReferenceMentions(reference), Map.of()),
                    "namespace '" + namespace + "' is in CONFIG_REF_MENTION but is not escaped — "
                            + "CONFIG_REF_NAMESPACES needs a substring of it");
        }
    }

    @Test
    @DisplayName("values without a mention are returned untouched — same instance, no cost")
    void noMentionIsUntouched() {
        String value = "Plain prompt with {userName} and no secrets syntax.";
        assertSame(value, LlmTask.escapeConfigReferenceMentions(value));
        assertNull(LlmTask.escapeConfigReferenceMentions(null));
    }
}
