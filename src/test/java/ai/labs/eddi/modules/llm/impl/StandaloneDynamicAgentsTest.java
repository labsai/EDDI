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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.SetupResult;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.SubAgentLocks;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sub-agents {@code create_sub_agent} deploys outside a group discussion used
 * to stay deployed for good unless the model tore them down itself: the only
 * cleanup ran at the end of a discussion. A user opening conversations left
 * five production agents behind per conversation.
 */
@DisplayName("StandaloneDynamicAgents")
class StandaloneDynamicAgentsTest {

    private static final String CONVERSATION = "0123456789abcdef01234567";

    private IAgentStore agentStore;
    private IAgentFactory agentFactory;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        agentFactory = mock(IAgentFactory.class);
        deploymentStore = mock(IDeploymentStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
    }

    private StandaloneDynamicAgents service(int cap) {
        return new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, cap, (IConversationLeaseManager) null);
    }

    /**
     * A lease store shared by every "node" of a test: a key held by one cannot be
     * acquired by another until released. Not reentrant, like the cluster
     * implementations.
     */
    private static DynamicOrigin released() {
        var origin = new DynamicOrigin("parent", CONVERSATION, null, "user-1");
        origin.setRetained(false);
        return origin;
    }

    /** A degraded-mode handle: unfenced (Mockito would answer 0 for the Long). */
    private static LeaseHandle unfencedHandle() {
        LeaseHandle handle = mock(LeaseHandle.class);
        when(handle.fence()).thenReturn(null);
        return handle;
    }

    private static IConversationLeaseManager sharedLeases(Set<String> held) {
        IConversationLeaseManager leases = mock(IConversationLeaseManager.class);
        when(leases.acquireKey(anyString(), any(Duration.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (!held.add(key)) {
                return CompletableFuture.failedFuture(new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, "other-node", key));
            }
            LeaseHandle handle = mock(LeaseHandle.class);
            when(handle.key()).thenReturn(key);
            return CompletableFuture.completedFuture(handle);
        });
        doAnswer(invocation -> {
            LeaseHandle handle = invocation.getArgument(0);
            held.remove(handle.key());
            return null;
        }).when(leases).release(any(LeaseHandle.class));
        when(leases.tryAcquireKey(anyString())).thenReturn(Optional.empty());
        return leases;
    }

    private void agent(String agentId, DynamicOrigin origin) throws Exception {
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(agentStore.getCurrentResourceId(agentId)).thenReturn(current);
        var configuration = new AgentConfiguration();
        configuration.setDynamicOrigin(origin);
        when(agentStore.read(agentId, 1)).thenReturn(configuration);
    }

    private ConversationMemory memoryThatCreated(List<String> created, Set<String> retained) {
        var memory = new ConversationMemory(CONVERSATION, "parent", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, new ArrayList<>(created)));
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, retained));
        return memory;
    }

    @Test
    @DisplayName("an ended conversation's own, non-retained sub-agents are undeployed and deleted")
    void tearsDownOwnAgents() throws Exception {
        agent("sub-1", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        agent("sub-kept", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1", "sub-kept"), Set.of("sub-kept")));

        verify(agentFactory).undeployAgent(Environment.production, "sub-1", null);
        verify(agentStore).deleteAllPermanently("sub-1");
        verify(deploymentStore).deleteDeploymentInfos("sub-1");
        verify(agentFactory, never()).undeployAgent(any(), eq("sub-kept"), any());
    }

    @Test
    @DisplayName("a discussion's agent, another conversation's, or one without origin is left alone")
    void leavesForeignAgentsAlone() throws Exception {
        agent("group-agent", new DynamicOrigin("parent", CONVERSATION, "gc-1", "user-1"));
        agent("other-conv", new DynamicOrigin("parent", "fedcba9876543210fedcba98", null, "user-1"));
        agent("no-origin", null);

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("group-agent", "other-conv", "no-origin"), Set.of()));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("a conversation that is gone leaves nothing to do")
    void missingConversationIsNoOp() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        service(20).cleanup(CONVERSATION);

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
    }

    @Test
    @DisplayName("the per-user cap counts only that user's deployed standalone sub-agents")
    void capCountsLiveStandaloneAgents() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployed("a1"), deployed("a2"), deployed("g1"), deployed("o1"), deployed("plain")));
        agent("a1", new DynamicOrigin("parent", "c1", null, "user-1"));
        agent("a2", new DynamicOrigin("parent", "c2", null, "user-1"));
        agent("g1", new DynamicOrigin("parent", "c3", "gc-1", "user-1"));
        agent("o1", new DynamicOrigin("parent", "c4", null, "user-2"));
        agent("plain", null);

        assertNull(service(3).capRefusal("user-1"), "two live, cap three");
        String refusal = service(2).capRefusal("user-1");
        assertNotNull(refusal);
        assertTrue(refusal.contains("teardown_agent"), refusal);
        assertNull(service(0).capRefusal("user-1"), "0 switches the cap off");
    }

    @Test
    @DisplayName("deployments that cannot be counted refuse creation")
    void capFailsClosed() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenThrow(new IResourceStore.ResourceStoreException("down"));

        assertNotNull(service(20).capRefusal("user-1"));
    }

    @Test
    @DisplayName("create_sub_agent refuses with the cap's message and sets nothing up")
    void toolHonoursTheGate() throws Exception {
        var setup = mock(AgentSetupService.class);
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        var tool = new CreateSubAgentTool(setup, mock(IConversationService.class), "parent", "user-1", config, null, null, CONVERSATION,
                null, null, () -> "cap reached: tear one down with teardown_agent");

        String result = tool.createSubAgent("Helper", "You help", null, null, null, null);

        assertTrue(result.contains("cap reached"), result);
        verify(setup, never()).setupAgent(any(), any());
    }

    private static DeploymentInfo deployed(String agentId) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(1);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        return info;
    }

    @Test
    @DisplayName("an agent whose origin cannot be read makes the cap refuse, rather than leaving it out of the count")
    void capFailsClosedOnUnreadableAgent() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("a1")));
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(agentStore.getCurrentResourceId("a1")).thenReturn(current);
        when(agentStore.read("a1", 1)).thenThrow(new IResourceStore.ResourceStoreException("down"));

        assertNotNull(service(20).capRefusal("user-1"));
    }

    @Test
    @DisplayName("the end's cleanup also finds a deployed agent whose origin names the conversation but the snapshot does not list")
    void endCleanupFindsOriginTaggedAgentMissingFromSnapshot() throws Exception {
        var stored = new ConversationMemory(CONVERSATION, "parent", 1, "user-1");
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenReturn(ConversationMemoryUtilities.convertConversationMemory(stored));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(deployed("unstored"), deployed("groups"), deployed("someone-else")));
        agent("unstored", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        agent("groups", new DynamicOrigin("parent", CONVERSATION, "gc-1", "user-1"));
        agent("someone-else", new DynamicOrigin("parent", "fedcba9876543210fedcba98", null, "user-1"));

        service(20).cleanup(CONVERSATION);

        verify(agentFactory).undeployAgent(Environment.production, "unstored", null);
        verify(agentStore).deleteAllPermanently("unstored");
        verify(agentFactory, never()).undeployAgent(any(), eq("groups"), any());
        verify(agentFactory, never()).undeployAgent(any(), eq("someone-else"), any());
    }

    @Test
    @DisplayName("an ended conversation creates no sub-agent, and one created while it ended is torn down; a cancelled one keeps creating")
    void creationAfterEnd() throws Exception {
        agent("late", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(ConversationState.ENDED);

        assertNotNull(service(20).endedRefusal(CONVERSATION));
        assertTrue(service(20).tearDownIfEnded(CONVERSATION, "late"));
        verify(agentFactory).undeployAgent(Environment.production, "late", null);
        verify(agentStore).deleteAllPermanently("late");

        // A plain cancel leaves the conversation open: creation goes ahead.
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(ConversationState.EXECUTION_INTERRUPTED);
        assertNull(service(20).endedRefusal(CONVERSATION));
        assertFalse(service(20).tearDownIfEnded(CONVERSATION, "late"));
    }

    @Test
    @DisplayName("create_sub_agent tears down an agent the conversation ended under, and reports it")
    void toolTearsDownAfterCreateWhenEnded() throws Exception {
        var setup = mock(AgentSetupService.class);
        when(setup.setupAgent(any(), any())).thenReturn(SetupResult.builder().action("setup_complete").agentId("late").build());
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        var created = new ArrayList<String>();
        var tool = new CreateSubAgentTool(setup, mock(IConversationService.class), "parent", "user-1", config, created, null, CONVERSATION,
                null, null, null, agentId -> true);

        String result = tool.createSubAgent("Helper", "You help", null, null, null, null);

        assertTrue(result.contains("torn down"), result);
        assertTrue(created.isEmpty(), "a torn-down agent is not tracked");
    }

    @Test
    @DisplayName("creation gate passed, then a purge deletes the conversation, then setup completes: the new agent is torn down")
    void purgedWhileSettingUp() throws Exception {
        agent("late", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        var standalone = service(20);
        // The gate: the conversation still exists.
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(ConversationState.READY);
        assertNull(standalone.endedRefusal(CONVERSATION));
        // The purge deletes it while setup runs: both reads now find nothing.
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(null);
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        assertTrue(standalone.tearDownIfEnded(CONVERSATION, "late"));
        verify(agentFactory).undeployAgent(Environment.production, "late", null);
        verify(agentStore).deleteAllPermanently("late");
        assertNotNull(standalone.endedRefusal(CONVERSATION), "a gone conversation creates no further agents");
    }

    @Test
    @DisplayName("a store that fails to answer is not a gone conversation: nothing is torn down")
    void storeFailureTearsNothingDown() throws Exception {
        agent("late", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(null);
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenThrow(new IResourceStore.ResourceStoreException("down"));

        assertFalse(service(20).tearDownIfEnded(CONVERSATION, "late"));
        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());

        when(memoryStore.getConversationState(CONVERSATION)).thenThrow(new IllegalStateException("down"));
        assertFalse(service(20).tearDownIfEnded(CONVERSATION, "late"));
        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
    }

    @Test
    @DisplayName("when the conversation cannot be read, retention is unknown, so the origin search deletes nothing")
    void unreadableConversationSkipsOriginTeardown() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenThrow(new IResourceStore.ResourceStoreException("down"));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("retained")));
        agent("retained", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));

        service(20).cleanup(CONVERSATION);

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("the cap reserves its slot: a second creation before the first finishes setup is refused, and released afterwards")
    void capReservationIsAtomic() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        var standalone = service(1);

        assertNull(standalone.reserveCreation("user-1"), "the first creation takes the only slot");
        assertNotNull(standalone.reserveCreation("user-1"), "a concurrent second creation is refused while the first is pending");
        standalone.releaseCreation("user-1");
        assertNull(standalone.reserveCreation("user-1"), "released once setup finished");
    }

    @Test
    @DisplayName("an agent created with retain=true survives the end's origin search, even if the creating turn was never stored")
    void retainedOriginSurvivesEndCleanup() throws Exception {
        var stored = new ConversationMemory(CONVERSATION, "parent", 1, "user-1");
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION)).thenReturn(ConversationMemoryUtilities.convertConversationMemory(stored));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(deployed("kept")));
        var origin = new DynamicOrigin("parent", CONVERSATION, null, "user-1");
        origin.setRetained(true);
        agent("kept", origin);
        when(memoryStore.getConversationState(CONVERSATION)).thenReturn(ConversationState.ENDED);

        service(20).cleanup(CONVERSATION);
        assertFalse(service(20).tearDownIfEnded(CONVERSATION, "kept"));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("retained during the turn that never got stored: the end's snapshot path reads the agent's flag and keeps it")
    void retainedFlagIsTheAuthorityForTheSnapshotPath() throws Exception {
        var origin = new DynamicOrigin("parent", CONVERSATION, null, "user-1");
        origin.setRetained(true);
        agent("kept", origin);
        // The stored memory predates retain_agent: it lists the agent, but not as
        // retained.
        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("kept"), Set.of()));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("an agent whose flag cannot be read is kept, not deleted")
    void unreadableFlagKeepsTheAgent() throws Exception {
        when(agentStore.getCurrentResourceId("unreadable")).thenThrow(new IllegalStateException("store down"));

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("unreadable"), Set.of()));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("unretain_agent in the active turn wins over the stale stored list: the agent is removed at the end")
    void liveUnretainWinsOverStaleMemory() throws Exception {
        var origin = new DynamicOrigin("parent", CONVERSATION, null, "user-1");
        origin.setRetained(false);
        agent("released", origin);

        // The stored memory still lists it as retained.
        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("released"), Set.of("released")));

        verify(agentFactory).undeployAgent(Environment.production, "released", null);
        verify(agentStore).deleteAllPermanently("released");
    }

    @Test
    @DisplayName("an agent created before the flag existed (absent, not false) keeps memory's retention")
    void legacyAgentFallsBackToMemory() throws Exception {
        agent("legacy", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("legacy"), Set.of("legacy")));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
    }

    @Test
    @DisplayName("teardown marks the agent as being torn down while it undeploys and deletes, and clears the mark after")
    void teardownMarksTheAgent() throws Exception {
        agent("sub-1", new DynamicOrigin("parent", CONVERSATION, null, "user-1"));
        var markedDuringUndeploy = new AtomicBoolean();
        doAnswer(invocation -> {
            markedDuringUndeploy.set(SubAgentLocks.isTearingDown("sub-1"));
            return null;
        }).when(agentFactory).undeployAgent(Environment.production, "sub-1", null);

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1"), Set.of()));

        assertTrue(markedDuringUndeploy.get(), "a retain arriving meanwhile is refused");
        assertFalse(SubAgentLocks.isTearingDown("sub-1"), "cleared once teardown is over");
    }

    @Test
    @DisplayName("while teardown holds the agent's cluster lease, a retain on another node is refused, and the lease is released after")
    void teardownLeaseRefusesRetainElsewhere() throws Exception {
        Set<String> held = ConcurrentHashMap.newKeySet();
        IConversationLeaseManager leases = sharedLeases(held);
        agent("sub-1", released());
        // Node B: the same conversation's retain tool, sharing only the lease store.
        var retainTool = new TeardownAgentTool(agentFactory, agentStore, deploymentStore, new CopyOnWriteArrayList<>(List.of("sub-1")),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), CONVERSATION, null, leases);
        var retainReply = new AtomicReference<String>();
        // The retain lands while teardown (node A) is reading the flag — after it took
        // the lease, before it marked the agent; only the lease can refuse it here.
        doAnswer(invocation -> {
            if (retainReply.get() == null) {
                retainReply.set("pending");
                retainReply.set(retainTool.retainAgent("sub-1"));
            }
            var configuration = new AgentConfiguration();
            configuration.setDynamicOrigin(released());
            return configuration;
        }).when(agentStore).read("sub-1", 1);
        var nodeA = new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, 20, leases);

        nodeA.cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1"), Set.of()));

        assertTrue(retainReply.get().contains("changed elsewhere"), retainReply.get());
        verify(agentStore, never()).update(eq("sub-1"), any(), any(), anyBoolean());
        verify(agentStore).deleteAllPermanently("sub-1");
        assertTrue(held.isEmpty(), "teardown released the lease");
    }

    @Test
    @DisplayName("a lease lost after undeploy (past its validity) stops the delete: the agent is kept, undeployed")
    void leaseLostBeforeDeleteKeepsTheAgent() throws Exception {
        Set<String> held = ConcurrentHashMap.newKeySet();
        var lost = new AtomicBoolean();
        IConversationLeaseManager leases = mock(IConversationLeaseManager.class);
        when(leases.isClustered()).thenReturn(true);
        LeaseHandle handle = mock(LeaseHandle.class);
        when(handle.key()).thenReturn(SubAgentLocks.LEASE_PREFIX + "sub-1");
        when(handle.fence()).thenReturn(7L);
        when(handle.isLost()).thenAnswer(invocation -> lost.get());
        when(leases.acquireKey(anyString(), any(Duration.class))).thenAnswer(invocation -> {
            held.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(handle);
        });
        agent("sub-1", released());
        doAnswer(invocation -> {
            lost.set(true);
            return null;
        }).when(agentFactory).undeployAgent(Environment.production, "sub-1", null);

        new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, 20, leases)
                .cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1"), Set.of()));

        verify(agentFactory).undeployAgent(Environment.production, "sub-1", null);
        verify(agentStore, never()).deleteAllPermanently(anyString());
        verify(leases).release(handle);
    }

    @Test
    @DisplayName("an agent retained or changed while it was undeployed is not deleted")
    void retainedDuringUndeployIsNotDeleted() throws Exception {
        agent("sub-1", released());
        doAnswer(invocation -> {
            var configuration = new AgentConfiguration();
            var origin = new DynamicOrigin("parent", CONVERSATION, null, "user-1");
            origin.setRetained(true);
            configuration.setDynamicOrigin(origin);
            when(agentStore.read("sub-1", 1)).thenReturn(configuration);
            return null;
        }).when(agentFactory).undeployAgent(Environment.production, "sub-1", null);

        service(20).cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1"), Set.of()));

        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("clustered but degraded (an unfenced handle): teardown keeps the agent and retain is refused, its flag not written")
    void degradedHandleSkipsTeardownAndRefusesRetain() throws Exception {
        IConversationLeaseManager leases = mock(IConversationLeaseManager.class);
        when(leases.isClustered()).thenReturn(true);
        when(leases.acquireKey(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(unfencedHandle()));
        agent("sub-1", released());

        new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, 20, leases)
                .cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1"), Set.of()));

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());

        var retainTool = new TeardownAgentTool(agentFactory, agentStore, deploymentStore, new CopyOnWriteArrayList<>(List.of("sub-1")),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), CONVERSATION, null, leases);
        String reply = retainTool.retainAgent("sub-1");

        assertTrue(reply.contains("cluster coordination is unavailable"), reply);
        verify(agentStore, never()).update(eq("sub-1"), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("clustering off: the local lease manager's unfenced handle is no obstacle — retain writes its flag")
    void unclusteredUnfencedHandleAllowsRetain() throws Exception {
        IConversationLeaseManager leases = mock(IConversationLeaseManager.class);
        when(leases.isClustered()).thenReturn(false);
        when(leases.acquireKey(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(unfencedHandle()));
        agent("sub-1", released());
        var retainTool = new TeardownAgentTool(agentFactory, agentStore, deploymentStore, new CopyOnWriteArrayList<>(List.of("sub-1")),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), CONVERSATION, null, leases);

        String reply = retainTool.retainAgent("sub-1");

        assertFalse(reply.contains("coordination"), reply);
        verify(agentStore).update(eq("sub-1"), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a teardown that cannot get the agent's lease (a retain holds it elsewhere) skips the agent this time")
    void leaseHeldElsewhereSkipsTeardown() throws Exception {
        Set<String> held = ConcurrentHashMap.newKeySet();
        held.add(SubAgentLocks.LEASE_PREFIX + "sub-1");
        agent("sub-1", released());
        agent("sub-2", released());
        var service = new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, 20, sharedLeases(held));

        service.cleanup(CONVERSATION, memoryThatCreated(List.of("sub-1", "sub-2"), Set.of()));

        verify(agentFactory, never()).undeployAgent(any(), eq("sub-1"), any());
        verify(agentStore, never()).deleteAllPermanently("sub-1");
        verify(agentFactory).undeployAgent(Environment.production, "sub-2", null);
        assertEquals(Set.of(SubAgentLocks.LEASE_PREFIX + "sub-1"), held, "the other node's lease is untouched, ours released");
    }
}
