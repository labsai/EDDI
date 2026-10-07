/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Why a deployment ended in {@link Deployment.Status#ERROR}.
 * <p>
 * Before this existed the reason lived only in the server log, and the API
 * answered a bare {@code {"status":"ERROR"}} — so an operator without log
 * access could not tell a missing vault grant from a broken workflow. It is
 * kept in memory next to the deployment status and returned by the waited
 * deploy and by the detailed deployment status; a redeploy recomputes it, so it
 * does not need to survive a restart.
 * <p>
 * Carries secret <em>names</em> and references only, never a value.
 *
 * @param code
 *            {@link #VAULT_GRANT_MISSING} or {@link #DEPLOYMENT_FAILED}
 * @param message
 *            operator-readable explanation, including the fix
 * @param secrets
 *            the ungranted secrets, for {@link #VAULT_GRANT_MISSING} only
 * @param fix
 *            the exact grant change that resolves it, for
 *            {@link #VAULT_GRANT_MISSING} only
 * @since 6.6.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeploymentFailure(String code, String message, List<SecretRef> secrets, Fix fix) {

    /** The agent names a vault secret whose grant does not include it. */
    public static final String VAULT_GRANT_MISSING = "VAULT_GRANT_MISSING";

    /** Any other failure; {@link #message} carries the cause. */
    public static final String DEPLOYMENT_FAILED = "DEPLOYMENT_FAILED";

    /** A failure with only a code and a message. */
    public static DeploymentFailure generic(String message) {
        return new DeploymentFailure(DEPLOYMENT_FAILED, message, null, null);
    }

    /** Whether this failure is a missing vault grant. */
    public boolean isGrantMissing() {
        return VAULT_GRANT_MISSING.equals(code);
    }

    /**
     * One ungranted reference. {@code tenantId} and {@code keyName} are null for a
     * reference that is not a plain vault reference — an unreadable
     * {@code ${connection:…}}, or one assembled from global variables — which the
     * check reports but no single grant change fixes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SecretRef(String tenantId, String keyName, String reference) {
    }

    /**
     * The grant change that lets the agent deploy.
     *
     * @param addAgentId
     *            the agent id to add to each secret's grant
     * @param endpoints
     *            one append call per fixable secret, e.g.
     *            {@code POST /secretstore/secrets/default/k/grant/agents/<id>}
     * @param dryRunFirst
     *            always true: the endpoints accept {@code ?dryRun=true}
     */
    public record Fix(String addAgentId, List<String> endpoints, boolean dryRunFirst) {
    }
}
