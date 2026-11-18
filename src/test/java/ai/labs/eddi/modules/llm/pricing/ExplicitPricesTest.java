/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("explicit zero prices")
class ExplicitPricesTest {

    private static final PriceCatalogSnapshot CATALOG = PriceCatalogSnapshot.of(
            new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null, List.of(ModelPriceEntry.of("openai", "gpt-4o*", 2.5, 10.0))),
            UnpricedPolicy.ALLOW);

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        ExplicitPrices.setCatalogFloor(false);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        ExplicitPrices.setCatalogFloor(false);
    }

    private static LlmConfiguration.Task task(String type, Double in, Double out) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType(type);
        task.setInputPricePer1M(in);
        task.setOutputPricePer1M(out);
        return task;
    }

    private static Map<String, Object> usage(String type, String model) {
        return usage(type, model, CATALOG);
    }

    private static Map<String, Object> usage(String type, String model, PriceCatalogSnapshot catalog) {
        return ToolContextBudget.tokenUsageMap(UsageNormalizer.normalize(new TokenUsage(1_000_000, 1_000_000), type, null, model, catalog, true));
    }

    @Test
    @DisplayName("a zero on a hosted provider is warned about, a zero on a local one is not")
    void warnsOnlyForHostedProviders() {
        assertEquals(1, ExplicitPrices.zeroPriceWarnings(task("openai", 0.0, 0.0)).size());
        assertEquals(1, ExplicitPrices.zeroPriceWarnings(task("anthropic", 0.0, null)).size());
        assertTrue(ExplicitPrices.zeroPriceWarnings(task("ollama", 0.0, 0.0)).isEmpty());
        assertTrue(ExplicitPrices.zeroPriceWarnings(task("jlama", 0.0, 0.0)).isEmpty());
        assertTrue(ExplicitPrices.zeroPriceWarnings(task("openai-compatible", 0.0, 0.0)).isEmpty());
        // a partial zero hides half of the spend: warned about too
        assertEquals(1, ExplicitPrices.zeroPriceWarnings(task("openai", 0.0, 10.0)).size());
        assertEquals(1, ExplicitPrices.zeroPriceWarnings(task("openai", 1.0, 0.0)).size());
        // real prices, or none at all, are not a zero
        assertTrue(ExplicitPrices.zeroPriceWarnings(task("openai", 1.0, 2.0)).isEmpty());
        assertTrue(ExplicitPrices.zeroPriceWarnings(task("openai", null, null)).isEmpty());
    }

    @Test
    @DisplayName("the warning names the cascade step or the judge that carries the zero")
    void warningNamesWhereItIsSet() {
        var task = task("ollama", null, null);
        var step = new LlmConfiguration.CascadeStep();
        step.setType("openai");
        step.setInputPricePer1M(0.0);
        step.setOutputPricePer1M(0.0);
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));
        var judge = new LlmConfiguration.JudgeModelConfig();
        judge.setType("anthropic");
        judge.setInputPricePer1M(0.0);
        cascade.setJudgeModel(judge);
        task.setModelCascade(cascade);

        List<String> warnings = ExplicitPrices.zeroPriceWarnings(task);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("cascade step 0"), warnings.get(0));
        assertTrue(warnings.get(1).contains("cascade judge"), warnings.get(1));
    }

    @Test
    @DisplayName("the deploy lint carries the same warning")
    void lintCarriesIt() {
        var warnings = PricingLint.lint(task("openai", 0.0, 0.0), CATALOG);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("explicit 0")), warnings.toString());
    }

    @Test
    @DisplayName("a call priced at an explicit zero on a hosted provider is counted, a local one is not")
    void countsZeroPricedCalls() {
        var zero = PriceOverride.of(0.0, 0.0);

        PricedSpend hosted = ModelPriceResolver.price(zero, usage("openai", "gpt-4o"), CATALOG);
        ModelPriceResolver.price(zero, usage("ollama", "llama3"), CATALOG);
        ModelPriceResolver.price(PriceOverride.of(1.0, 1.0), usage("openai", "gpt-4o"), CATALOG);

        assertEquals(0.0, hosted.usd(), 1e-12);
        assertEquals(1.0, registry.counter(ExplicitPrices.ZERO_PRICED_CALLS, "provider", "openai").count());
        assertEquals(0.0, registry.counter(ExplicitPrices.ZERO_PRICED_CALLS, "provider", "ollama").count());
    }

    @Test
    @DisplayName("default: an explicit zero still beats the catalog; with the catalog floor it is raised to the catalog price")
    void catalogFloorRaisesAZero() {
        var zero = PriceOverride.of(0.0, 0.0);

        PricedSpend off = ModelPriceResolver.price(zero, usage("openai", "gpt-4o"), CATALOG);
        assertEquals(0.0, off.usd(), 1e-12);

        ExplicitPrices.setCatalogFloor(true);
        PricedSpend on = ModelPriceResolver.price(zero, usage("openai", "gpt-4o"), CATALOG);
        assertEquals(2.5 + 10.0, on.usd(), 1e-9);
    }

    @Test
    @DisplayName("the provider tag comes from the known provider types, anything else is 'other'")
    void providerTagIsAllowListed() {
        ModelPriceResolver.price(PriceOverride.of(0.0, 0.0), Map.of("inputTokens", 5L, "outputTokens", 5L, "pricingModel", "made-up-{x}/m"), CATALOG);
        ModelPriceResolver.price(PriceOverride.of(0.0, 0.0), Map.of("inputTokens", 5L, "outputTokens", 5L, "pricingModel", "xai/grok"), CATALOG);

        assertEquals(1.0, registry.counter(ExplicitPrices.ZERO_PRICED_CALLS, "provider", "other").count());
        assertEquals(1.0, registry.counter(ExplicitPrices.ZERO_PRICED_CALLS, "provider", "xai").count());
    }

    @Test
    @DisplayName("the catalog floor does not apply to a local provider")
    void floorSkipsLocalProviders() {
        var local = PriceCatalogSnapshot.of(
                new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null, List.of(ModelPriceEntry.of("ollama", "*", 1.0, 1.0))),
                UnpricedPolicy.ALLOW);
        ExplicitPrices.setCatalogFloor(true);
        var spend = ModelPriceResolver.price(PriceOverride.of(0.0, 0.0), usage("ollama", "llama3", local), local);
        assertEquals(0.0, spend.usd(), 1e-12);
    }

    @Test
    @DisplayName("the floor only raises: a price above the catalog's stays, a model the catalog does not know keeps its zero")
    void catalogFloorOnlyRaises() {
        ExplicitPrices.setCatalogFloor(true);

        PricedSpend higher = ModelPriceResolver.price(PriceOverride.of(5.0, 20.0), usage("openai", "gpt-4o"), CATALOG);
        assertEquals(5.0 + 20.0, higher.usd(), 1e-9);

        PricedSpend uncatalogued = ModelPriceResolver.price(PriceOverride.of(0.0, 0.0), usage("mistral", "unknown-model"), CATALOG);
        assertEquals(0.0, uncatalogued.usd(), 1e-12);
        assertFalse(ExplicitPrices.isLocal("mistral"));
    }
}
