/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refusal detection is the only one of the five response-validation triggers
 * whose signal is a language guess rather than something the provider reports.
 * The other four — empty text, a length finish reason, a content-filter flag, a
 * streaming timeout — are structural and work for any language.
 * <p>
 * It used to be four English prefixes compiled into {@code LlmTask}, so a
 * German- or Japanese-language agent that set {@code onRefusal} had a guardrail
 * that could never fire: no retry, no warning, no metric. Making it
 * configurable introduces the opposite hazard, which is what most of this class
 * is about — a stray empty entry in the list would match every completion, and
 * under {@code onRefusal: "error"} fail every turn.
 */
@DisplayName("LLM refusal detection")
class LlmTaskRefusalDetectionTest {

    private static final List<String> DEFAULTS = LlmConfiguration.ResponseValidation.DEFAULT_REFUSAL_PATTERNS;

    @Test
    @DisplayName("the shipped defaults still catch the prefixes they replaced")
    void defaultsCatchTheFormerLiterals() {
        for (String prefix : DEFAULTS) {
            assertTrue(LlmTask.looksLikeRefusal(prefix + " help with that.", DEFAULTS),
                    "a completion opening \"" + prefix + "\" is what these defaults exist to catch");
        }
    }

    @Test
    @DisplayName("an ordinary answer is not a refusal")
    void ordinaryAnswerIsNotARefusal() {
        assertFalse(LlmTask.looksLikeRefusal("The invoice total is $450.", DEFAULTS));
    }

    /**
     * The pattern is a prefix, not a substring. A refusal is recognisable because
     * the model leads with it; the same words mid-sentence are usually part of a
     * real answer.
     */
    @Test
    @DisplayName("a matching phrase mid-sentence is not a refusal")
    void midSentenceMatchIsNotARefusal() {
        assertFalse(LlmTask.looksLikeRefusal("The policy says I cannot issue a refund after 30 days.", DEFAULTS),
                "the phrase has to open the completion, or every explanation of a limitation becomes a refusal");
    }

    /**
     * The hazard this whole test class exists for. {@code "".startsWith(x)} is true
     * for every {@code x}, so an empty entry would classify every completion as a
     * refusal.
     */
    @Test
    @DisplayName("a blank pattern does not match everything")
    void blankPatternMatchesNothing() {
        for (List<String> patterns : List.of(List.of(""), List.of("   "), List.of("\t"), Arrays.asList("i cannot", ""))) {
            assertFalse(LlmTask.looksLikeRefusal("The invoice total is $450.", patterns),
                    "a blank entry in " + patterns + " must be dropped, not treated as a universal prefix");
        }
    }

    @Test
    @DisplayName("a null entry is skipped rather than throwing")
    void nullEntryIsSkipped() {
        assertTrue(LlmTask.looksLikeRefusal("I cannot help with that.", Arrays.asList(null, "i cannot")));
        assertFalse(LlmTask.looksLikeRefusal("The invoice total is $450.", Arrays.asList((String) null)));
    }

    @Test
    @DisplayName("an empty list disables detection")
    void emptyListDisablesDetection() {
        assertFalse(LlmTask.looksLikeRefusal("I cannot help with that.", List.of()),
                "an empty list is how a deployment turns the heuristic off");
    }

    @Test
    @DisplayName("a null list disables detection")
    void nullListDisablesDetection() {
        assertFalse(LlmTask.looksLikeRefusal("I cannot help with that.", null));
    }

    /**
     * The reason the field exists. A non-English agent must be able to name its own
     * prefixes, and doing so must not leave the English ones quietly in effect.
     */
    @Test
    @DisplayName("a non-English list catches its own refusals and only those")
    void nonEnglishPatternsWork() {
        var german = List.of("es tut mir leid", "das kann ich nicht");

        assertTrue(LlmTask.looksLikeRefusal("Es tut mir leid, das kann ich nicht beantworten.", german));
        assertFalse(LlmTask.looksLikeRefusal("I cannot help with that.", german),
                "replacing the list must actually replace it, not add to the English defaults");
    }

    @Test
    @DisplayName("matching ignores case and surrounding whitespace")
    void matchingIsCaseAndWhitespaceInsensitive() {
        assertTrue(LlmTask.looksLikeRefusal("  \n I CANNOT do that.", DEFAULTS));
        assertTrue(LlmTask.looksLikeRefusal("i cannot do that.", List.of("  I Cannot  ")),
                "a pattern with stray spacing should still match; the operator typed it into JSON, not a regex");
    }

    /**
     * A Turkish-locale JVM lowercases {@code I} to a dotless {@code ı}, so a
     * locale-sensitive {@code toLowerCase()} would stop "I cannot" matching on
     * exactly the deployments least likely to notice. Asserting the behaviour
     * rather than the call keeps the guarantee if the implementation is rewritten.
     */
    @Test
    @DisplayName("matching survives a Turkish default locale")
    void matchingSurvivesATurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(LlmTask.looksLikeRefusal("I cannot help with that.", DEFAULTS),
                    "case folding must not depend on the JVM's default locale");
        } finally {
            Locale.setDefault(original);
        }
    }
}
