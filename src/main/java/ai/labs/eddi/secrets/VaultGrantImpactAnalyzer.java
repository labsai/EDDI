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
 * <b>Advisory, never the control.</b> The grant is enforced by
 * {@link SecretResolver}, for the asking agent, every time a secret is resolved
 * — from the vault or from a cache (#1179). Nothing here lets a secret through
 * or keeps one back. What this adds is foresight: narrowing a grant, or
 * creating a secret with a grant that leaves out an agent already configured to
 * use it, refuses those agents the secret from the next resolution on, and an
 * operator should see who that is before making the change rather than in the
 * next failed turn.
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
 * the vault it guards is shared by all of them. What neither source holds is a
 * deployment racing the scan, one whose record is written at READY on another
 * node a moment after the scan read the records, and an
 * {@code autoDeploy=false} deployment on another node that has not been
 * announced here. So the answer is never "every deployment in the cluster", and
 * {@link GrantImpact#complete} does not claim it is; those agents are refused
 * at resolution like any other.
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
     *            whether every SOURCE was read: false when any environment or the
     *            deployment records could not be listed, or any deployed agent's
     *            configuration could not be read, so the list may be short. True
     *            does not mean every deployment in the cluster was seen — see the
     *            class comment for the two a scan cannot see
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
     * What to tell the creator of a secret that does not exist yet about the agents
     * its {@code allowedAgents} leaves out, or null when there is nothing to say.
     * <p>
     * The deploy-time check lets a reference to an absent secret through: agent
     * sync promotes a workflow to an instance that does not hold the source's
     * secrets, and deploys it before anyone creates them. Such an agent meets the
     * secret's grant the first time it resolves it, and is refused there if the
     * grant leaves it out. That is the control, and it holds on every node whatever
     * this scan saw (#1179). This used to REFUSE the creation instead, which was
     * only ever as good as the scan: it missed a deployment that came up on another
     * node after the records were read, and an {@code autoDeploy=false} deployment
     * on another node. The creation now goes ahead, and this names the agents that
     * will be refused, so the creator can add them before anyone notices.
     * <p>
     * Follows {@code eddi.vault.grant-enforcement}: {@code off} does not look,
     * because nothing is refused there either; {@code warn} also logs the message,
     * because the agents are not refused there, only logged.
     *
     * @param mode
     *            the deployment's grant-enforcement mode
     * @return the warning, naming the agents (or what could not be read), or null
     */
    public String warningOnCreate(SecretReference secret, List<String> allowedAgents, VaultGrantGate.Mode mode) {
        if (mode == VaultGrantGate.Mode.OFF) {
            return null;
        }
        GrantImpact impact = agentsLosingAccess(secret, allowedAgents);
        String consequence = mode == VaultGrantGate.Mode.ENFORCE
                ? "they are refused the secret when they resolve it, and their next deployment is refused too"
                : "under eddi.vault.grant-enforcement=enforce they would be refused it; under warn this is logged";
        String message;
        if (!impact.agentsLosingAccess().isEmpty()) {
            message = "Deployed agent(s) " + agentIds(impact.agentsLosingAccess()) + " already reference " + secret.toReferenceString()
                    + ", and the grant it was created with leaves them out: " + consequence + ". Add them to allowedAgents if they "
                    + "should use it.";
        } else if (!impact.complete()) {
            String cause = impact.unverifiedAgents().isEmpty()
                    ? "the deployed agents could not all be listed"
                    : "the configuration of deployed agent(s) " + agentIds(impact.unverifiedAgents()) + " could not be read";
            message = "Could not check which deployed agents reference " + secret.toReferenceString() + ": " + cause + ". Any that do "
                    + "and are left out of the grant: " + consequence + ".";
        } else {
            return null;
        }
        if (mode == VaultGrantGate.Mode.WARN) {
            LOGGER.warnf("%s", sanitize(message));
        }
        return message;
    }

    private static String agentIds(List<AffectedAgent> agents) {
        Set<String> ids = new LinkedHashSet<>();
        agents.forEach(agent -> ids.add(agent.agentId()));
        return String.join(", ", ids);
    }
}
