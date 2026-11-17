/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleSet;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavior rule condition that matches on the deployment environment.
 * Composable with {@code actionmatcher} — a rule can emit
 * {@code force_cautious} that triggers a {@code langchain} task with
 * {@code counterweight.level=strict}.
 * <p>
 * Configuration:
 *
 * <pre>{@code
 * {
 *   "type": "deploymentContext",
 *   "configs": {
 *     "when": "production"
 *   }
 * }
 * }</pre>
 * <p>
 * Passes when the current deployment env (system property
 * {@code eddi.deployment.env} → env var {@code EDDI_DEPLOYMENT_ENV} → default
 * {@code "development"}) matches {@code when}, ignoring case; a missing or
 * blank {@code when} matches every environment.
 * <p>
 * A non-blank {@code tagMatches} is refused by
 * {@link #validateConfiguration()}. It used to require the agent's tags to
 * contain the value, but agents carry no tags, so such a rule could never fire.
 *
 * @since 6.0.0
 */
public class DeploymentContextCondition implements IRuleCondition {

    public static final String ID = "deploymentContext";

    private static final String CONFIG_WHEN = "when";
    private static final String CONFIG_TAG_MATCHES = "tagMatches";

    private String when;
    private String tagMatches;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        if (when != null) {
            configs.put(CONFIG_WHEN, when);
        }
        if (tagMatches != null) {
            configs.put(CONFIG_TAG_MATCHES, tagMatches);
        }
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null) {
            this.when = configs.get(CONFIG_WHEN);
            this.tagMatches = configs.get(CONFIG_TAG_MATCHES);
        } else {
            this.when = null;
            this.tagMatches = null;
        }
    }

    /**
     * Refuses a non-blank {@code tagMatches}. Nothing has ever written agent tags —
     * {@code AgentConfiguration} has no tags field — so the condition always
     * failed, and a safety rule such as "be cautious for high-risk agents in
     * production" silently never fired. Refusing it names the rule when the ruleset
     * is saved or deployed instead.
     */
    @Override
    public void validateConfiguration() {
        if (tagMatches != null && !tagMatches.isBlank()) {
            throw new IllegalArgumentException(String.format("'%s' is not supported: agents carry no tags, so this condition could never"
                    + " match. Remove it, or match a value the client sends with a 'contextmatcher' condition.", CONFIG_TAG_MATCHES));
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        if (when == null || when.isBlank()) {
            return ExecutionState.SUCCESS;
        }

        return when.equalsIgnoreCase(resolveDeploymentEnv()) ? ExecutionState.SUCCESS : ExecutionState.FAIL;
    }

    /**
     * Resolve the deployment environment from MicroProfile Config (which reads
     * system properties, env vars, and application.properties).
     */
    String resolveDeploymentEnv() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue("eddi.deployment.env", String.class)
                    .orElse("development");
        } catch (Exception e) {
            return "development";
        }
    }

    @Override
    public void setConditions(List<IRuleCondition> conditions) {
        // leaf condition — no sub-conditions
    }

    @Override
    public void setContainingRuleSet(RuleSet ruleSet) {
        // not needed
    }

    @Override
    public IRuleCondition clone() {
        DeploymentContextCondition clone = new DeploymentContextCondition();
        clone.setConfigs(this.getConfigs());
        return clone;
    }
}
