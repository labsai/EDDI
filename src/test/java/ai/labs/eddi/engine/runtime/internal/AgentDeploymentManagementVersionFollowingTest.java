/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily deployment sweep and old versions whose conversations can move to a
 * newer, compatible version: retired at once, with none of their conversations
 * ended.
 */
@DisplayName("AgentDeploymentManagement — retiring versions whose conversations can move")
class AgentDeploymentManagementVersionFollowingTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT = "aabbccddeeff112233445566";

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        var runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
        management = new AgentDeploymentManagement(deploymentStore, agentFactory, agentStore, mock(IAgentsReadiness.class),
                conversationMemoryStore, mock(IDocumentDescriptorStore.class), mock(IMigrationManager.class), mock(V6RenameMigration.class),
                mock(V6QuteMigration.class), mock(ChannelConnectorMigration.class), mock(WorkspaceAccessIndexMigration.class), runtime,
                mock(IWorkflowStore.class), mock(IRuleSetStore.class), 30);
    }

    private void version(int version, Integer generation) throws Exception {
        var config = new AgentConfiguration();
        config.setCompatibilityGeneration(generation);
        when(agentStore.read(AGENT, version)).thenReturn(config);
    }

    private void readyOfGeneration(int generation, int version) throws Exception {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentVersion()).thenReturn(version);
        when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT, generation)).thenReturn(agent);
    }

    @Test
    @DisplayName("a newer compatible version is ready: the old one is undeployed and recorded so")
    void retiresWhenANewerCompatibleVersionIsReady() throws Exception {
        version(5, 2);
        readyOfGeneration(2, 6);

        assertTrue(management.retireIfConversationsCanMove(ENV, AGENT, 5));

        verify(agentFactory).undeployAgent(ENV, AGENT, 5);
        verify(deploymentStore).setDeploymentInfo("production", AGENT, 5, DeploymentInfo.DeploymentStatus.undeployed);
    }

    @Test
    @DisplayName("a version without a generation takes the old path")
    void legacyVersionIsNotRetiredHere() throws Exception {
        version(5, null);

        assertFalse(management.retireIfConversationsCanMove(ENV, AGENT, 5));
        verify(agentFactory, never()).undeployAgent(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("no newer version of the generation is ready: the old path")
    void noNewerCompatibleVersion() throws Exception {
        version(5, 2);
        readyOfGeneration(2, 5);

        assertFalse(management.retireIfConversationsCanMove(ENV, AGENT, 5), "the version itself is not a successor");
        verify(agentFactory, never()).undeployAgent(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a configuration that cannot be read takes the old path")
    void unreadableConfigurationTakesTheOldPath() throws Exception {
        when(agentStore.read(AGENT, 5)).thenThrow(new IResourceStore.ResourceStoreException("down"));

        assertFalse(management.retireIfConversationsCanMove(ENV, AGENT, 5));
    }

    /**
     * The sweep used to end an old version's idle conversations before undeploying
     * it. With a compatible version to continue on, those conversations must be
     * left alone — and the old version still goes.
     */
    @Test
    @DisplayName("the sweep retires a compatible old version without ending its conversations")
    void sweepDoesNotEndMovableConversations() throws Exception {
        var old = new DeploymentInfo();
        old.setAgentId(AGENT);
        old.setAgentVersion(5);
        old.setEnvironment(ENV);
        old.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        IResourceStore.IResourceId latest = mock(IResourceStore.IResourceId.class);
        when(latest.getVersion()).thenReturn(6);
        when(agentStore.getCurrentResourceId(AGENT)).thenReturn(latest);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(old));
        when(conversationMemoryStore.getActiveConversationCount(AGENT, 5)).thenReturn(3L);
        version(5, 2);
        readyOfGeneration(2, 6);

        management.manageAgentDeployments();

        verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(anyString(), any());
        verify(conversationMemoryStore, never()).compareAndSetState(anyString(), any(), any());
        verify(agentFactory).undeployAgent(ENV, AGENT, 5);
    }
}
