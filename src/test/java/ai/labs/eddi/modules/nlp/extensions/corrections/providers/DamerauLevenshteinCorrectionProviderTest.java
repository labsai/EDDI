/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections.providers;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.nlp.extensions.corrections.DamerauLevenshteinCorrection.DEFAULT_MAX_CANDIDATES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The candidate cap bounds the parser's combinatorial explosion, so agent
 * designers must be able to tune it from the workflow configuration — a cap
 * that only the constructor knows about is not configuration, it is a
 * hard-coded constant (AGENTS.md §4.1 rule 1).
 */
@DisplayName("DamerauLevenshteinCorrectionProvider — configuration")
class DamerauLevenshteinCorrectionProviderTest {

    /** Distances from "hallo": 0, 1, 1, 1, 1, 1, 1, 2 — all within distance 2. */
    private static final List<String> CANDIDATE_WORDS = List.of("hallo", "hbllo", "hcllo", "hdllo", "hello", "hfllo", "hgllo", "hxylo");

    @Test
    @DisplayName("'maxCandidates' from the workflow config reaches the correction")
    void maxCandidatesIsConfigurable() {
        var correction = provide(Map.<String, Object>of("distance", "2", "maxCandidates", "2"));

        assertEquals(2, correction.correctWord("hallo", "en", Collections.emptyList()).size(),
                "the configured candidate cap must be honoured, not the built-in default");
    }

    @Test
    @DisplayName("a larger 'maxCandidates' widens the result set")
    void maxCandidatesCanBeRaised() {
        var correction = provide(Map.<String, Object>of("distance", "2", "maxCandidates", "8"));

        assertEquals(CANDIDATE_WORDS.size(), correction.correctWord("hallo", "en", Collections.emptyList()).size());
    }

    @Test
    @DisplayName("without 'maxCandidates' the built-in cap applies")
    void defaultCandidateCapApplies() {
        var correction = provide(Map.<String, Object>of("distance", "2"));

        assertEquals(DEFAULT_MAX_CANDIDATES, correction.correctWord("hallo", "en", Collections.emptyList()).size());
    }

    @Test
    @DisplayName("a non-positive or malformed 'maxCandidates' falls back to the default")
    void malformedCandidateCapFallsBack() {
        var nonPositive = provide(Map.<String, Object>of("distance", "2", "maxCandidates", "0"));
        var malformed = provide(Map.<String, Object>of("distance", "2", "maxCandidates", "abc"));

        assertEquals(DEFAULT_MAX_CANDIDATES, nonPositive.correctWord("hallo", "en", Collections.emptyList()).size());
        assertEquals(DEFAULT_MAX_CANDIDATES, malformed.correctWord("hallo", "en", Collections.emptyList()).size());
    }

    @Test
    @DisplayName("numeric (non-string) config values are accepted")
    void numericConfigValuesAreAccepted() {
        var correction = provide(Map.<String, Object>of("distance", 2, "maxCandidates", 2));

        assertEquals(2, correction.correctWord("hallo", "en", Collections.emptyList()).size());
    }

    @Test
    @DisplayName("'maxCandidates' is advertised as a config option")
    void maxCandidatesIsAdvertised() {
        var configs = new DamerauLevenshteinCorrectionProvider().getConfigs();

        assertTrue(configs.containsKey("distance"));
        assertTrue(configs.containsKey("maxCandidates"), "an option no agent config can reach is not configuration");
        assertEquals(DEFAULT_MAX_CANDIDATES, configs.get("maxCandidates").getDefaultValue());
    }

    @Test
    @DisplayName("'distance' from the workflow config still reaches the correction")
    void distanceIsStillConfigurable() {
        var correction = provide(Map.<String, Object>of("distance", "1", "maxCandidates", "8"));

        assertEquals(CANDIDATE_WORDS.size() - 1, correction.correctWord("hallo", "en", Collections.emptyList()).size(),
                "with distance 1 the distance-2 candidate must drop out");
    }

    private static ICorrection provide(Map<String, Object> config) {
        var correction = new DamerauLevenshteinCorrectionProvider().provide(config);
        correction.init(List.of(dictionary()));

        return correction;
    }

    private static RegularDictionary dictionary() {
        var dictionary = new RegularDictionary();
        CANDIDATE_WORDS.forEach(word -> dictionary.addWord(word, new Expressions(new Expression("word", new Expression(word))), 0));

        return dictionary;
    }
}
