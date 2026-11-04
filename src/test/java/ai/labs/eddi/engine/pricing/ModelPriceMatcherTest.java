/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPriceMatcherTest {

    private static ModelPriceEntry e(String provider, String model, double in) {
        return ModelPriceEntry.of(provider, model, in, in * 4);
    }

    private static double inputOf(ModelPriceMatcher m, String provider, String model) {
        return m.match(provider, model).orElseThrow().inputPricePer1M();
    }

    @Test
    void exactBeatsGlob() {
        var m = ModelPriceMatcher.compile(List.of(e("openai", "gpt-4o*", 1), e("openai", "gpt-4o", 2)));
        assertEquals(2, inputOf(m, "openai", "gpt-4o"));
        assertEquals(1, inputOf(m, "openai", "gpt-4o-2024-08-06"));
    }

    @Test
    void longestLiteralWinsWhateverTheOrder() {
        var m = ModelPriceMatcher.compile(List.of(e("openai", "gpt-4o*", 1), e("openai", "gpt-4o-mini*", 2)));
        assertEquals(2, inputOf(m, "openai", "gpt-4o-mini-2024-07-18"));
        assertEquals(1, inputOf(m, "openai", "gpt-4o-2024-08-06"));
        var reversed = ModelPriceMatcher.compile(List.of(e("openai", "gpt-4o-mini*", 2), e("openai", "gpt-4o*", 1)));
        assertEquals(2, inputOf(reversed, "openai", "gpt-4o-mini"));
    }

    @Test
    void leadingAndBothSidedGlobs() {
        var m = ModelPriceMatcher.compile(List.of(e("bedrock", "*anthropic.claude*", 3), e("bedrock", "*-v1:0", 5)));
        assertEquals(3, inputOf(m, "bedrock", "eu.anthropic.claude-sonnet-4-5-v1:0"));
        assertEquals(5, inputOf(m, "bedrock", "amazon.nova-pro-v1:0"));
    }

    @Test
    void caseInsensitive() {
        var m = ModelPriceMatcher.compile(List.of(e("Anthropic", "Claude-Sonnet-4-5*", 3)));
        assertEquals(3, inputOf(m, "anthropic", "claude-sonnet-4-5-20250929"));
        assertEquals(3, inputOf(m, "ANTHROPIC", "CLAUDE-SONNET-4-5"));
    }

    @Test
    void responseModelIsTriedBeforeRequestModel() {
        var m = ModelPriceMatcher.compile(List.of(e("openrouter", "openrouter/auto", 15), e("openrouter", "anthropic/claude*", 3)));
        assertEquals(3, m.match("openrouter", "anthropic/claude-sonnet-4.5", "openrouter/auto").orElseThrow().inputPricePer1M());
        assertEquals(15, m.match("openrouter", "something/unknown", "openrouter/auto").orElseThrow().inputPricePer1M());
        assertEquals(15, m.match("openrouter", null, "openrouter/auto").orElseThrow().inputPricePer1M());
    }

    @Test
    void providerSpecificBeatsWildcardProvider() {
        var m = ModelPriceMatcher.compile(List.of(e("*", "llama3*", 0.5), e("ollama", "*", 0)));
        assertEquals(0, inputOf(m, "ollama", "llama3.1:8b"));
        assertEquals(0.5, inputOf(m, "openai-compatible", "llama3.1-70b"));
    }

    @Test
    void wildcardModelMatchesEverything() {
        var m = ModelPriceMatcher.compile(List.of(e("ollama", "*", 0)));
        assertEquals(0, inputOf(m, "ollama", "anything"));
        assertTrue(m.match("openai", "anything").isEmpty());
    }

    @Test
    void nothingMatches() {
        var m = ModelPriceMatcher.compile(List.of(e("openai", "gpt-4o", 1)));
        assertTrue(m.match("openai", "gpt-4").isEmpty());
        assertTrue(m.match("openai", null).isEmpty());
        assertTrue(m.match("anthropic", "gpt-4o").isEmpty());
        assertTrue(ModelPriceMatcher.EMPTY.match("openai", "gpt-4o").isEmpty());
    }

    @Test
    void midStringTextIsLiteralNotAWildcard() {
        // only a leading/trailing '*' is special; the validator refuses a middle one,
        // and the matcher never interprets it
        var m = ModelPriceMatcher.compile(List.of(e("openai", "gpt*4o", 1)));
        assertTrue(m.match("openai", "gpt-4o").isEmpty());
    }
}
