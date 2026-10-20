/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleEndpoint;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deploy-time validation of every {@code openai-compatible} endpoint an LLM
 * configuration names — the task's own model, each cascade step (with the
 * task's parameters underneath, as the cascade merges them at run time) and the
 * cascade judge.
 * <p>
 * An endpoint without a {@code baseUrl} or {@code modelName}, or with a
 * capability flag that is not a boolean, can never work, so it fails the deploy
 * with a message naming the field instead of failing every turn — and a
 * misspelt {@code "ture"} is never read as a silent {@code false}. The type is
 * new, so no stored configuration predates this check. Values still holding a
 * template are left for run time, where they are checked again once resolved.
 */
final class OpenAiCompatibleConfigValidator {

    private OpenAiCompatibleConfigValidator() {
    }

    static void validate(LlmConfiguration config) throws WorkflowConfigurationException {
        if (config == null || config.tasks() == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (LlmConfiguration.Task task : config.tasks()) {
            String taskId = task.getId() != null ? task.getId() : "<unnamed>";
            if (OpenAiCompatibleEndpoint.isType(task.getType())) {
                collect("LLM task '" + taskId + "'", task.getParameters(), problems);
            }
            var cascade = task.getModelCascade();
            if (cascade == null) {
                continue;
            }
            if (cascade.getSteps() != null) {
                int index = 0;
                for (CascadeStep step : cascade.getSteps()) {
                    if (step != null && OpenAiCompatibleEndpoint.isType(step.getType())) {
                        collect("LLM task '" + taskId + "' cascade step " + index,
                                CascadingModelExecutor.mergeParams(task.getParameters(), step.getParameters()), problems);
                    }
                    index++;
                }
            }
            var judge = cascade.getJudgeModel();
            if (judge != null && OpenAiCompatibleEndpoint.isType(judge.getType())) {
                collect("LLM task '" + taskId + "' cascade judge", judge.getParameters(), problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new WorkflowConfigurationException(String.join("; ", problems));
        }
    }

    private static void collect(String where, Map<String, String> parameters, List<String> problems) {
        for (String problem : OpenAiCompatibleEndpoint.problems(parameters)) {
            problems.add(where + ": " + problem);
        }
    }
}
