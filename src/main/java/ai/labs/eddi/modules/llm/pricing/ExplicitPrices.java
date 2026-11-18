/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProviders;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import io.micrometer.core.instrument.Metrics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an explicit {@code 0} price on a configuration means, and what to do
 * about it.
 * <p>
 * An explicit price beats the catalog, and a zero is a real price — a local
 * model is legitimately free ({@code docs/model-pricing.md}). But a zero on a
 * <em>hosted</em> provider keeps {@code audit:cost} at 0, so cost ceilings,
 * tenant budgets and {@code unpricedPolicy=REFUSE} never fire for it. That is
 * allowed to stand (the author may know better), but it is made visible: a
 * warning at save and at deploy, the counter {@value #ZERO_PRICED_CALLS} per
 * call, and the deployment switch {@code eddi.llm.pricing.catalog-floor} that
 * stops a configuration pricing a catalogued model below the catalog.
 */
public final class ExplicitPrices {

    /**
     * Counter of model calls priced at an explicit zero on a non-local provider.
     */
    public static final String ZERO_PRICED_CALLS = "eddi.llm.pricing.explicit_zero_calls";

    /**
     * Provider types that run on the operator's own hardware or endpoint: a zero
     * price is the normal case there.
     */
    private static final Set<String> LOCAL_PROVIDERS = Set.of("ollama", "jlama", "openai-compatible");

    /**
     * The provider types a {@code provider} tag may carry — the built-in types and
     * the named OpenAI-compatible presets; anything else is tagged {@code other},
     * so a typo or a template in a config cannot grow the metric's cardinality.
     */
    private static final Set<String> KNOWN_PROVIDERS = knownProviders();

    private static Set<String> knownProviders() {
        Set<String> known = new HashSet<>(List.of("openai", "huggingface", "anthropic", "gemini-vertex", "gemini", "ollama", "jlama", "mistral",
                "azure-openai", "bedrock", "oracle-genai", "openai-compatible"));
        OpenAiCompatibleProviders.all().forEach(provider -> known.add(provider.id()));
        return Set.copyOf(known);
    }

    /** {@code eddi.llm.pricing.catalog-floor}; published by its config bean. */
    private static volatile boolean catalogFloor = false;

    private ExplicitPrices() {
    }

    /** Sets the catalog-floor switch. */
    public static void setCatalogFloor(boolean value) {
        catalogFloor = value;
    }

    /**
     * Whether a configuration may not price a catalogued model below the catalog.
     */
    public static boolean catalogFloor() {
        return catalogFloor;
    }

    /** Whether {@code type} is a local or self-hosted provider, free by nature. */
    public static boolean isLocal(String type) {
        return type != null && LOCAL_PROVIDERS.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a price that is set is an explicit zero — the input, the output or
     * both. A partial zero ({@code input: 0, output: 10}) hides half of the spend
     * the same way.
     */
    public static boolean isExplicitZero(Double input, Double output) {
        return (input != null && input == 0.0) || (output != null && output == 0.0);
    }

    /** The provider type of a usage's {@code "<type>/<model>"} key, or null. */
    static String providerOf(Object pricingModel) {
        return pricingModel instanceof String key && key.indexOf('/') > 0 ? key.substring(0, key.indexOf('/')) : null;
    }

    /**
     * Counts a priced call whose configuration says the model is free, when the
     * provider is not a local one. {@code pricingModel} is the usage's
     * {@code "<type>/<model>"} key.
     */
    static void countIfZeroPriced(PriceOverride override, Object pricingModel) {
        if (override == null || !isExplicitZero(override.inputPer1M(), override.outputPer1M())) {
            return;
        }
        String type = providerOf(pricingModel);
        if (isLocal(type)) {
            return;
        }
        String lower = type != null ? type.toLowerCase(Locale.ROOT) : null;
        String tag = lower != null && KNOWN_PROVIDERS.contains(lower) ? lower : "other";
        Metrics.counter(ZERO_PRICED_CALLS, "provider", tag).increment();
    }

    /**
     * The statically known models of {@code task} that carry an explicit zero price
     * although their provider is not a local one, one message each, naming where it
     * is set. Shown at save and at deploy; never an error.
     */
    public static List<String> zeroPriceWarnings(LlmConfiguration.Task task) {
        List<String> warnings = new ArrayList<>();
        if (task == null) {
            return warnings;
        }
        String taskType = task.getType();
        LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade != null && cascade.isEnabled() && cascade.getSteps() != null && !cascade.getSteps().isEmpty()) {
            for (int i = 0; i < cascade.getSteps().size(); i++) {
                LlmConfiguration.CascadeStep step = cascade.getSteps().get(i);
                if (step == null) {
                    continue;
                }
                Double in = step.getInputPricePer1M() != null ? step.getInputPricePer1M() : cascade.getInputPricePer1M();
                Double out = step.getOutputPricePer1M() != null ? step.getOutputPricePer1M() : cascade.getOutputPricePer1M();
                note("cascade step " + i, step.getType() != null ? step.getType() : taskType, in, out, warnings);
            }
            LlmConfiguration.JudgeModelConfig judge = cascade.getJudgeModel();
            if (judge != null && judge.getType() != null) {
                note("cascade judge", judge.getType(), judge.getInputPricePer1M(), judge.getOutputPricePer1M(), warnings);
            }
        } else {
            note("model", taskType, task.getInputPricePer1M(), task.getOutputPricePer1M(), warnings);
        }
        return warnings;
    }

    private static void note(String what, String type, Double input, Double output, List<String> warnings) {
        if (isExplicitZero(input, output) && type != null && !type.contains("{") && !isLocal(type)) {
            warnings.add("the " + what + " (provider '" + type + "') has an explicit 0 price (input or output): cost ceilings, tenant budgets and "
                    + "unpricedPolicy do not see its spend. Fine for a model that is free; otherwise remove the price (the catalog then prices "
                    + "it) or set eddi.llm.pricing.catalog-floor=true to keep configurations from pricing below the catalog");
        }
    }
}
