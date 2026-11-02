/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
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
import java.util.EnumSet;
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
 * <b>Where it looks.</b> Two sources, because neither is the whole fleet on its
 * own. {@link IAgentFactory} is a per-node runtime registry: it alone sees a
 * deployment still coming up, or one made with {@code autoDeploy=false}, which
 * is never recorded. The deployment records in {@link IDeploymentStore} live in
 * the shared database, so they also name the agents deployed on every other
 * node — on a cluster, the runtime registry alone answered for one node while
 * the vault it guards is shared by all of them. What neither source holds yet
 * is a deployment racing the scan: one whose record is written at READY on
 * another node a moment after the scan read the records.
 * <p>
 * <b>What it cannot read.</b> An agent whose configuration cannot be read is
 * not listed as losing access — naming it would be a guess — but it marks the
 * answer incomplete and is returned as unverified, so "nothing breaks" is never
 * claimed on the strength of a config nobody managed to read.
 */
@ApplicationScoped
public class VaultGrantImpactAnalyzer {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantImpactAnalyzer.class);

    private final VaultGrantChecker checker;
    private final IAgentFactory agentFactory;
    private final IDeploymentStore deploymentStore;

    @Inject
    public VaultGrantImpactAnalyzer(VaultGrantChecker checker, IAgentFactory agentFactory, IDeploymentStore deploymentStore) {
        this.checker = checker;
        this.agentFactory = agentFactory;
        this.deploymentStore = deploymentStore;
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
     * What the scan found, and whether it managed to look everywhere.
     * <p>
     * The two travel together because "no affected agents" and "could not tell"
     * must not arrive as the same answer. They did: a failed environment listing
     * was skipped and the remaining list returned as though complete, so a caller —
     * and then the operator in front of the Manager — read an empty list as
     * "nothing breaks" when the truth was "not known".
     *
     * @param agentsLosingAccess
     *            the deployed agents that reference the secret and would not be
     *            granted it, as far as the scan got
     * @param complete
     *            false when any environment or the deployment records could not be
     *            listed, or any deployed agent's configuration could not be read,
     *            so the list may be short
     * @param unverifiedAgents
     *            the deployed agents whose configuration could not be read — the
     *            ones that make the answer incomplete, when it is an agent and not
     *            a listing that failed
     */
    public record GrantImpact(List<AffectedAgent> agentsLosingAccess, boolean complete, List<AffectedAgent> unverifiedAgents) {

        /** An answer with no agent whose configuration went unread. */
        public GrantImpact(List<AffectedAgent> agentsLosingAccess, boolean complete) {
            this(agentsLosingAccess, complete, List.of());
        }
    }

    /**
     * The runtime states that count as deployed. {@code IN_PROGRESS} counts: the
     * deploy gate has already let the agent through by the time it is registered,
     * so a secret created while it comes up is one it will resolve.
     */
    private static final Set<Deployment.Status> DEPLOYED = EnumSet.of(Deployment.Status.READY, Deployment.Status.IN_PROGRESS);

    /**
     * The deployed agents that reference {@code secret} and are not granted it by
     * {@code proposedAllowedAgents}.
     *
     * @param proposedAllowedAgents
     *            the grant list the operator is about to store, in any of the
     *            shapes {@link SecretMetadata#grantsAllAgents} accepts
     * @return the affected agents, plus whether every environment was actually
     *         scanned. The list is empty when the proposed grant is the wildcard
     *         (nothing can lose access to a secret everyone may use) or when
     *         nothing deployed references the secret — those are complete answers.
     *         An environment that could not be listed leaves {@code complete}
     *         false, so a caller can tell that apart from "nothing breaks". Never
     *         null and never an exception: this feeds a warning on an operation
     *         that must not fail because the warning could not be computed
     */
    public GrantImpact agentsLosingAccess(SecretReference secret, List<String> proposedAllowedAgents) {
        if (secret == null || SecretMetadata.grantsAllAgents(proposedAllowedAgents)) {
            return new GrantImpact(List.of(), true);
        }
        Set<String> granted = new LinkedHashSet<>(proposedAllowedAgents);

        List<AffectedAgent> affected = new ArrayList<>();
        List<AffectedAgent> unverified = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean complete = true;
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
                complete = false;
                continue;
            }
            for (IAgent agent : deployed) {
                if (agent.getAgentId() != null && DEPLOYED.contains(agent.getDeploymentStatus())) {
                    complete &= assess(secret, granted, environment, agent.getAgentId(), agent.getAgentVersion(), seen, affected, unverified);
                }
            }
        }
        List<DeploymentInfo> records;
        try {
            // The shared database's view: the agents deployed on every node, not only
            // on the one answering this request.
            records = deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
        } catch (Exception e) {
            LOGGER.warnf("Could not read the deployment records while assessing the grant change for %s: %s",
                    sanitize(secret.toReferenceString()), sanitize(e.getMessage()));
            records = List.of();
            complete = false;
        }
        for (DeploymentInfo deployment : records == null ? List.<DeploymentInfo>of() : records) {
            if (deployment != null && deployment.getAgentId() != null && deployment.getEnvironment() != null) {
                complete &= assess(secret, granted, deployment.getEnvironment(), deployment.getAgentId(), deployment.getAgentVersion(), seen,
                        affected, unverified);
            }
        }
        return new GrantImpact(affected, complete, unverified);
    }

    /**
     * Checks one deployed agent version, once, and files it.
     *
     * @return false when its configuration could not be read
     */
    private boolean assess(SecretReference secret, Set<String> granted, Deployment.Environment environment, String agentId,
                           Integer agentVersion, Set<String> seen, List<AffectedAgent> affected, List<AffectedAgent> unverified) {
        if (granted.contains(agentId) || !seen.add(environment.name() + "/" + agentId + "/" + agentVersion)) {
            return true;
        }
        switch (checker.checkReferences(agentId, agentVersion, secret)) {
            case REFERENCES -> affected.add(new AffectedAgent(agentId, agentVersion, environment.name()));
            // Not listed as losing access — naming it would be a guess — but the
            // answer is no longer complete: an agent whose config could not be read
            // may well be one that loses access.
            case UNKNOWN -> {
                unverified.add(new AffectedAgent(agentId, agentVersion, environment.name()));
                return false;
            }
            case DOES_NOT_REFERENCE -> {
                // nothing to report
            }
        }
        return true;
    }

    /**
     * Why a secret that does not exist yet may not be <em>created</em> with
     * {@code allowedAgents}, or null when it may.
     * <p>
     * The deploy-time check lets a reference to an absent secret through: agent
     * sync promotes a workflow to an instance that does not hold the source's
     * secrets, and deploys it before anyone creates them. The resolver checks no
     * grants, so without this check a secret created later — restricted to some
     * other agent — would resolve for every agent already deployed with a reference
     * to it, until its next deployment. That is the grant the deploy gate would
     * have refused, so it is refused here, at the first moment it exists: the
     * creator either names those agents on the grant or undeploys them first.
     * <p>
     * Follows {@code eddi.vault.grant-enforcement} as the deploy gate does:
     * {@code enforce} refuses, {@code warn} logs and allows, {@code off} does not
     * look. Under {@code enforce} a scan that could not look everywhere refuses
     * too. That is the opposite of the deploy gate, which lets an unreadable config
     * through, and it is the same trade weighed the other way: refusing a
     * deployment is an outage, refusing a creation is a retry, and letting it
     * through hands the value to whichever unread agent references it for as long
     * as that agent stays deployed. The message names the way out: an agent named
     * on the grant, or the wildcard, needs no scan.
     *
     * @param mode
     *            the deployment's grant-enforcement mode
     * @return the refusal message, naming the agents, or null
     */
    public String refusalToCreate(SecretReference secret, List<String> allowedAgents, VaultGrantGate.Mode mode) {
        if (mode == VaultGrantGate.Mode.OFF) {
            return null;
        }
        GrantImpact impact = agentsLosingAccess(secret, allowedAgents);
        String message;
        if (!impact.agentsLosingAccess().isEmpty()) {
            message = "Deployed agent(s) " + agentIds(impact.agentsLosingAccess()) + " already reference " + secret.toReferenceString()
                    + ", and the grant this secret is being created with leaves them out. Deployed while the secret did not exist, they "
                    + "were never checked against its grant, so creating it like this would hand them the value. Add them to "
                    + "allowedAgents, or undeploy them first.";
        } else if (!impact.complete()) {
            String cause = impact.unverifiedAgents().isEmpty()
                    ? "the deployed agents could not all be listed"
                    : "the configuration of deployed agent(s) " + agentIds(impact.unverifiedAgents()) + " could not be read";
            message = "Could not confirm that no deployed agent left out of this grant references " + secret.toReferenceString() + ": "
                    + cause + ". An agent deployed while the secret did not exist was never checked against its grant, so the creation "
                    + "is refused rather than risk handing it the value. Retry, add the agent(s) to allowedAgents, or undeploy them first.";
        } else {
            return null;
        }
        if (mode == VaultGrantGate.Mode.WARN) {
            LOGGER.warnf("%s (eddi.vault.grant-enforcement=warn: created anyway)", sanitize(message));
            return null;
        }
        return message;
    }

    private static String agentIds(List<AffectedAgent> agents) {
        Set<String> ids = new LinkedHashSet<>();
        agents.forEach(agent -> ids.add(agent.agentId()));
        return String.join(", ", ids);
    }
}
