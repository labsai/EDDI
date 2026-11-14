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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        return new StandaloneDynamicAgents(agentStore, agentFactory, deploymentStore, memoryStore, cap);
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
}
