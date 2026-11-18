/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.validation.ConfigValidationPolicy;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Save-time structural check of a {@link WorkflowConfiguration}: every step
 * names a lifecycle task the engine has, and a step that needs a resource names
 * it with a well-formed reference.
 * <p>
 * The step type is resolved exactly like deployment does it
 * ({@code WorkflowStoreClientLibrary}): the host of an {@code eddi://} URI is
 * looked up in the {@link LifecycleExtensions} registry. <b>Not checked</b>:
 * that the referenced resource exists — a workflow may be saved before its
 * configs, and deploy reports a missing one — and duplicate steps, which the
 * engine allows (a task's component is keyed by its position, so two
 * {@code httpcalls} steps are an ordinary workflow).
 * <p>
 * How a finding is handled (refusal or warning) is decided by
 * {@link ConfigValidationPolicy}, not here.
 */
@ApplicationScoped
public class WorkflowConfigSaveValidator {

    private static final String WHAT = "workflow";
    private static final String SCHEME = "eddi";
    private static final String KEY_URI = "uri";

    /**
     * The step types whose task refuses to configure without a {@code config.uri}
     * (their {@code configure()} throws "No resource URI has been defined").
     */
    private static final Set<String> NEEDS_RESOURCE_URI = Set.of("ai.labs.behavior", "ai.labs.rules", "ai.labs.httpcalls",
            "ai.labs.apicalls", "ai.labs.llm", "ai.labs.langchain", "ai.labs.mcpcalls", "ai.labs.output");

    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensions;

    @Inject
    public WorkflowConfigSaveValidator(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensions) {
        this.lifecycleExtensions = lifecycleExtensions;
    }

    /**
     * Validates through the CDI-managed instance. Where no container is running (a
     * store built by hand in a unit test) the step types cannot be resolved and
     * only the shape of the references is checked.
     */
    public static void validate(WorkflowConfiguration workflow) {
        Predicate<String> known = null;
        try {
            WorkflowConfigSaveValidator bean = CDI.current().select(WorkflowConfigSaveValidator.class).get();
            known = bean.lifecycleExtensions::containsKey;
        } catch (RuntimeException noContainer) {
            // shape-only check
        }
        ConfigValidationPolicy.report(WHAT, problems(workflow, known));
    }

    /**
     * @param knownStepType
     *            whether a step type's host is registered; null skips that check
     */
    static List<String> problems(WorkflowConfiguration workflow, Predicate<String> knownStepType) {
        List<String> problems = new ArrayList<>();
        if (workflow == null || workflow.getWorkflowSteps() == null) {
            return problems;
        }
        List<WorkflowConfiguration.WorkflowStep> steps = workflow.getWorkflowSteps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowConfiguration.WorkflowStep step = steps.get(i);
            if (step == null) {
                continue; // reported by the null-element check the store already runs
            }
            String path = "workflowSteps[" + i + "]";
            URI type = step.getType();
            String host = null;
            if (type == null) {
                problems.add(path + ".type is required: name a step such as eddi://ai.labs.parser");
            } else if (!SCHEME.equals(type.getScheme()) || type.getHost() == null) {
                problems.add(path + ".type '" + type + "' must be an eddi://ai.labs.<step> URI");
            } else {
                host = type.getHost();
                if (knownStepType != null && !knownStepType.test(host)) {
                    problems.add(path + ".type '" + type + "' is not a known workflow step");
                }
            }
            checkConfigUri(path, host, step.getConfig(), problems);
        }
        return problems;
    }

    private static void checkConfigUri(String path, String stepHost, Map<String, Object> config, List<String> problems) {
        Object uri = config == null ? null : config.get(KEY_URI);
        if (uri == null || uri.toString().isBlank()) {
            if (stepHost != null && NEEDS_RESOURCE_URI.contains(stepHost)) {
                problems.add(path + ".config.uri is required for " + stepHost + ": point it at a saved configuration");
            }
            return;
        }
        try {
            var resourceId = RestUtilities.extractResourceId(URI.create(uri.toString()));
            if (resourceId == null || resourceId.getId() == null || resourceId.getId().isBlank()) {
                problems.add(path + ".config.uri '" + uri + "' does not name a resource: expected eddi://<store>/<id>?version=<n>");
            }
        } catch (RuntimeException e) {
            problems.add(path + ".config.uri '" + uri + "' is not a valid URI");
        }
    }
}
