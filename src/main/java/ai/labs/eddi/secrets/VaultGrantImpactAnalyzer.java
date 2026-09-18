/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers "who would this grant change break?" before an operator makes it.
 * <p>
 * <b>Why it exists.</b> Widening a grant is safe by construction; narrowing one
 * is not. An agent that is deployed today and references a secret it is about
 * to lose keeps running — {@link VaultGrantChecker} is a deploy-time gate, not
 * a revocation mechanism — and then fails its <em>next</em> deployment,
 * possibly weeks later, on a restart nobody connects to the grant edit.
 * Surfacing the affected agents at the moment of the edit turns a latent outage
 * into a decision.
 * <p>
 * <b>Why it is a separate bean from {@link VaultGrantChecker}.</b> The checker
 * sits underneath {@code AgentFactory}, which consults it on every deployment.
 * Giving the checker an {@link IAgentFactory} of its own would close that loop.
 * This class is injected only by the REST layer, which nothing in the
 * deployment path depends on.
 * <p>
 * <b>What it cannot see.</b> {@link IAgentFactory} is a per-node runtime
 * registry, so in a cluster this reports the agents deployed on <em>this</em>
 * node. It is a warning, and it errs towards saying less rather than towards
 * guessing: an agent it cannot read is not reported.
 */
@ApplicationScoped
public class VaultGrantImpactAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantImpactAnalyzer.class);

    private final VaultGrantChecker checker;
    private final IAgentFactory agentFactory;

    @Inject
    public VaultGrantImpactAnalyzer(VaultGrantChecker checker, IAgentFactory agentFactory) {
        this.checker = checker;
        this.agentFactory = agentFactory;
    }

    /**
     * A deployed agent that references a secret and would not be on its grant list
     * any more.
     *
     * @param agentId
     *            the deployed agent's id
     * @param agentVersion
     *            the deployed version — the one whose configuration was scanned, so
     *            the operator can tell a stale deployment from the current config
     * @param environment
     *            which environment it is deployed in
     */
    public record AffectedAgent(String agentId, Integer agentVersion, String environment) {
    }

    /**
     * The deployed agents that reference {@code secret} and are not granted it by
     * {@code proposedAllowedAgents}.
     *
     * @param proposedAllowedAgents
     *            the grant list the operator is about to store, in any of the
     *            shapes {@link SecretMetadata#grantsAllAgents} accepts
     * @return the affected agents, empty when the proposed grant is the wildcard
     *         (nothing can lose access to a secret everyone may use), when nothing
     *         deployed references the secret, or when the analysis could not run.
     *         Never null and never an exception: this feeds a warning on an
     *         operation that must not fail because the warning could not be
     *         computed
     */
    public List<AffectedAgent> agentsLosingAccess(SecretReference secret, List<String> proposedAllowedAgents) {
        if (secret == null || SecretMetadata.grantsAllAgents(proposedAllowedAgents)) {
            return List.of();
        }
        Set<String> granted = new LinkedHashSet<>(proposedAllowedAgents);

        List<AffectedAgent> affected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Deployment.Environment environment : Deployment.Environment.values()) {
            List<IAgent> deployed;
            try {
                // Every registered version, not the latest per id: the latest can be
                // registered but not READY while an older version is the one serving,
                // and two versions can be READY at once. Either would be a silent miss.
                deployed = agentFactory.getAllDeployedAgents(environment);
            } catch (Exception e) {
                LOGGER.warnf("Could not list deployed agents in %s while assessing the grant change for %s: %s", environment,
                        sanitize(secret.toReferenceString()), sanitize(e.getMessage()));
                continue;
            }
            for (IAgent agent : deployed) {
                String agentId = agent.getAgentId();
                if (agentId == null || agent.getDeploymentStatus() != Deployment.Status.READY || granted.contains(agentId)) {
                    continue;
                }
                if (!seen.add(environment.name() + "/" + agentId + "/" + agent.getAgentVersion())) {
                    continue;
                }
                if (checker.references(agentId, agent.getAgentVersion(), secret)) {
                    affected.add(new AffectedAgent(agentId, agent.getAgentVersion(), environment.name()));
                }
            }
        }
        return affected;
    }
}
