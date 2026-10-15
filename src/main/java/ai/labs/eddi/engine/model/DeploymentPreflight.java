/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a deploy of one agent version would run into, asked before deploying —
 * today the vault-grant check, which refuses every NEW agent that uses a
 * restricted secret, because a grant names agent ids and the id did not exist
 * when the grant was written. A UI runs this right after creating an agent, so
 * the grant can be settled before the first deploy instead of after it fails.
 *
 * @param agentId
 *            the agent
 * @param version
 *            the version checked
 * @param enforcement
 *            {@code ENFORCE}, {@code WARN} or {@code OFF}
 *            ({@code eddi.vault.grant-enforcement})
 * @param checked
 *            false when the check did not run — mode {@code OFF}, or it could
 *            not read what it needed; the deploy is then let through, so
 *            {@code ready} is true
 * @param ready
 *            false only in {@code ENFORCE} mode with at least one grant issue —
 *            exactly when the deploy would be refused
 * @param grantIssues
 *            every referenced secret this agent is not granted, listed in
 *            {@code WARN} mode too
 * @since 6.6.0
 */
public record DeploymentPreflight(String agentId, Integer version, String enforcement, boolean checked, boolean ready,
        List<GrantIssue> grantIssues) {

    /**
     * One ungranted secret.
     *
     * @param tenantId
     *            the secret's tenant; null for a reference that is not a plain
     *            vault reference
     * @param keyName
     *            the secret's name; null as above
     * @param reference
     *            the reference as the agent's configuration names it
     * @param grantsAllAgents
     *            always false for an issue; present so a client need not infer it
     * @param allowedAgentCount
     *            how many agents the grant lists; null when unreadable
     * @param allowedAgents
     *            the agent ids on the grant — returned to an {@code eddi-admin}
     *            only. The ids are not secret, but they map which other agents hold
     *            a credential, and only an admin can change a grant anyway; an
     *            editor gets the count
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrantIssue(String tenantId, String keyName, String reference, boolean grantsAllAgents, Integer allowedAgentCount,
            List<String> allowedAgents) {
    }
}
