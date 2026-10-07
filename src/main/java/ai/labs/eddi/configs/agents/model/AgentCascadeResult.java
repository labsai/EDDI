/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a cascade did: the new agent version, the workflow versions it wrote,
 * and the deployment outcome when a redeploy was requested.
 *
 * @param agentId
 *            the agent
 * @param previousAgentVersion
 *            the version the cascade started from
 * @param newAgentVersion
 *            the version it wrote (equal to {@code previousAgentVersion} when
 *            nothing needed writing)
 * @param workflows
 *            one entry per workflow whose reference in the agent changed
 * @param totalWorkflows
 *            how many workflows the agent references
 * @param mappingsApplied
 *            how many of the requested mappings matched a reference
 * @param compatibleWithPreviousVersion
 *            the compatibility the new agent version was written with
 * @param deployment
 *            the redeploy outcome, {@code null} when none was requested
 */
public record AgentCascadeResult(String agentId, int previousAgentVersion, int newAgentVersion, List<WorkflowChange> workflows, int totalWorkflows,
        int mappingsApplied, boolean compatibleWithPreviousVersion, Deployment deployment) {

    /**
     * One workflow whose reference in the agent changed.
     *
     * @param workflowId
     *            the workflow
     * @param previousVersion
     *            the version the agent referenced before
     * @param newVersion
     *            the version it references now
     * @param repointed
     *            the agent was pointed at a different workflow version because a
     *            mapping named it
     * @param stepsUpdated
     *            a new workflow version was written because step references changed
     */
    public record WorkflowChange(String workflowId, int previousVersion, int newVersion, boolean repointed, boolean stepsUpdated) {
    }

    /**
     * The redeploy outcome.
     *
     * @param environment
     *            where it was deployed; {@code null} when the deploy call itself
     *            failed before reporting anything
     * @param redeployed
     *            the deployment reached READY with no error
     * @param status
     *            the deployment status the deploy reported, if any
     * @param error
     *            why it failed, if it did
     * @param previousVersionStillDeployed
     *            a deploy never retires the version it supersedes
     */
    public record Deployment(String environment, boolean redeployed, String status, String error, boolean previousVersionStillDeployed) {
    }

    /**
     * The flat shape the MCP {@code apply_agent_changes} tool has always returned,
     * plus the per-workflow detail. Kept here so the two surfaces cannot report one
     * cascade differently.
     */
    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("agentId", agentId);
        result.put("previousAgentVersion", previousAgentVersion);
        result.put("newAgentVersion", newAgentVersion);
        result.put("updatedWorkflows", workflows.size());
        if (!workflows.isEmpty()) {
            result.put("compatibleWithPreviousVersion", compatibleWithPreviousVersion);
        }
        result.put("totalWorkflows", totalWorkflows);
        result.put("mappingsApplied", mappingsApplied);
        List<Map<String, Object>> workflowList = new ArrayList<>();
        for (WorkflowChange change : workflows) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("workflowId", change.workflowId());
            entry.put("previousVersion", change.previousVersion());
            entry.put("newVersion", change.newVersion());
            entry.put("repointed", change.repointed());
            entry.put("stepsUpdated", change.stepsUpdated());
            workflowList.add(entry);
        }
        result.put("workflows", workflowList);
        if (deployment != null) {
            result.put("redeployed", deployment.redeployed());
            if (deployment.environment() != null) {
                result.put("environment", deployment.environment());
            }
            if (deployment.status() != null) {
                result.put("deploymentStatus", deployment.status());
            }
            if (deployment.error() != null) {
                result.put("deployError", deployment.error());
            }
            if (deployment.environment() != null) {
                result.put("previousVersionStillDeployed", deployment.previousVersionStillDeployed());
            }
        }
        return result;
    }
}
