/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.DynamicOrigin;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * LLM tool for tearing down dynamically created agents. Constructed
 * per-invocation by {@code AgentOrchestrator} with the factory, agent store,
 * and the lists of created/retained agent IDs from the group conversation.
 *
 * <p>
 * Only agents that were created during the current discussion can be torn down
 * — preventing destruction of pre-existing agents. Two independent proofs are
 * required, and either one failing refuses:
 * <ol>
 * <li>the id is in {@code createdAgentIds}, the tracking list this conversation
 * (or its discussion) accumulated;</li>
 * <li>the agent's own configuration carries a {@link DynamicOrigin} naming this
 * conversation or this discussion — stamped by {@code create_sub_agent} and by
 * nothing else.</li>
 * </ol>
 * The list alone used to decide, and it could be seeded from client-supplied
 * context, which turned {@code teardown_agent(id, delete=true)} into "delete
 * any agent permanently". The context channel is now closed at the entry points
 * ({@code ReservedContextKeys}); the marker makes sure a future leak of that
 * kind still cannot reach an agent a person built.
 *
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class TeardownAgentTool {

    private static final Logger LOGGER = Logger.getLogger(TeardownAgentTool.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IDeploymentStore deploymentStore;
    private final List<String> createdAgentIds;
    private final Set<String> retainedAgentIds;
    /**
     * Agents this discussion has actually torn down. Persisted to step data by
     * {@code DynamicAgentToolsProvider} and subtracted by
     * {@code seedCreatedAgentIds}, so a teardown frees a
     * {@code maxCreatedAgentsPerDiscussion} slot instead of the id reappearing on
     * the next turn's seed.
     */
    private final Set<String> tornDownAgentIds;
    /** The calling conversation — must match the target's {@link DynamicOrigin}. */
    private final String conversationId;
    /** The calling conversation's discussion, or null; also accepted as a match. */
    private final String groupConversationId;

    /**
     * {@code deploymentStore} may be null; teardown then skips retiring deployment
     * records.
     *
     * @param conversationId
     *            the conversation this tool runs in
     * @param groupConversationId
     *            the discussion that conversation belongs to, or {@code null}
     */
    public TeardownAgentTool(IAgentFactory agentFactory,
            IAgentStore agentStore,
            IDeploymentStore deploymentStore,
            List<String> createdAgentIds,
            Set<String> retainedAgentIds,
            Set<String> tornDownAgentIds,
            String conversationId,
            String groupConversationId) {
        this.conversationId = conversationId;
        this.groupConversationId = groupConversationId;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.deploymentStore = deploymentStore;
        this.createdAgentIds = createdAgentIds != null ? createdAgentIds : new CopyOnWriteArrayList<>();
        this.retainedAgentIds = retainedAgentIds != null ? retainedAgentIds : new CopyOnWriteArraySet<>();
        this.tornDownAgentIds = tornDownAgentIds != null ? tornDownAgentIds : new CopyOnWriteArraySet<>();
    }

    @Tool("Tear down (undeploy) a dynamically created agent. Only agents created during this discussion "
            + "can be torn down. Optionally delete the agent configuration permanently.")
    public String teardownAgent(
                                @P("The ID of the agent to tear down") String agentId,
                                @P("If true, permanently delete the agent config after undeploying. Default: false") Boolean delete) {

        try {
            // --- Validate parameters ---
            if (agentId == null || agentId.isBlank()) {
                return "⚠️ Agent ID is required.";
            }

            // --- Security: can only teardown agents we created ---
            if (!createdAgentIds.contains(agentId)) {
                return "⚠️ Cannot tear down agent '%s' — it was not created during this discussion."
                        .formatted(agentId);
            }

            // --- Check if retained ---
            if (retainedAgentIds.contains(agentId)) {
                return "⚠️ Agent '%s' has been marked as retained and cannot be torn down. "
                        .formatted(agentId)
                        + "Remove the retain flag first if you want to tear it down.";
            }

            // --- Security: the agent itself must say it was created here ---
            String originRefusal = refuseUnlessCreatedHere(agentId);
            if (originRefusal != null) {
                return originRefusal;
            }

            // --- Undeploy ---
            try {
                agentFactory.undeployAgent(DEFAULT_ENV, agentId, null);
                LOGGER.infof("[TEARDOWN] Undeployed agent '%s'", agentId);
            } catch (Exception e) {
                LOGGER.warnf("[TEARDOWN] Undeploy failed for agent '%s': %s", agentId, e.getMessage());
                return "❌ Failed to undeploy agent '%s': %s".formatted(agentId, e.getMessage());
            }

            // --- Optional: delete agent configuration ---
            if (Boolean.TRUE.equals(delete)) {
                try {
                    agentStore.deleteAllPermanently(agentId);
                    retireDeploymentRecords(agentId);
                } catch (Exception e) {
                    // Deliberately BEFORE forgetting the agent: an agent whose config
                    // survives must stay tracked, or the ephemeral cleanup at the end of
                    // the discussion will never retry it and the config plus its
                    // deployment record are orphaned. The old code removed it from
                    // createdAgentIds up front and then reported the failure, which is
                    // exactly the leak this ordering prevents.
                    LOGGER.warnf("[TEARDOWN] Delete failed for agent '%s': %s", agentId, e.getMessage());
                    return "⚠️ Agent '%s' was undeployed but deletion failed: %s"
                            .formatted(agentId, e.getMessage());
                }
                forget(agentId);
                LOGGER.infof("[TEARDOWN] Permanently deleted agent '%s'", agentId);
                return "✅ Agent '%s' has been undeployed and permanently deleted.".formatted(agentId);
            }

            forget(agentId);
            return "✅ Agent '%s' has been undeployed successfully.".formatted(agentId);

        } catch (Exception e) {
            LOGGER.errorf("[TEARDOWN] Unexpected error tearing down agent '%s': %s",
                    agentId, e.getMessage());
            return "❌ Unexpected error: " + e.getMessage();
        }
    }

    /**
     * {@code null} when the agent's current configuration carries a
     * {@link DynamicOrigin} naming this conversation or its discussion; otherwise
     * the refusal to return. Fails closed: an agent whose configuration cannot be
     * read is not torn down.
     * <p>
     * Undeploy-only is gated as well as delete: taking someone else's production
     * agent offline is the same trespass, just reversible.
     */
    private String refuseUnlessCreatedHere(String agentId) {
        DynamicOrigin origin;
        try {
            IResourceStore.IResourceId current = agentStore.getCurrentResourceId(agentId);
            AgentConfiguration configuration = current != null ? agentStore.read(agentId, current.getVersion()) : null;
            origin = configuration != null ? configuration.getDynamicOrigin() : null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return "⚠️ Cannot tear down agent '%s' — no such agent exists.".formatted(agentId);
        } catch (Exception e) {
            LOGGER.warnf("[TEARDOWN] Could not read agent '%s' to verify its origin — refusing: %s", sanitize(agentId), e.getMessage());
            return "⚠️ Cannot tear down agent '%s' — its origin could not be verified. Try again later.".formatted(agentId);
        }
        if (origin == null) {
            LOGGER.warnf("[TEARDOWN] Refused teardown of agent '%s': it carries no dynamic-agent origin", sanitize(agentId));
            return "⚠️ Cannot tear down agent '%s' — it was not created by create_sub_agent.".formatted(agentId);
        }
        if (!origin.namesConversationOrDiscussion(conversationId, groupConversationId)) {
            LOGGER.warnf("[TEARDOWN] Refused teardown of agent '%s': created by a different conversation", sanitize(agentId));
            return "⚠️ Cannot tear down agent '%s' — it was not created during this discussion.".formatted(agentId);
        }
        return null;
    }

    /**
     * Stops tracking a successfully torn-down agent, and records the teardown so it
     * survives the turn.
     * <p>
     * Removing it from {@code createdAgentIds} alone was not enough to free a
     * {@code maxCreatedAgentsPerDiscussion} slot: that list is rebuilt every turn
     * by {@code seedCreatedAgentIds}, which unions the
     * {@code dynamic:created_agent_ids} entry of every earlier step — so the id
     * came straight back and the cap counted an agent that no longer exists
     * forever. The torn-down set is what {@code seedCreatedAgentIds} subtracts, and
     * what {@code propagateDynamicAgentTracking} uses to drop the id from the
     * group's own tracking too.
     */
    private void forget(String agentId) {
        // Tombstone first: DynamicAgentToolsProvider persists this set into step data
        // and GroupLifecycleOps applies it to the group's tracking, where it must win
        // over any created-list snapshot that has not observed the teardown yet.
        tornDownAgentIds.add(agentId);
        createdAgentIds.remove(agentId);
        retainedAgentIds.remove(agentId);
    }

    /**
     * A deployment record left behind by a deleted agent makes the runtime retry a
     * doomed redeploy. Never fatal — the agent is gone either way, and the sweep in
     * AgentDeploymentManagement retires anything missed here.
     */
    private void retireDeploymentRecords(String agentId) {
        if (deploymentStore == null) {
            return;
        }
        try {
            deploymentStore.deleteDeploymentInfos(agentId);
        } catch (Exception e) {
            LOGGER.warnf("[TEARDOWN] Could not clear deployment record(s) for agent '%s': %s", agentId, e.getMessage());
        }
    }

    @Tool("Mark a dynamically created agent for retention — it will NOT be automatically deleted "
            + "when the discussion ends. Use this when a created agent should be kept for future use.")
    public String retainAgent(
                              @P("The ID of the agent to retain") String agentId) {

        try {
            // --- Validate parameters ---
            if (agentId == null || agentId.isBlank()) {
                return "⚠️ Agent ID is required.";
            }

            // --- Security: can only retain agents we created ---
            if (!createdAgentIds.contains(agentId)) {
                return "⚠️ Cannot retain agent '%s' — it was not created during this discussion."
                        .formatted(agentId);
            }

            // --- Check if already retained ---
            if (retainedAgentIds.contains(agentId)) {
                return "ℹ️ Agent '%s' is already marked as retained.".formatted(agentId);
            }

            retainedAgentIds.add(agentId);
            LOGGER.infof("[TEARDOWN] Retained agent '%s'", agentId);

            return "✅ Agent '%s' has been marked as retained. It will not be auto-deleted after the discussion."
                    .formatted(agentId);

        } catch (Exception e) {
            LOGGER.errorf("[TEARDOWN] Error retaining agent '%s': %s", agentId, e.getMessage());
            return "❌ Error retaining agent: " + e.getMessage();
        }
    }

    @Tool("Remove the retention flag from a previously retained agent, allowing it to be cleaned up when the discussion ends.")
    public String unretainAgent(@P("The agent ID to un-retain") String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "⚠️ Agent ID is required.";
        }
        if (!retainedAgentIds.contains(agentId)) {
            return "⚠️ Agent '%s' is not currently retained.".formatted(agentId);
        }
        retainedAgentIds.remove(agentId);
        return "✅ Retention flag removed from agent '%s'. It will be cleaned up when the discussion ends.".formatted(agentId);
    }
}
