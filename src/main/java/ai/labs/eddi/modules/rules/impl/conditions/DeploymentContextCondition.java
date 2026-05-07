/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleSet;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavior rule condition that matches on the deployment environment and
 * optional agent tags. Composable with {@code actionmatcher} — a rule can emit
 * {@code force_cautious} that triggers a {@code langchain} task with
 * {@code counterweight.level=strict}.
 * <p>
 * Configuration:
 *
 * <pre>{@code
 * {
 *   "type": "deploymentContext",
 *   "configs": {
 *     "when": "production",
 *     "tagMatches": "high-risk"
 *   }
 * }
 * }</pre>
 * <p>
 * Passes when the current deployment env (system property
 * {@code eddi.deployment.env} → env var {@code EDDI_DEPLOYMENT_ENV} → default
 * {@code "development"}) matches {@code when}, AND if {@code tagMatches} is
 * set, the current agent's tags list (from context) contains that value.
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
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        // Resolve current deployment env
        String currentEnv = resolveDeploymentEnv();

        // Check "when" match
        if (when != null && !when.isBlank()) {
            if (!when.equalsIgnoreCase(currentEnv)) {
                return ExecutionState.FAIL;
            }
        }

        // Check "tagMatches" if configured
        if (tagMatches != null && !tagMatches.isBlank()) {
            IData<List<String>> tagsData = memory.getCurrentStep().getLatestData("agent:tags");
            if (tagsData == null || tagsData.getResult() == null) {
                return ExecutionState.FAIL;
            }
            boolean found = tagsData.getResult().stream()
                    .anyMatch(tag -> tagMatches.equalsIgnoreCase(tag));
            if (!found) {
                return ExecutionState.FAIL;
            }
        }

        return ExecutionState.SUCCESS;
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
