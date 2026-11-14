/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.DynamicOrigin;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The lifecycle of agents {@code create_sub_agent} deploys <em>outside</em> a
 * group discussion. A discussion tears its created agents down when it ends
 * ({@code GroupLifecycleOps#cleanupEphemeralAgents}); a standalone conversation
 * had nothing of the kind, so every agent the model did not tear down itself
 * stayed deployed to production for good — five per conversation by default, on
 * the operator's key, for every conversation a user opened.
 * <ul>
 * <li>{@link #cleanup} — when the creating conversation ends or is deleted,
 * every agent it created and did not retain is undeployed and permanently
 * deleted. Only an agent whose own {@code dynamicOrigin} names this
 * conversation and <em>no</em> discussion is touched: a discussion's agents are
 * the group engine's to clean up, and an agent without a verifiable origin is
 * left alone (the tracked list alone is not proof — the same rule
 * {@code teardown_agent} applies).</li>
 * <li>{@link #capRefusal} — a cap on the standalone agents one user may have
 * deployed at once ({@code eddi.dynamic-agents.standalone.max-live-per-user},
 * default 20), the backstop for conversations that never end.</li>
 * </ul>
 * Never throws: a cleanup that fails is logged and leaves the agent deployed,
 * as it was before.
 */
@ApplicationScoped
public class StandaloneDynamicAgents {

    private static final Logger LOGGER = Logger.getLogger(StandaloneDynamicAgents.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    /** Default of {@code eddi.dynamic-agents.standalone.max-live-per-user}. */
    public static final int DEFAULT_MAX_LIVE_PER_USER = 20;

    private final IAgentStore agentStore;
    private final IAgentFactory agentFactory;
    private final IDeploymentStore deploymentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final int maxLivePerUser;

    @Inject
    public StandaloneDynamicAgents(IAgentStore agentStore, IAgentFactory agentFactory, IDeploymentStore deploymentStore,
            IConversationMemoryStore conversationMemoryStore,
            @ConfigProperty(name = "eddi.dynamic-agents.standalone.max-live-per-user", defaultValue = "20") int maxLivePerUser) {
        this.agentStore = agentStore;
        this.agentFactory = agentFactory;
        this.deploymentStore = deploymentStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.maxLivePerUser = maxLivePerUser;
    }

    /**
     * Why this user may not create another standalone agent now, or {@code null}
     * when they may. A cap of {@code 0} or less switches the cap off. Fails closed:
     * when the deployments cannot be counted, creation is refused.
     */
    public String capRefusal(String userId) {
        if (maxLivePerUser <= 0) {
            return null;
        }
        long live;
        try {
            live = liveStandaloneAgents(userId);
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not count the standalone sub-agents of user '%s' — refusing creation: %s", sanitize(userId),
                    sanitize(e.getMessage()));
            return "⚠️ Your deployed sub-agents could not be counted right now, so no new one was created. Try again later.";
        }
        if (live >= maxLivePerUser) {
            return ("⚠️ You already have %d sub-agents deployed outside group discussions, the most allowed (%d). "
                    + "Tear one down with teardown_agent, or end the conversations that created them, before creating another.")
                    .formatted(live, maxLivePerUser);
        }
        return null;
    }

    /**
     * How many standalone sub-agents created for {@code userId} are deployed now.
     */
    long liveStandaloneAgents(String userId) throws IResourceStore.ResourceStoreException {
        if (userId == null) {
            return 0;
        }
        List<DeploymentInfo> deployed = deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
        Set<String> agentIds = new LinkedHashSet<>();
        for (DeploymentInfo info : deployed) {
            if (info != null && info.getAgentId() != null) {
                agentIds.add(info.getAgentId());
            }
        }
        long live = 0;
        for (String agentId : agentIds) {
            // Not originOf: its fail-safe null would leave an unreadable agent out of
            // the count and admit a creation over the cap.
            DynamicOrigin origin = originForCount(agentId);
            if (origin != null && origin.getCreatedInGroupConversationId() == null && userId.equals(origin.getCreatedForUserId())) {
                live++;
            }
        }
        return live;
    }

    /**
     * The agent's {@code dynamicOrigin} for the cap: {@code null} when it has none
     * or no longer exists (a stale deployment record), and a failure to read it
     * propagates, so {@link #capRefusal} refuses.
     */
    private DynamicOrigin originForCount(String agentId) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStore.IResourceId current = agentStore.getCurrentResourceId(agentId);
            AgentConfiguration configuration = current != null ? agentStore.read(agentId, current.getVersion()) : null;
            return configuration != null ? configuration.getDynamicOrigin() : null;
        } catch (IResourceStore.ResourceNotFoundException gone) {
            return null;
        }
    }

    /**
     * Tears down what the conversation created and did not retain, reading its
     * stored memory. For the end and delete paths that hold only the id.
     */
    public void cleanup(String conversationId) {
        if (conversationId == null) {
            return;
        }
        String owner = null;
        Set<String> handled = new HashSet<>();
        Set<String> retained = Set.of();
        try {
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            if (snapshot != null) {
                owner = snapshot.getUserId();
                IConversationMemory memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
                List<String> created = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);
                retained = DynamicAgentToolsProvider.seedRetainedAgentIds(memory, created);
                cleanup(conversationId, memory);
                handled.addAll(created);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            // gone already: only the agents' own origins can name them now
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not read conversation %s to tear down its sub-agents: %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
        }
        // The stored snapshot misses an agent a turn created and never stored — a turn
        // cancelled or abandoned while the end ran, on any node. The agent's own
        // dynamicOrigin is durable the moment it exists, so the end also tears down
        // every deployed agent whose origin names this conversation: whichever of the
        // end and the turn's finalization runs last still finds it.
        for (String agentId : originTagged(conversationId, owner)) {
            if (!handled.contains(agentId) && !retained.contains(agentId)) {
                teardown(agentId, conversationId);
            }
        }
    }

    /**
     * The deployed agents whose {@code dynamicOrigin} names {@code conversationId}
     * and no discussion — scoped to the conversation's owner when known, through
     * the same deployment lookup the per-user cap uses. Empty when the deployments
     * cannot be read (logged; the snapshot path above still ran).
     */
    List<String> originTagged(String conversationId, String owner) {
        List<String> tagged = new ArrayList<>();
        try {
            Set<String> agentIds = new LinkedHashSet<>();
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (info != null && info.getAgentId() != null) {
                    agentIds.add(info.getAgentId());
                }
            }
            for (String agentId : agentIds) {
                DynamicOrigin origin = originOf(agentId);
                if (origin != null && origin.getCreatedInGroupConversationId() == null
                        && conversationId.equals(origin.getCreatedInConversationId())
                        && (owner == null || owner.equals(origin.getCreatedForUserId()))) {
                    tagged.add(agentId);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not list the deployed sub-agents of conversation %s: %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
        }
        return tagged;
    }

    /**
     * Why a sub-agent may not be created in {@code conversationId} now, or
     * {@code null}: an ended conversation creates none. Checked before setup; an
     * agent created while the end ran is caught by {@link #tearDownIfEnded}.
     */
    public String endedRefusal(String conversationId) {
        if (isEnded(conversationId)) {
            return "⚠️ This conversation has ended or no longer exists, so no sub-agent was created.";
        }
        return null;
    }

    /**
     * After setup: when the conversation ended while the agent was being created,
     * tears it down at once — the end's cleanup may already have run.
     *
     * @return {@code true} when the agent was torn down
     */
    public boolean tearDownIfEnded(String conversationId, String agentId) {
        if (agentId == null || !isEnded(conversationId)) {
            return false;
        }
        teardown(agentId, conversationId);
        return true;
    }

    /**
     * Whether the conversation is over: stored {@code ENDED}, or confirmed gone. A
     * purge deletes the snapshot after its own cleanup, so an agent whose setup
     * finished after that scan finds no conversation at all — and must be torn
     * down, not kept. Gone means confirmed: a state read that finds nothing and a
     * snapshot read that finds nothing. A store that fails to answer is not "gone"
     * — nothing is torn down then (logged).
     */
    private boolean isEnded(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        try {
            ConversationState state = conversationMemoryStore.getConversationState(conversationId);
            if (state != null) {
                return state == ConversationState.ENDED;
            }
            return conversationMemoryStore.loadConversationMemorySnapshot(conversationId) == null;
        } catch (IResourceStore.ResourceNotFoundException gone) {
            return true;
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not read the state of conversation %s; treating it as open: %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
            return false;
        }
    }

    /**
     * Tears down what the conversation in {@code memory} created and did not
     * retain.
     */
    public void cleanup(String conversationId, IConversationMemory memory) {
        if (conversationId == null || memory == null) {
            return;
        }
        List<String> created;
        Set<String> retained;
        try {
            created = DynamicAgentToolsProvider.seedCreatedAgentIds(memory);
            if (created.isEmpty()) {
                return;
            }
            retained = DynamicAgentToolsProvider.seedRetainedAgentIds(memory, created);
        } catch (RuntimeException e) {
            LOGGER.warnf("[DYNAMIC] Could not read the sub-agents conversation %s created: %s", sanitize(conversationId), sanitize(e.getMessage()));
            return;
        }
        for (String agentId : created) {
            if (agentId == null || retained.contains(agentId)) {
                continue;
            }
            teardown(agentId, conversationId);
        }
    }

    private void teardown(String agentId, String conversationId) {
        DynamicOrigin origin = originOf(agentId);
        if (origin == null || origin.getCreatedInGroupConversationId() != null || !conversationId.equals(origin.getCreatedInConversationId())) {
            // No verifiable origin (every agent create_sub_agent deploys carries one), a
            // discussion's agent (the group engine's to clean up) or another
            // conversation's: not ours to touch.
            LOGGER.debugf("[DYNAMIC] Agent '%s' is not a standalone sub-agent of conversation %s — left alone", sanitize(agentId),
                    sanitize(conversationId));
            return;
        }
        try {
            agentFactory.undeployAgent(DEFAULT_ENV, agentId, null);
            agentStore.deleteAllPermanently(agentId);
            try {
                deploymentStore.deleteDeploymentInfos(agentId);
            } catch (Exception e) {
                LOGGER.warnf("[DYNAMIC] Could not clear the deployment record(s) of sub-agent '%s': %s", sanitize(agentId), sanitize(e.getMessage()));
            }
            LOGGER.infof("[DYNAMIC] Tore down sub-agent '%s' when conversation %s ended", sanitize(agentId), sanitize(conversationId));
        } catch (Exception e) {
            LOGGER.warnf("[DYNAMIC] Could not tear down sub-agent '%s' of conversation %s: %s", sanitize(agentId), sanitize(conversationId),
                    sanitize(e.getMessage()));
        }
    }

    /**
     * The agent's {@code dynamicOrigin}, or {@code null} when absent or unreadable.
     */
    private DynamicOrigin originOf(String agentId) {
        try {
            IResourceStore.IResourceId current = agentStore.getCurrentResourceId(agentId);
            AgentConfiguration configuration = current != null ? agentStore.read(agentId, current.getVersion()) : null;
            return configuration != null ? configuration.getDynamicOrigin() : null;
        } catch (Exception e) {
            LOGGER.debugf("[DYNAMIC] Could not read agent '%s' for its origin: %s", sanitize(agentId), sanitize(e.getMessage()));
            return null;
        }
    }
}
