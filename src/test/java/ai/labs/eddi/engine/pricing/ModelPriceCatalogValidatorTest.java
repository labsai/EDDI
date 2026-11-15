/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPriceCatalogValidatorTest {

    private static List<String> validate(ModelPriceEntry... entries) {
        return ModelPriceCatalogValidator.validate(new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null, List.of(entries)),
                Set.of("openai", "anthropic", "ollama"));
    }

    private static ModelPriceEntry entry(String provider, String model) {
        return ModelPriceEntry.of(provider, model, 1.0, 2.0);
    }

    @Test
    void aValidCatalogPasses() {
        var full = new ModelPriceEntry("anthropic", "claude-sonnet-4-5*", 3.0, 15.0, 0.3, 3.75,
                new LongContextTier(200_000L, 6.0, 22.5, 0.6, 7.5), "2026-11-02", "https://www.anthropic.com/pricing", "list price");
        assertEquals(List.of(), validate(full, entry("openai", "gpt-4o"), entry("*", "*"), ModelPriceEntry.of("ollama", "*", 0.0, 0.0)));
    }

    @Test
    void modelCharsetAndWildcards() {
        assertEquals(List.of(), validate(entry("openai", "*gpt-4o*")));
        assertEquals(1, validate(entry("openai", "gpt*4o")).size(), "a mid-string * is refused");
        assertEquals(1, validate(entry("openai", "**")).size());
        assertEquals(1, validate(entry("openai", "gpt 4o")).size());
        assertEquals(1, validate(entry("openai", "gpt-4o(.*)")).size());
        assertEquals(1, validate(entry("openai", "")).size());
        assertEquals(1, validate(entry("openai", "a".repeat(129))).size());
        assertEquals(List.of(), validate(entry("openai", "a".repeat(128))));
    }

    @Test
    void providerMustBeKnownOrWildcard() {
        assertEquals(1, validate(entry("opneai", "gpt-4o")).size());
        assertEquals(1, validate(entry("Open AI", "gpt-4o")).size());
        assertEquals(1, validate(entry(null, "gpt-4o")).size());
        assertEquals(List.of(), ModelPriceCatalogValidator.validate(
                new ModelPriceCatalog(1, null, null, null, List.of(entry("anything-new", "m"))), null));
    }

    @Test
    void pricesMustBeFiniteAndInRange() {
        assertEquals(1, validate(ModelPriceEntry.of("openai", "a", -0.01, 1.0)).size());
        assertEquals(1, validate(ModelPriceEntry.of("openai", "a", Double.NaN, 1.0)).size());
        assertEquals(1, validate(ModelPriceEntry.of("openai", "a", Double.POSITIVE_INFINITY, 1.0)).size());
        assertEquals(1, validate(ModelPriceEntry.of("openai", "a", 10_000.01, 1.0)).size());
        assertEquals(List.of(), validate(ModelPriceEntry.of("openai", "a", 10_000.0, 0.0)));
        assertEquals(1, validate(ModelPriceEntry.of("openai", "a", null, null)).size(), "an entry must price something");
    }

    @Test
    void longContextThresholdIsBounded() {
        var low = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, new LongContextTier(999L, 2.0, null, null, null), null, null, null);
        var missing = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, new LongContextTier(null, 2.0, null, null, null), null, null, null);
        var high = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, new LongContextTier(10_000_001L, 2.0, null, null, null), null, null,
                null);
        var badPrice = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, new LongContextTier(200_000L, -1.0, null, null, null), null,
                null, null);
        assertEquals(1, validate(low).size());
        assertEquals(1, validate(missing).size());
        assertEquals(1, validate(high).size());
        assertEquals(1, validate(badPrice).size());
    }

    @Test
    void textFieldsAreBoundedAndSourceIsHttp() {
        var longNote = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, null, null, null, "x".repeat(513));
        var fileSource = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, null, null, "file:///etc/passwd", null);
        var jsSource = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, null, null, "javascript:alert(1)", null);
        var badDate = new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, null, "yesterday", null, null);
        assertEquals(1, validate(longNote).size());
        assertEquals(1, validate(fileSource).size());
        assertEquals(1, validate(jsSource).size());
        assertEquals(1, validate(badDate).size());
    }

    @Test
    void duplicatesAreRefusedCaseInsensitively() {
        List<String> violations = validate(entry("openai", "gpt-4o"), entry("OpenAI".toLowerCase(), "GPT-4o"));
        assertEquals(1, violations.size());
        assertTrue(violations.getFirst().contains("duplicate"));
    }

    @Test
    void entryCountIsCapped() {
        List<ModelPriceEntry> many = new ArrayList<>();
        for (int i = 0; i <= ModelPriceCatalogValidator.MAX_ENTRIES; i++) {
            many.add(entry("openai", "m" + i));
        }
        assertEquals(1, ModelPriceCatalogValidator.validate(new ModelPriceCatalog(1, null, null, null, many), null).size());
    }
}
