/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.impl.LlmTask;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deploy-time check: every statically known model an LLM task calls — its own
 * model, each cascade step, the cascade's judge, the rolling-summary model and
 * the tool-response summarizer — either has an explicit price on its config or
 * a catalog entry. What has neither is reported as a deployment warning
 * ({@code AgentDeploymentStatus.warnings} and the log): its spend will count $0
 * against every budget. A model name that is a template cannot be priced until
 * it is rendered and is reported as checked per call.
 * <p>
 * Advisory: it never fails a deployment. Under a refusing
 * {@link UnpricedPolicy} the warning says so — the calls themselves are refused
 * by {@code UnpricedCallPolicy}.
 */
public final class PricingLint {

    private PricingLint() {
    }

    /** Finding code, as {@code AgentCompatibilityLint} renders it. */
    public static final String CODE = "unpriced-model";

    /** The warning messages for one task, against {@code catalog}. */
    public static List<String> lint(LlmConfiguration.Task task, PriceCatalogSnapshot catalog) {
        Set<String> warnings = new LinkedHashSet<>();
        if (task == null) {
            return List.of();
        }
        String taskType = task.getType();
        String taskModel = modelOf(task.getParameters(), taskType);
        String suffix = suffix(catalog.effectivePolicy());
        LlmConfiguration.ModelCascadeConfig cascade = task.getModelCascade();
        boolean cascading = cascade != null && cascade.isEnabled() && cascade.getSteps() != null && !cascade.getSteps().isEmpty();

        if (!cascading) {
            check("model", taskType, taskModel, PriceOverride.of(task.getInputPricePer1M(), task.getOutputPricePer1M()), catalog, suffix,
                    warnings);
        } else {
            for (int i = 0; i < cascade.getSteps().size(); i++) {
                LlmConfiguration.CascadeStep step = cascade.getSteps().get(i);
                if (step == null) {
                    continue;
                }
                String type = step.getType() != null ? step.getType() : taskType;
                Double in = step.getInputPricePer1M() != null ? step.getInputPricePer1M() : cascade.getInputPricePer1M();
                Double out = step.getOutputPricePer1M() != null ? step.getOutputPricePer1M() : cascade.getOutputPricePer1M();
                check("cascade step " + i, type, modelOf(step.getParameters(), type), PriceOverride.of(in, out), catalog, suffix,
                        warnings);
            }
            LlmConfiguration.JudgeModelConfig judge = cascade.getJudgeModel();
            if (judge != null && judge.getType() != null) {
                check("cascade judge", judge.getType(), modelOf(judge.getParameters(), judge.getType()),
                        PriceOverride.of(judge.getInputPricePer1M(), judge.getOutputPricePer1M()), catalog, suffix, warnings);
            }
        }

        LlmConfiguration.ConversationSummaryConfig summary = task.getConversationSummary();
        if (summary != null && summary.isEnabled()) {
            String type = summary.getLlmProvider() != null && !summary.getLlmProvider().isBlank() ? summary.getLlmProvider() : taskType;
            String model = summary.getLlmModel() != null && !summary.getLlmModel().isBlank() ? summary.getLlmModel() : taskModel;
            boolean sameModel = type != null && type.equalsIgnoreCase(taskType) && model != null && model.equalsIgnoreCase(taskModel);
            PriceOverride own = sameModel ? PriceOverride.of(task.getInputPricePer1M(), task.getOutputPricePer1M()) : PriceOverride.NONE;
            check("conversation summary", type, model, own, catalog, suffix, warnings);
        }

        if (task.getToolResponseLimits() != null && task.getToolResponseLimits().getSummarizerModel() != null
                && !task.getToolResponseLimits().getSummarizerModel().isBlank()) {
            String model = task.getToolResponseLimits().getSummarizerModel();
            PriceOverride own = model.equalsIgnoreCase(taskModel)
                    ? PriceOverride.of(task.getInputPricePer1M(), task.getOutputPricePer1M())
                    : PriceOverride.NONE;
            check("tool summarizer", taskType, model, own, catalog, suffix, warnings);
        }
        return new ArrayList<>(warnings);
    }

    private static void check(String what, String type, String model, PriceOverride own, PriceCatalogSnapshot catalog,
                              String suffix, Set<String> warnings) {
        if (own.inputPer1M() != null || own.outputPer1M() != null) {
            return;
        }
        if (isTemplated(type) || isTemplated(model)) {
            warnings.add("the " + what + " is chosen at run time and cannot be priced at deploy time; "
                    + "it is checked per call" + suffix);
            return;
        }
        if (model == null || model.isBlank()) {
            return;
        }
        if (!ModelPriceResolver.isPriced(own, type, model, catalog)) {
            warnings.add("the " + what + " '" + type + "/" + model + "' has no price — set "
                    + "inputPricePer1M/outputPricePer1M or add a catalog entry under Model Prices; until then its spend counts $0 against "
                    + "every budget" + suffix);
        }
    }

    private static String suffix(UnpricedPolicy policy) {
        return switch (policy) {
            case REFUSE -> " (unpricedPolicy=REFUSE: its calls are refused)";
            case REFUSE_WHEN_BUDGETED -> " (unpricedPolicy=REFUSE_WHEN_BUDGETED: its calls are refused while a dollar ceiling applies)";
            case ALLOW -> "";
        };
    }

    private static String modelOf(Map<String, String> parameters, String type) {
        if (parameters == null) {
            return null;
        }
        try {
            return LlmTask.resolveModelName(parameters, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isTemplated(String value) {
        return value != null && (value.contains("{") || value.contains("${"));
    }
}
