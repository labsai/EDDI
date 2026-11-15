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
        Set<String> referenced;
        try {
            referenced = checker.referencedTenants(agentId, agentVersion);
        } catch (VaultGrantChecker.UnverifiableReferencesException e) {
            LOGGER.warnf("Deployment of agent '%s' v%s refused: %s", sanitize(agentId), agentVersion, sanitize(e.getMessage()));
            throw new ForbiddenException(e.getMessage() + ". Try again once it can be read, or ask an administrator to deploy it.");
        }
        List<String> foreign = foreignTenants(referenced);
        if (foreign.isEmpty()) {
            return;
        }
        LOGGER.warnf("Deployment of agent '%s' v%s refused: it references space tenant(s) %s that '%s' is not a member of", sanitize(agentId),
                agentVersion, sanitize(String.valueOf(foreign)), sanitize(accessGuard.currentPrincipal()));
        throw new ForbiddenException("This agent uses secrets or variables of a space you are not a member of (" + String.join(", ", foreign)
                + "). Only a member of that space may deploy it — ask one to, or remove the references.");
    }

    /**
     * {@link #requireMayDeploy}'s rule for one configuration run on its own,
     * outside any agent and so outside any deployment: a knowledge base searched or
     * ingested into through its standalone endpoints builds its embedding model and
     * store from the configuration on the spot, resolving every
     * {@code ${vars:<tenant>/…}} and {@code ${vault:<tenant>/…}} it names with the
     * server's authority. The caller must belong to each space tenant it names.
     *
     * @param what
     *            what the configuration is, for the message
     * @throws ForbiddenException
     *             naming the space tenants the caller does not belong to
     */
    public void requireMayUse(Object configuration, String what) {
        if (configuration == null || !accessGuard.settings().isEnforcing() || accessGuard.isAdmin()) {
            return;
        }
        Set<String> referenced;
        try {
            referenced = checker.referencedTenantsOf(configuration);
        } catch (VaultGrantChecker.UnverifiableReferencesException e) {
            LOGGER.warnf("Use of a %s refused: %s", sanitize(what), sanitize(e.getMessage()));
            throw new ForbiddenException(e.getMessage() + ". Try again once it can be read, or ask an administrator.");
        }
        List<String> foreign = foreignTenants(referenced);
        if (foreign.isEmpty()) {
            return;
        }
        LOGGER.warnf("Use of a %s refused: it references space tenant(s) %s that '%s' is not a member of", sanitize(what),
                sanitize(String.valueOf(foreign)), sanitize(accessGuard.currentPrincipal()));
        throw new ForbiddenException("This " + what + " uses secrets or variables of a space you are not a member of ("
                + String.join(", ", foreign) + "). Only a member of that space may run it — ask one to, or remove the references.");
    }

    /**
     * The space tenants in {@code referenced} the caller is not a member of,
     * sorted.
     */
    private List<String> foreignTenants(Set<String> referenced) {
        if (referenced.isEmpty()) {
            return List.of();
        }
        Set<String> own = new HashSet<>();
        for (String space : accessGuard.callerSpaces().spaces()) {
            own.add(SpaceTenants.tenantFor(space));
        }
        return referenced.stream().filter(SpaceTenants::isSpaceTenant).filter(tenant -> !own.contains(tenant)).sorted().toList();
    }
}
