/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link RecruitAgentTool} (I7).
 * <p>
 * Recruitment writes to a running discussion's roster, so the refusals matter
 * more than the happy path: a recruit that is not deployed can only produce
 * errors on its first turn, a duplicate silently doubles someone's voice, and
 * an uncapped loop grows the fan-out of every subsequent phase without bound.
 *
 * @author tests
 */
class RecruitAgentToolTest {

    private static final String GC_ID = "gc-1";
    private static final String RECRUITER = "agent-a";
    private static final String TARGET = "agent-specialist";

    private LiveDiscussionRegistry registry;
    private GroupConversation gc;
    private IDeploymentStore deploymentStore;

    @BeforeEach
    void setUp() throws Exception {
        registry = new LiveDiscussionRegistry();
        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId("group-1");
        registry.register(gc);
        deploymentStore = mock(IDeploymentStore.class);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployment(TARGET), deployment("other-deployed")));
    }

    private DeploymentInfo deployment(String agentId) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        return info;
    }

    private DynamicAgentConfig config(int cap) {
        var c = new DynamicAgentConfig();
        c.setEnabled(true);
        c.setAllowRecruitment(true);
        c.setMaxRecruitedAgentsPerDiscussion(cap);
        return c;
    }

    private RecruitAgentTool tool() {
        return tool(config(10));
    }

    private RecruitAgentTool tool(DynamicAgentConfig config) {
        return new RecruitAgentTool(registry, GC_ID, RECRUITER, config, deploymentStore);
    }

    // =================================================================
    // Happy path
    // =================================================================

    @Test
    void recruit_joinsTheRosterAndIsRecordedInTheTranscript() {
        String reply = tool().recruitAgent(TARGET, "SecurityReviewer", "we need a threat model");

        assertTrue(reply.startsWith("Recruited"), reply);
        assertEquals(1, gc.getDynamicMembers().size());
        GroupMember recruit = gc.getDynamicMembers().get(0);
        assertEquals(TARGET, recruit.agentId());
        assertEquals("SecurityReviewer", recruit.role());
        assertEquals(RecruitAgentTool.RECRUIT_SPEAKING_ORDER, recruit.speakingOrder(),
                "recruits must sort after the configured roster without renumbering it");
        assertTrue(gc.getRecruitedAgentIds().contains(TARGET));

        var entry = gc.getTranscript().get(0);
        assertEquals(TranscriptEntryType.FACILITATION, entry.type());
        assertTrue(entry.content().contains("threat model"), "the reason is what tells the other members why: " + entry.content());
        assertTrue(entry.content().contains(TARGET), entry.content());
    }

    @Test
    void recruit_isTrackedSeparatelyFromCreatedAgents() {
        // createdAgentIds drives cleanupEphemeralAgents, which UNDEPLOYS. A recruit
        // is a borrowed pre-existing agent — undeploying it would take it away from
        // every other conversation using it.
        tool().recruitAgent(TARGET, "Reviewer", "why");

        assertTrue(gc.getCreatedAgentIds().isEmpty(), "a recruit must never land on the teardown list");
        assertEquals(List.of(TARGET), gc.getRecruitedAgentIds());
    }

    @Test
    void missingRole_stillRecruits() {
        String reply = tool().recruitAgent(TARGET, null, "why");

        assertTrue(reply.startsWith("Recruited"), reply);
        assertNull(gc.getDynamicMembers().get(0).role(), "a roleless recruit participates in ALL phases");
    }

    // =================================================================
    // Refusals
    // =================================================================

    @Test
    void undeployedAgent_isRefused() {
        String reply = tool().recruitAgent("not-deployed", "Reviewer", "why");

        assertFalse(reply.startsWith("Recruited"), reply);
        assertTrue(reply.contains("findAgentsByCapability"), "point the model at the way to find a valid one: " + reply);
        assertTrue(gc.getDynamicMembers().isEmpty());
    }

    @Test
    void selfRecruitment_isRefused() {
        String reply = tool().recruitAgent(RECRUITER, "Reviewer", "why");

        assertTrue(reply.contains("already part of"), reply);
        assertTrue(gc.getDynamicMembers().isEmpty());
    }

    @Test
    void alreadyRecruited_isRefused() {
        tool().recruitAgent(TARGET, "Reviewer", "why");

        String reply = tool().recruitAgent(TARGET, "Reviewer", "again");

        assertTrue(reply.contains("already a member"), reply);
        assertEquals(1, gc.getDynamicMembers().size(), "recruiting twice must not double a member's voice");
    }

    @Test
    void existingMemberConversation_countsAsAlreadyAMember() {
        // A configured member that has already spoken has a member conversation.
        gc.getMemberConversationIds().put(TARGET, "conv-1");

        String reply = tool().recruitAgent(TARGET, "Reviewer", "why");

        assertTrue(reply.contains("already a member"), reply);
        assertTrue(gc.getDynamicMembers().isEmpty());
    }

    @Test
    void capIsEnforced() throws Exception {
        // maxRecruitedAgentsPerDiscussion existed with a default of 10 and was read
        // nowhere before I7 — this is the first test that can fail if it stops
        // being enforced.
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployment("a1"), deployment("a2"), deployment("a3")));
        var tool = tool(config(2));

        assertTrue(tool.recruitAgent("a1", "R", "why").startsWith("Recruited"));
        assertTrue(tool.recruitAgent("a2", "R", "why").startsWith("Recruited"));
        String third = tool.recruitAgent("a3", "R", "why");

        assertFalse(third.startsWith("Recruited"), third);
        assertTrue(third.contains("limit of 2"), third);
        assertEquals(2, gc.getDynamicMembers().size());
    }

    @Test
    void unregisteredDiscussion_isRefused() {
        registry.unregister(GC_ID);

        String reply = tool().recruitAgent(TARGET, "Reviewer", "why");

        assertFalse(reply.startsWith("Recruited"), reply);
        assertEquals(0, gc.getDynamicMembers().size(), "a write to a stale copy would be clobbered by the loop's next save");
    }

    @Test
    void blankAgentId_isRefused() {
        assertFalse(tool().recruitAgent("  ", "R", "why").startsWith("Recruited"));
        assertFalse(tool().recruitAgent(null, "R", "why").startsWith("Recruited"));
        assertTrue(gc.getDynamicMembers().isEmpty());
    }

    @Test
    void deploymentStoreFailure_refusesRatherThanAssumingReady() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenThrow(new RuntimeException("mongo is down"));

        String reply = tool().recruitAgent(TARGET, "Reviewer", "why");

        assertFalse(reply.startsWith("Recruited"), "recruiting on an unverifiable premise is the worse error: " + reply);
        assertTrue(gc.getDynamicMembers().isEmpty());
    }

    // =================================================================
    // Concurrency — the cap must hold under parallel speakers
    // =================================================================

    @Test
    void concurrentRecruitment_cannotExceedTheCap() throws Exception {
        var deployments = new ArrayList<DeploymentInfo>();
        for (int i = 0; i < 8; i++) {
            deployments.add(deployment("cand-" + i));
        }
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(deployments);
        var tool = tool(config(3));

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(8);
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    tool.recruitAgent("cand-" + n, "R", "why");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "recruitment deadlocked");

        // A PARALLEL phase runs every speaker at once. Checking the cap outside the
        // lock would let several callers pass it together and overshoot.
        assertEquals(3, gc.getDynamicMembers().size(), "the cap must hold under concurrent recruiters");
        assertEquals(3, gc.getRecruitedAgentIds().size());
    }
}
