/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.secrets.VaultGrantChecker;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Refuses to deploy an agent that uses the secrets or variables of a space the
 * deployer is not a member of.
 *
 * <h3>Why at deployment</h3> A space's secrets are reached by a reference in
 * configuration — {@code ${vault:<tenant>/key}} — and the tenant id is not a
 * secret. Anybody who may edit an agent can write any reference into it. What
 * turns a reference into a request that carries the key somewhere is
 * deployment, so that is where membership is checked, against the person
 * deploying: the same point at which the vault's own per-agent grants are
 * enforced. A colleague who was lent edit access to your agent can point it at
 * your team's key, and cannot deploy it.
 * <p>
 * Administrators are exempt, as they are for the deployment-wide vault. Without
 * workspace enforcement nothing is checked: every editor already reaches every
 * tenant there.
 */
@ApplicationScoped
public class SpaceReferenceGuard {

    private static final Logger LOGGER = Logger.getLogger(SpaceReferenceGuard.class);

    private final VaultGrantChecker checker;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public SpaceReferenceGuard(VaultGrantChecker checker, ResourceAccessGuard accessGuard) {
        this.checker = checker;
        this.accessGuard = accessGuard;
    }

    /**
     * @throws ForbiddenException
     *             naming the space tenants the deployer does not belong to
     */
    public void requireMayDeploy(String agentId, Integer agentVersion) {
        if (!accessGuard.settings().isEnforcing() || accessGuard.isAdmin()) {
            return;
        }
        Set<String> referenced = checker.referencedTenants(agentId, agentVersion);
        if (referenced.isEmpty()) {
            return;
        }
        Set<String> own = new HashSet<>();
        for (String space : accessGuard.callerSpaces().spaces()) {
            own.add(SpaceTenants.tenantFor(space));
        }
        List<String> foreign = referenced.stream().filter(SpaceTenants::isSpaceTenant).filter(tenant -> !own.contains(tenant)).sorted()
                .toList();
        if (foreign.isEmpty()) {
            return;
        }
        LOGGER.warnf("Deployment of agent '%s' v%s refused: it references space tenant(s) %s that '%s' is not a member of", sanitize(agentId),
                agentVersion, sanitize(String.valueOf(foreign)), sanitize(accessGuard.currentPrincipal()));
        throw new ForbiddenException("This agent uses secrets or variables of a space you are not a member of (" + String.join(", ", foreign)
                + "). Only a member of that space may deploy it — ask one to, or remove the references.");
    }
}
