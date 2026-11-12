/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.VaultGrantGate;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    /**
     * {@code eddi.vault.grant-enforcement}, applied to the agent grants of a
     * configuration run outside any agent. Field-injected; null in tests built with
     * {@code new}, which parses to the shipped default, {@code enforce}.
     */
    @Inject
    @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = VaultGrantGate.DEFAULT_MODE_NAME)
    String grantEnforcement;

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
        if (configuration == null || accessGuard.isAdmin()) {
            return;
        }
        // Before the workspace check, and whether or not workspaces are enforced: a
        // secret's agent grant binds on every deployment, not only a multi-space one.
        requireNoAgentRestrictedSecrets(configuration, what);
        if (!accessGuard.settings().isEnforcing()) {
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
     * Refuses to run a configuration outside any agent when it names a secret whose
     * {@code allowedAgents} restricts it to particular agents.
     * <p>
     * On the agent path that grant is enforced when the agent is deployed
     * ({@link VaultGrantChecker#findUngrantedReferences}). A knowledge base run
     * through its standalone endpoints has no agent, so nothing held it to the
     * grant: any editor with EDIT on any knowledge base could name a secret granted
     * to one agent and have the server resolve it.
     * <p>
     * A restricted secret is therefore permitted only to a caller who may EDIT at
     * least one existing agent on its grant: that caller can already make the
     * granted agent use the secret, so running the knowledge base exposes nothing
     * new — this is how the editor who maintains an agent and its knowledge base
     * keeps searching and ingesting into it. Anyone else is refused, without being
     * told which agents the grant names. An administrator is exempt. Without
     * workspace enforcement every editor may edit every agent, so the check then
     * passes for any existing granted agent. A secret open to every agent
     * ({@code ["*"]}, or no list) is unaffected, and so is the ingestion an agent's
     * own retrieval or a scheduled run performs, which does not pass through here.
     * <p>
     * Follows {@code eddi.vault.grant-enforcement}: {@code off} does not check,
     * {@code warn} logs and lets the call through, {@code enforce} (the default)
     * refuses. A grant or configuration that cannot be read is refused: unlike the
     * deploy gate there is no later check to fall back on.
     *
     * @throws ForbiddenException
     *             naming the restricted secrets
     */
    private void requireNoAgentRestrictedSecrets(Object configuration, String what) {
        VaultGrantGate.Mode mode = VaultGrantGate.Mode.parseStrict(grantEnforcement);
        if (mode == VaultGrantGate.Mode.OFF) {
            return;
        }
        List<String> restricted;
        try {
            restricted = checker.findAgentRestrictedReferences(configuration).stream().filter(this::callerMayNotUse)
                    .map(VaultGrantChecker.RestrictedReference::reference).toList();
        } catch (RuntimeException e) {
            LOGGER.warnf("Use of a %s refused: its vault references could not be checked against their agent grants: %s", sanitize(what),
                    sanitize(e.getMessage()));
            throw new ForbiddenException("This " + what + "'s vault references could not be checked against their agent grants. Try again "
                    + "once they can be read, or ask an administrator.");
        }
        if (restricted.isEmpty()) {
            return;
        }
        String names = String.join(", ", restricted);
        if (mode == VaultGrantGate.Mode.WARN) {
            LOGGER.warnf("A %s run outside any agent by '%s' uses vault secret(s) restricted to particular agents: %s. Allowed "
                    + "(eddi.vault.grant-enforcement=warn); set it to 'enforce' to refuse.", sanitize(what),
                    sanitize(accessGuard.currentPrincipal()), sanitize(names));
            return;
        }
        LOGGER.warnf("Use of a %s refused: '%s' may edit none of the agents granted the vault secret(s) %s", sanitize(what),
                sanitize(accessGuard.currentPrincipal()), sanitize(names));
        throw new ForbiddenException("This " + what + " uses vault secret(s) whose grant (allowedAgents) names particular agents: " + names
                + ". Run here, outside any agent, it may be used only by someone who may edit one of those agents, or by an administrator. "
                + "Ask one to run it, grant the secret to every agent (allowedAgents [\"*\"]), or use a secret meant for this " + what + ".");
    }

    /**
     * Whether the caller is refused {@code restricted}: an entry that could not be
     * verified always is; otherwise unless the caller may EDIT an agent on the
     * grant.
     */
    private boolean callerMayNotUse(VaultGrantChecker.RestrictedReference restricted) {
        if (restricted.unverifiable()) {
            return true;
        }
        return restricted.grantedAgents().stream().noneMatch(agentId -> accessGuard.hasAccess(agentId, AccessLevel.EDIT));
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
