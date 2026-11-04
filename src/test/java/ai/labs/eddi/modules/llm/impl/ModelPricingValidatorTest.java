/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.pricing.model.LongContextTier;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ModelPricing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPricingValidatorTest {

    private static LlmConfiguration.Task task(ModelPricing pricing) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setPricing(pricing);
        return task;
    }

    @Test
    void validPricingPasses() {
        assertDoesNotThrow(
                () -> ModelPricingValidator.validate("t", task(new ModelPricing(0.3, 3.75, new LongContextTier(null, 6.0, 22.5, 0.0, null)))));
        assertDoesNotThrow(() -> ModelPricingValidator.validate("t", task(null)));
    }

    @Test
    void negativeOrNonFiniteOrHugePricesFailDeployment() {
        assertThrows(WorkflowConfigurationException.class, () -> ModelPricingValidator.validate("t", task(new ModelPricing(-1.0, null, null))));
        assertThrows(WorkflowConfigurationException.class,
                () -> ModelPricingValidator.validate("t", task(new ModelPricing(null, Double.NaN, null))));
        assertThrows(WorkflowConfigurationException.class,
                () -> ModelPricingValidator.validate("t", task(new ModelPricing(null, null, new LongContextTier(null, 10_001.0, null, null, null)))));
    }

    @Test
    void cascadeStepPricingIsValidatedThroughTheCascadeValidator() {
        var step = new LlmConfiguration.CascadeStep();
        step.setPricing(new ModelPricing(-0.5, null, null));
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setSteps(List.of(step));
        var task = task(null);
        task.setModelCascade(cascade);
        var e = assertThrows(WorkflowConfigurationException.class, () -> CascadeConfigValidator.validate(new LlmConfiguration(List.of(task))));
        assertTrue(e.getMessage().contains("modelCascade.steps[0].pricing.cacheReadPricePer1M"), e.getMessage());
    }

    @Test
    void pricingRoundTripsThroughJsonAndOldConfigsHaveNone() throws Exception {
        var mapper = new ObjectMapper();
        var json = "{\"type\":\"anthropic\",\"inputPricePer1M\":3.0,\"pricing\":{\"cacheReadPricePer1M\":0.3,"
                + "\"longContext\":{\"inputPricePer1M\":6.0}}}";
        var task = mapper.readValue(json, LlmConfiguration.Task.class);
        assertEquals(0.3, task.getPricing().cacheReadPricePer1M());
        assertEquals(6.0, task.getPricing().longContext().inputPricePer1M());
        assertEquals(null, mapper.readValue("{\"type\":\"openai\"}", LlmConfiguration.Task.class).getPricing());
    }
}
