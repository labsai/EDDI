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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cascade, judge, summary and tool-summarizer branches of
 * {@link PricingLint} that {@link PricingLintTest} leaves out.
 */
class PricingLintBranchTest {

    private static final PriceCatalogSnapshot ALLOW = catalog(UnpricedPolicy.ALLOW);

    private static PriceCatalogSnapshot catalog(UnpricedPolicy policy) {
        return PriceCatalogSnapshot.of(new ModelPriceCatalog(1, policy, null, null, List.of(ModelPriceEntry.of("openai", "gpt-4o*", 2.5, 10.0))),
                UnpricedPolicy.ALLOW);
    }

    private static Map<String, String> model(String name) {
        Map<String, String> params = new HashMap<>();
        params.put("modelName", name);
        return params;
    }

    private static LlmConfiguration.Task task(String type, String model) {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType(type);
        task.setParameters(model(model));
        return task;
    }

    private static LlmConfiguration.ModelCascadeConfig cascade(LlmConfiguration.CascadeStep... steps) {
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(new ArrayList<>(List.of(steps)));
        return cascade;
    }

    private static LlmConfiguration.CascadeStep step(String type, String model) {
        var step = new LlmConfiguration.CascadeStep();
        step.setType(type);
        step.setParameters(model(model));
        return step;
    }

    @Test
    void aNullTaskHasNoFindings() {
        assertEquals(List.of(), PricingLint.lint(null, ALLOW));
    }

    @Test
    void theBudgetedRefusalIsNamedInTheWarning() {
        var warnings = PricingLint.lint(task("openai", "my-finetune"), catalog(UnpricedPolicy.REFUSE_WHEN_BUDGETED));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().endsWith("while a dollar ceiling applies)"), warnings.getFirst());
    }

    @Test
    void aTaskWithoutAModelHasNothingToPrice() {
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void aTemplatedTypeIsCheckedPerCall() {
        var warnings = PricingLint.lint(task("${vars.provider}", "gpt-4o"), ALLOW);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("the model is chosen at run time"), warnings.getFirst());
    }

    @Test
    void aDisabledCascadeChecksTheTaskModel() {
        var task = task("openai", "my-finetune");
        var cascade = cascade(step("openai", "gpt-4o"));
        cascade.setEnabled(false);
        task.setModelCascade(cascade);
        var warnings = PricingLint.lint(task, ALLOW);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("the model 'openai/my-finetune'"), warnings.getFirst());

        task.setModelCascade(cascade());
        assertEquals(1, PricingLint.lint(task, ALLOW).size(), "a cascade without steps is not a cascade");
    }

    @Test
    void cascadeStepsInheritTheTaskTypeAndTheCascadePrices() {
        var task = task("openai", "gpt-4o");
        var steps = new LlmConfiguration.ModelCascadeConfig();
        steps.setEnabled(true);
        var list = new ArrayList<LlmConfiguration.CascadeStep>();
        list.add(step(null, "my-finetune"));
        list.add(null);
        steps.setSteps(list);
        task.setModelCascade(steps);
        var warnings = PricingLint.lint(task, ALLOW);
        assertEquals(List.of("cascade step 0"), warnings.stream().map(w -> w.substring(4, w.indexOf(" '"))).toList());
        assertTrue(warnings.getFirst().contains("'openai/my-finetune'"), warnings.getFirst());

        steps.setInputPricePer1M(1.0);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW), "the cascade-level price covers the step");
        steps.setInputPricePer1M(null);
        steps.setOutputPricePer1M(4.0);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void aStepsOwnPriceWinsOverTheCascades() {
        var task = task("openai", "gpt-4o");
        var priced = step("anthropic", "claude-x");
        priced.setOutputPricePer1M(15.0);
        priced.setInputPricePer1M(3.0);
        task.setModelCascade(cascade(priced));
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void anUnpricedJudgeIsReportedAndAPricedOneIsNot() {
        var task = task("openai", "gpt-4o");
        var cascade = cascade(step("openai", "gpt-4o-mini"));
        var judge = new LlmConfiguration.JudgeModelConfig();
        judge.setType("anthropic");
        judge.setParameters(model("judge-x"));
        cascade.setJudgeModel(judge);
        task.setModelCascade(cascade);
        var warnings = PricingLint.lint(task, ALLOW);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("the cascade judge 'anthropic/judge-x'"), warnings.getFirst());

        judge.setInputPricePer1M(1.0);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));

        judge.setType(null);
        judge.setInputPricePer1M(null);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW), "a judge without a type is not configured");
    }

    @Test
    void aSummaryOnTheTaskModelUsesTheTaskPrice() {
        var task = task("openai", "my-finetune");
        task.setInputPricePer1M(1.0);
        var summary = new LlmConfiguration.ConversationSummaryConfig();
        summary.setEnabled(true);
        summary.setLlmProvider(" ");
        summary.setLlmModel("MY-FINETUNE");
        task.setConversationSummary(summary);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));

        summary.setLlmProvider("anthropic");
        var warnings = PricingLint.lint(task, ALLOW);
        assertEquals(1, warnings.size(), "another provider does not share the task's price");
        assertTrue(warnings.getFirst().startsWith("the conversation summary 'anthropic/MY-FINETUNE'"), warnings.getFirst());

        summary.setEnabled(false);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void aSummaryWithoutAnyModelIsSkipped() {
        var task = new LlmConfiguration.Task();
        task.setType("openai");
        var summary = new LlmConfiguration.ConversationSummaryConfig();
        summary.setEnabled(true);
        task.setConversationSummary(summary);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void theToolSummarizerIsCheckedAndSharesThePriceOfTheTaskModel() {
        var task = task("openai", "my-finetune");
        task.setOutputPricePer1M(2.0);
        var limits = new LlmConfiguration.ToolResponseLimits();
        limits.setSummarizerModel("cheap-model");
        task.setToolResponseLimits(limits);
        var warnings = PricingLint.lint(task, ALLOW);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("the tool summarizer 'openai/cheap-model'"), warnings.getFirst());

        limits.setSummarizerModel("My-Finetune");
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));

        limits.setSummarizerModel(" ");
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
        limits.setSummarizerModel(null);
        assertEquals(List.of(), PricingLint.lint(task, ALLOW));
    }

    @Test
    void templatedDetection() {
        assertTrue(PricingLint.isTemplated("{x}"));
        assertTrue(PricingLint.isTemplated("a${b}"));
        assertEquals(false, PricingLint.isTemplated("plain"));
        assertEquals(false, PricingLint.isTemplated(null));
    }
}
