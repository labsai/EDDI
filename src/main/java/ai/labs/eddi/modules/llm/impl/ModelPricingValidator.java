/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogValidator;
import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ModelPricing;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the optional {@code pricing} object of an LLM task and of its
 * cascade steps: every price finite and within [0, 10 000]. The field is new,
 * so no stored config predates it, and a bad value fails the deployment like a
 * negative {@code inputPricePer1M} does.
 */
final class ModelPricingValidator {

    private ModelPricingValidator() {
    }

    /** Throws when the task's or any cascade step's {@code pricing} is invalid. */
    static void validate(String taskId, LlmConfiguration.Task task) throws WorkflowConfigurationException {
        List<String> violations = new ArrayList<>();
        check("pricing", task.getPricing(), violations);
        LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
        if (cascade != null && cascade.getSteps() != null) {
            for (int i = 0; i < cascade.getSteps().size(); i++) {
                LlmConfiguration.CascadeStep step = cascade.getSteps().get(i);
                if (step != null) {
                    check("modelCascade.steps[" + i + "].pricing", step.getPricing(), violations);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new WorkflowConfigurationException("Invalid LLM task '" + taskId + "': " + String.join("; ", violations));
        }
    }

    static void check(String where, ModelPricing pricing, List<String> violations) {
        if (pricing == null) {
            return;
        }
        ModelPriceCatalogValidator.price(pricing.cacheReadPricePer1M(), where + ".cacheReadPricePer1M", violations);
        ModelPriceCatalogValidator.price(pricing.cacheWritePricePer1M(), where + ".cacheWritePricePer1M", violations);
        LongContextTier tier = pricing.longContext();
        if (tier != null) {
            ModelPriceCatalogValidator.price(tier.inputPricePer1M(), where + ".longContext.inputPricePer1M", violations);
            ModelPriceCatalogValidator.price(tier.outputPricePer1M(), where + ".longContext.outputPricePer1M", violations);
            ModelPriceCatalogValidator.price(tier.cacheReadPricePer1M(), where + ".longContext.cacheReadPricePer1M", violations);
            ModelPriceCatalogValidator.price(tier.cacheWritePricePer1M(), where + ".longContext.cacheWritePricePer1M", violations);
        }
    }
}
