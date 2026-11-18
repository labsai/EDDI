/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.utils.EndpointParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which parameters a cascade step may inherit from its task, and the
 * save/deploy check that a step of another provider brings its own credentials.
 * <p>
 * A secret can be restricted to one vendor's host. If a step of a
 * <em>different</em> provider type inherited the task's {@code apiKey}, that
 * key would be sent to the other vendor. So credentials, endpoints and
 * credential-bearing headers are inherited only when the step's provider type
 * equals the task's; the vendor-neutral tuning parameters (temperature,
 * timeouts, ...) and the model keys still inherit.
 */
public final class CascadeCredentialPolicy {

    private static final Set<String> CREDENTIAL_BOUND = Set.of("apikey", "accesstoken", "authtoken", "nonazureapikey", "signingsecret", "apppassword",
            "bottoken", "compartmentid", "configprofile", "projectid", "region", "location", "organizationid", "serviceversion", "customheaders",
            "customqueryparams", "customparameters",
            // account-bound resources of one vendor: a Bedrock guardrail, a Gemini cached
            // content
            "guardrailid", "guardrailversion", "guardrailstreammode", "cachedcontentname");

    /** The keys that are a credential in their own right (not an endpoint). */
    private static final Set<String> SECRET_KEYS = Set.of("apikey", "accesstoken", "authtoken", "nonazureapikey");

    /**
     * Provider types that authenticate without a per-step secret: local or
     * self-hosted servers, and providers that use the host's own identity.
     */
    private static final Set<String> NO_STEP_SECRET_TYPES = Set.of("ollama", "jlama", "openai-compatible", "bedrock", "gemini-vertex",
            "oracle-genai");

    private CascadeCredentialPolicy() {
    }

    /** Whether a parameter key is bound to the provider that holds it. */
    public static boolean isCredentialBound(String key) {
        return key != null && (EndpointParameters.isEndpointKey(key) || CREDENTIAL_BOUND.contains(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Whether two provider types name the same provider (null/blank step = the
     * task's).
     */
    public static boolean sameProvider(String taskType, String stepType) {
        if (stepType == null || stepType.isBlank()) {
            return true;
        }
        return taskType != null && taskType.trim().equalsIgnoreCase(stepType.trim());
    }

    /**
     * The steps that would have inherited a secret from a task of another provider
     * and bring none of their own, one message per step. Types that are still
     * templates are skipped (they are only known at run time, where the merge
     * itself refuses to inherit).
     */
    public static List<String> problems(LlmConfiguration config) {
        List<String> problems = new ArrayList<>();
        if (config == null || config.tasks() == null) {
            return problems;
        }
        for (LlmConfiguration.Task task : config.tasks()) {
            var cascade = task != null ? task.getModelCascade() : null;
            if (cascade == null || !cascade.isEnabled() || cascade.getSteps() == null || !hasSecret(task.getParameters())) {
                continue;
            }
            String taskType = task.getType();
            for (int i = 0; i < cascade.getSteps().size(); i++) {
                CascadeStep step = cascade.getSteps().get(i);
                String stepType = step != null ? step.getType() : null;
                if (stepType == null || stepType.isBlank() || isTemplate(stepType) || isTemplate(taskType) || sameProvider(taskType, stepType)
                        || NO_STEP_SECRET_TYPES.contains(stepType.trim().toLowerCase(Locale.ROOT)) || hasSecret(step.getParameters())) {
                    continue;
                }
                problems.add("task '" + (task.getId() != null ? task.getId() : "<unnamed>") + "' modelCascade step " + i + " runs on provider '"
                        + stepType + "' but the task's provider is '" + taskType + "' and the step defines no apiKey of its own — credentials are "
                        + "never inherited across provider types; give the step its own apiKey (a vault reference), endpoint and headers");
            }
        }
        return problems;
    }

    private static boolean hasSecret(Map<String, String> parameters) {
        if (parameters == null) {
            return false;
        }
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (parameter.getKey() != null && SECRET_KEYS.contains(parameter.getKey().toLowerCase(Locale.ROOT)) && parameter.getValue() != null
                    && !parameter.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTemplate(String type) {
        return type != null && type.indexOf('{') >= 0;
    }
}
