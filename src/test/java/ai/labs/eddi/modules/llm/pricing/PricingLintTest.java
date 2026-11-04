/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingLintTest {

    private static PriceCatalogSnapshot catalog(UnpricedPolicy policy) {
        return PriceCatalogSnapshot.of(new ModelPriceCatalog(1, policy, null, null, List.of(ModelPriceEntry.of("openai", "gpt-4o*", 2.5, 10.0))),
                UnpricedPolicy.ALLOW);
    }

    private static LlmConfiguration.Task task(String type, String model) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType(type);
        Map<String, String> params = new HashMap<>();
        params.put("modelName", model);
        task.setParameters(params);
        return task;
    }

    @Test
    void cataloguedOrExplicitlyPricedModelsAreClean() {
        assertEquals(List.of(), PricingLint.lint(task("openai", "gpt-4o-mini"), catalog(UnpricedPolicy.ALLOW)));
        var priced = task("openai", "my-finetune");
        priced.setInputPricePer1M(0.0);
        assertEquals(List.of(), PricingLint.lint(priced, catalog(UnpricedPolicy.ALLOW)));
    }

    @Test
    void anUnpricedModelIsReportedWithThePolicyConsequence() {
        List<String> allow = PricingLint.lint(task("openai", "my-finetune"), catalog(UnpricedPolicy.ALLOW));
        assertEquals(1, allow.size());
        assertTrue(allow.getFirst().contains("openai/my-finetune"), allow.getFirst());
        List<String> refuse = PricingLint.lint(task("openai", "my-finetune"), catalog(UnpricedPolicy.REFUSE));
        assertTrue(refuse.getFirst().contains("refused"), refuse.getFirst());
    }

    @Test
    void aTemplatedModelIsCheckedPerCall() {
        List<String> warnings = PricingLint.lint(task("openai", "{context.model}"), catalog(UnpricedPolicy.ALLOW));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("checked per call"));
    }

    @Test
    void cascadeStepsAndASummaryOnAnotherModelAreChecked() {
        var task = task("openai", "gpt-4o");
        var step = new LlmConfiguration.CascadeStep();
        step.setType("anthropic");
        step.setParameters(new HashMap<>(Map.of("modelName", "claude-x")));
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));
        task.setModelCascade(cascade);
        var summary = new LlmConfiguration.ConversationSummaryConfig();
        summary.setEnabled(true);
        summary.setLlmModel("cheap-model");
        task.setConversationSummary(summary);

        List<String> warnings = PricingLint.lint(task, catalog(UnpricedPolicy.ALLOW));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("cascade step 0"));
        assertTrue(warnings.get(1).contains("conversation summary"));
    }
}
