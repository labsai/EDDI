/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.LenientConfigWrite;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Save-time structural check of a {@link WorkflowConfiguration}. Only what
 * fails at deployment is refused, each rule with the runtime code that fails:
 * <ul>
 * <li>a step without a {@code type}: {@code WorkflowStoreClientLibrary
 * .createExecutableWorkflow} dereferences it (a bare NullPointerException that
 * fails the deployment);</li>
 * <li>an {@code eddi:} type without a host, or a host that is not a registered
 * lifecycle task ({@link LifecycleExtensions}, aliases included):
 * {@code UnrecognizedExtensionException} at deployment. A type with another
 * scheme is skipped there with a warning and is not refused;</li>
 * <li>for the steps whose {@code configure()} throws without a resource
 * (behavior/rules, httpcalls/apicalls, llm/langchain, mcpcalls, rag): a missing
 * or blank {@code config.uri}; for every step: a {@code config.uri} that is not
 * a URI or names no resource id. The value is trimmed first, as
 * {@code ResourceUris.require} does. {@code ai.labs.output} is not on the list:
 * without a uri it is a context-only output step
 * ({@code OutputGenerationTask.configure} returns null and {@code execute}
 * still emits context output and quick replies).</li>
 * </ul>
 * <b>Not checked</b>: that the referenced resource exists — a workflow may be
 * saved before its configs, and deploy reports a missing one — and duplicate
 * steps, which the engine allows.
 * <p>
 * An interactive save is refused with an {@link IllegalArgumentException}
 * (400); inside {@link LenientConfigWrite} the same findings are only logged,
 * and a caller that reports them collects them with
 * {@link #problems(WorkflowConfiguration)}.
 */
@ApplicationScoped
public class WorkflowConfigSaveValidator {

    private static final Logger LOGGER = Logger.getLogger(WorkflowConfigSaveValidator.class);

    private static final String WHAT = "workflow";
    private static final String SCHEME = "eddi";
    private static final String KEY_URI = "uri";

    /**
     * The step types whose task refuses to configure without a {@code config.uri}:
     * {@code RulesEvaluationTask}, {@code ApiCallsTask}, {@code LlmTask},
     * {@code McpCallsTask} and {@code RagTask} throw from {@code configure()}.
     */
    static final Set<String> NEEDS_RESOURCE_URI = Set.of("ai.labs.behavior", "ai.labs.rules", "ai.labs.httpcalls", "ai.labs.apicalls",
            "ai.labs.llm", "ai.labs.langchain", "ai.labs.mcpcalls", "ai.labs.rag");

    /**
     * The step types that load {@code config.uri} when it is set and run without
     * one: {@code OutputGenerationTask} and {@code PropertySetterTask}.
     */
    static final Set<String> LOADS_RESOURCE_URI_IF_SET = Set.of("ai.labs.output", "ai.labs.property");

    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensions;

    @Inject
    public WorkflowConfigSaveValidator(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensions) {
        this.lifecycleExtensions = lifecycleExtensions;
    }

    /**
     * Refuses an interactive save that cannot deploy; inside
     * {@link LenientConfigWrite} only logs.
     */
    public static void validate(WorkflowConfiguration workflow) {
        List<String> problems = problems(workflow);
        if (problems.isEmpty()) {
            return;
        }
        if (!LenientConfigWrite.isActive()) {
            throw new IllegalArgumentException("Invalid " + String.join("; ", problems));
        }
        problems.forEach(problem -> LOGGER.warn("Workflow written by import, sync, copy or setup: " + LogSanitizer.sanitize(problem)));
    }

    /**
     * Every problem found, prefixed with the kind of document. Resolves the step
     * types through the CDI-managed registry; where no container is running (a
     * store built by hand in a unit test) only the shape is checked.
     */
    public static List<String> problems(WorkflowConfiguration workflow) {
        Predicate<String> known = null;
        try {
            WorkflowConfigSaveValidator bean = CDI.current().select(WorkflowConfigSaveValidator.class).get();
            known = bean.lifecycleExtensions::containsKey;
        } catch (RuntimeException noContainer) {
            // shape-only check
        }
        List<String> problems = new ArrayList<>();
        for (String problem : findProblems(workflow, known)) {
            problems.add(WHAT + ": " + problem);
        }
        return problems;
    }

    /**
     * @param knownStepType
     *            whether a step type's host is registered; null skips that check
     */
    public static List<String> findProblems(WorkflowConfiguration workflow, Predicate<String> knownStepType) {
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
            } else if (SCHEME.equals(type.getScheme())) {
                host = type.getHost();
                if (host == null) {
                    problems.add(path + ".type '" + type + "' must be an eddi://ai.labs.<step> URI");
                } else if (knownStepType != null && !knownStepType.test(host)) {
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
            // Absent is fine for output; present but blank is not, because
            // OutputGenerationTask hands any non-null uri to ResourceUris.require.
            boolean refused = stepHost != null
                    && (NEEDS_RESOURCE_URI.contains(stepHost) || uri != null && "ai.labs.output".equals(stepHost));
            if (refused) {
                problems.add(path + ".config.uri is required for " + stepHost + ": point it at a saved configuration");
            }
            return;
        }
        if (stepHost == null || !NEEDS_RESOURCE_URI.contains(stepHost) && !LOADS_RESOURCE_URI_IF_SET.contains(stepHost)) {
            // A step whose task does not read config.uri (parser, templating, …).
            return;
        }
        String trimmed = uri.toString().trim();
        try {
            var resourceId = RestUtilities.extractResourceId(URI.create(trimmed));
            if (resourceId == null || resourceId.getId() == null || resourceId.getId().isBlank()) {
                problems.add(path + ".config.uri '" + trimmed + "' does not name a resource: expected eddi://<store>/<id>?version=<n>");
            }
        } catch (RuntimeException e) {
            problems.add(path + ".config.uri '" + trimmed + "' is not a valid URI");
        }
    }
}
