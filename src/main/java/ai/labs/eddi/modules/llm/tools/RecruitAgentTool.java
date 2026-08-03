/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Brings an existing deployed agent into a running discussion (I7).
 * <p>
 * Capability <em>discovery</em> already shipped —
 * {@code FindAgentsByCapability} lets a member find the specialist it needs —
 * but there was no way to act on the answer:
 * {@code GroupConversation.addDynamicMember} had no production caller and
 * {@code maxRecruitedAgentsPerDiscussion} was read nowhere, so a discovered
 * agent could be named in prose and never speak. This closes that loop.
 * <p>
 * <b>Recruits join from the next phase, never mid-phase.</b> The recruit lands
 * on {@code dynamicMembers}, and {@code resolveParticipants} unions that list
 * when it builds each phase's speaker list. Mutating a phase's roster while it
 * is running would desynchronise the speaker index F2's resume bookmarks point
 * into, and would change the denominator I2's convergence check and I4's
 * unanimity test already computed for the round in flight.
 * <p>
 * <b>Recruits are never torn down.</b> They are pre-existing deployed agents
 * that this discussion borrowed, not agents it created — {@code
 * cleanupEphemeralAgents} undeploys what the discussion made, and undeploying a
 * borrowed agent would take it away from every other conversation using it.
 * Their ids are tracked separately from {@code createdAgentIds} for exactly
 * that reason.
 *
 * @author ginccc
 */
@Vetoed // Constructed per-turn with runtime values — must NOT be a CDI bean
public class RecruitAgentTool {

    private static final Logger LOGGER = Logger.getLogger(RecruitAgentTool.class);

    /**
     * Speaking order given to a recruit. {@code MAX_VALUE} rather than a computed
     * next-in-sequence: every ordering in the engine sorts unset/highest last, so a
     * recruit speaks after the configured roster without renumbering anyone — and
     * two recruits arriving in the same phase cannot collide on an index.
     */
    static final int RECRUIT_SPEAKING_ORDER = Integer.MAX_VALUE;

    private static final String DEFAULT_ENV = "unrestricted";

    private final LiveDiscussionRegistry registry;
    private final String groupConversationId;
    private final String recruiterAgentId;
    private final DynamicAgentConfig config;
    private final IDeploymentStore deploymentStore;

    public RecruitAgentTool(LiveDiscussionRegistry registry, String groupConversationId, String recruiterAgentId,
            DynamicAgentConfig config, IDeploymentStore deploymentStore) {
        this.registry = registry;
        this.groupConversationId = groupConversationId;
        this.recruiterAgentId = recruiterAgentId;
        this.config = config;
        this.deploymentStore = deploymentStore;
    }

    @Tool("Bring an existing deployed agent into this discussion as a new member. Use findAgentsByCapability first "
            + "to locate a suitable agent. The recruit joins from the next round onward.")
    public String recruitAgent(
                               @P("The agent id to recruit, exactly as findAgentsByCapability reported it") String agentId,
                               @P("The role this agent should play in the discussion, e.g. \"SecurityReviewer\"") String role,
                               @P("Why this agent is needed — recorded in the transcript for the other members to see") String reason) {

        GroupConversation gc = registry.get(groupConversationId).orElse(null);
        if (gc == null) {
            return "This discussion is no longer accepting new members (it has finished or is paused).";
        }
        if (agentId == null || agentId.isBlank()) {
            return "Name the agent id to recruit.";
        }
        String wanted = agentId.trim();

        // Self-recruitment first: it is the one rejection that is always true
        // regardless of roster state, and reporting "already a member" for it would
        // be a confusing way to say "that is you".
        if (wanted.equals(recruiterAgentId)) {
            return "You are already part of this discussion.";
        }
        if (isAlreadyMember(gc, wanted)) {
            return "Agent '%s' is already a member of this discussion.".formatted(wanted);
        }

        int cap = config.getMaxRecruitedAgentsPerDiscussion();
        if (gc.getRecruitedAgentIds().size() >= cap) {
            return "This discussion has already recruited its limit of %d agent(s). Work with the current team.".formatted(cap);
        }
        if (!isDeployedAndReady(wanted)) {
            return "Agent '%s' is not deployed and ready, so it cannot join. Use findAgentsByCapability to find one that is."
                    .formatted(wanted);
        }

        String resolvedRole = role != null && !role.isBlank() ? role.trim() : null;
        var recruit = new GroupMember(wanted, wanted, RECRUIT_SPEAKING_ORDER, resolvedRole, MemberType.AGENT);

        // One synchronized region over BOTH lists: the cap above is read from
        // recruitedAgentIds, so a concurrent recruiter passing the check between our
        // check and our add would take the roster one past the limit.
        synchronized (gc.getRecruitedAgentIds()) {
            if (isAlreadyMember(gc, wanted) || gc.getRecruitedAgentIds().size() >= cap) {
                return "Agent '%s' was just recruited by another member, or the limit was reached.".formatted(wanted);
            }
            gc.addDynamicMember(recruit);
            gc.getRecruitedAgentIds().add(wanted);
        }

        String note = "%s recruited %s as %s: %s".formatted(
                recruiterAgentId != null ? recruiterAgentId : "A member", wanted,
                resolvedRole != null ? resolvedRole : "a member",
                reason != null && !reason.isBlank() ? reason.trim() : "no reason given");
        gc.getTranscript().add(new TranscriptEntry(
                recruiterAgentId, "System", note, gc.getCurrentPhaseIndex(), gc.getCurrentPhaseName(),
                TranscriptEntryType.FACILITATION, Instant.now(), null, wanted));

        LOGGER.infof("Agent '%s' recruited '%s' into group conversation %s", recruiterAgentId, wanted, groupConversationId);
        return "Recruited '%s'%s. They join from the next round.".formatted(wanted,
                resolvedRole != null ? " as " + resolvedRole : "");
    }

    /**
     * Configured roster and prior recruits both count — neither can be joined
     * twice.
     */
    private boolean isAlreadyMember(GroupConversation gc, String agentId) {
        if (gc.getRecruitedAgentIds().contains(agentId)) {
            return true;
        }
        List<GroupMember> dynamic = gc.getDynamicMembers();
        synchronized (dynamic) {
            if (dynamic.stream().anyMatch(m -> agentId.equals(m.agentId()))) {
                return true;
            }
        }
        return gc.getMemberConversationIds() != null && gc.getMemberConversationIds().containsKey(agentId);
    }

    /**
     * A recruit has to be deployed, or its first turn fails and the discussion has
     * gained a member that can only produce errors. Read failures are treated as
     * "not ready" — recruiting on an unverifiable premise is the worse error.
     */
    private boolean isDeployedAndReady(String agentId) {
        if (deploymentStore == null) {
            return false;
        }
        try {
            List<DeploymentInfo> deployments = deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
            return deployments != null && deployments.stream()
                    .anyMatch(d -> d != null && agentId.equals(d.getAgentId())
                            && (d.getEnvironment() == null || DEFAULT_ENV.equalsIgnoreCase(String.valueOf(d.getEnvironment()))));
        } catch (Exception e) {
            LOGGER.warnf("Could not verify deployment of '%s' — refusing recruitment: %s", agentId, e.getMessage());
            return false;
        }
    }
}
