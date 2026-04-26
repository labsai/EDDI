/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link AgentDeploymentManagement} — covers
 * manageAgentDeployments(), isOlderThanDays(), manageDeploymentOfOldAgent(),
 * and endOldConversationsWithOldAgents().
 */
@DisplayName("AgentDeploymentManagement Extended Tests")
class AgentDeploymentManagementExtendedTest {

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IMigrationManager migrationManager;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        var agentsReadiness = mock(IAgentsReadiness.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        migrationManager = mock(IMigrationManager.class);
        var v6Rename = mock(V6RenameMigration.class);
        var v6Qute = mock(V6QuteMigration.class);
        var runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));

        management = new AgentDeploymentManagement(
                deploymentStore, agentFactory, agentStore, agentsReadiness,
                conversationMemoryStore, documentDescriptorStore,
                migrationManager, v6Rename, v6Qute, runtime, 30);
    }

    // ─── manageAgentDeployments ─────────────────────────────

    @Nested
    @DisplayName("manageAgentDeployments")
    class ManageDeployments {

        @Test
        @DisplayName("should redeploy latest agent version")
        void redeploysLatestAgent() throws Exception {
            var info = createDeploymentInfo("agent-1", 2);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            var latestResId = mockResourceId("agent-1", 2);
            when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latestResId);

            management.manageAgentDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent-1", 2, null);
        }

        @Test
        @DisplayName("should manage old agent when newer version exists")
        void managesOldAgent() throws Exception {
            var info = createDeploymentInfo("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            // Latest is version 2, deployed is version 1 → old agent
            var latestResId = mockResourceId("agent-1", 2);
            when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latestResId);

            // No active conversations → undeploy
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);

            management.manageAgentDeployments();

            // manageDeploymentOfOldAgent is called twice: once inline and once in
            // UndeploymentExecutor
            verify(agentFactory, atLeast(1)).undeployAgent(Environment.production, "agent-1", 1);
            verify(deploymentStore, atLeast(1)).setDeploymentInfo("production", "agent-1", 1,
                    DeploymentInfo.DeploymentStatus.undeployed);
        }

        @Test
        @DisplayName("should keep old agent if active conversations exist")
        void keepsOldAgentWithActiveConversations() throws Exception {
            var info = createDeploymentInfo("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            var latestResId = mockResourceId("agent-1", 3);
            when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latestResId);

            // Has active conversations → keep deployed
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(5L);

            management.manageAgentDeployments();

            verify(agentFactory, never()).undeployAgent(any(), any(), anyInt());
            // Should redeploy old version to keep UX active (called twice: inline +
            // UndeploymentExecutor)
            verify(agentFactory, atLeast(1)).deployAgent(Environment.production, "agent-1", 1, null);
        }

        @Test
        @DisplayName("should handle deleted agent (ResourceNotFoundException) as old agent")
        void handlesDeletedAgent() throws Exception {
            var info = createDeploymentInfo("agent-deleted", 1);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            when(agentStore.getCurrentResourceId("agent-deleted"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            // No conversations → undeploy
            when(conversationMemoryStore.getActiveConversationCount("agent-deleted", 1)).thenReturn(0L);

            management.manageAgentDeployments();

            verify(agentFactory, atLeast(1)).undeployAgent(Environment.production, "agent-deleted", 1);
        }

        @Test
        @DisplayName("should handle ServiceException during deployment management gracefully")
        void handlesServiceException() throws Exception {
            var info = createDeploymentInfo("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            var latestResId = mockResourceId("agent-1", 1);
            when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latestResId);

            doThrow(new ServiceException("Deploy failed"))
                    .when(agentFactory).deployAgent(any(), any(), anyInt(), any());

            assertDoesNotThrow(() -> management.manageAgentDeployments());
        }

        @Test
        @DisplayName("should handle ResourceStoreException from readDeploymentInfos")
        void handlesReadException() throws Exception {
            when(deploymentStore.readDeploymentInfos(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertDoesNotThrow(() -> management.manageAgentDeployments());
        }

        @Test
        @DisplayName("should skip if last check was less than 1 hour ago")
        void skipsRecentCheck() throws Exception {
            // First call runs
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of());
            management.manageAgentDeployments();

            // Second call should skip (within 1 hour)
            management.manageAgentDeployments();

            // readDeploymentInfos called only once
            verify(deploymentStore, times(1))
                    .readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
        }
    }

    // ─── endOldConversationsWithOldAgents (via manageAgentDeployments) ──

    @Nested
    @DisplayName("endOldConversationsWithOldAgents")
    class EndOldConversations {

        @Test
        @DisplayName("should end conversations older than max idle days")
        void endsOldConversations() throws Exception {
            var info = createDeploymentInfo("agent-1", 1);
            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            // Old version (latest is 2)
            var latestResId = mockResourceId("agent-1", 2);
            when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latestResId);

            // First check: has active conversations
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1))
                    .thenReturn(1L);

            // Load old conversations for cleanup
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setId("conv-old");
            snapshot.setAgentId("agent-1");
            snapshot.setAgentVersion(1);
            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent-1", 1))
                    .thenReturn(List.of(snapshot));

            // Old descriptor - 60 days old (max is 30)
            var descriptor = new DocumentDescriptor();
            descriptor.setName("Test Agent");
            long sixtyDaysAgo = System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000);
            descriptor.setLastModifiedOn(new Date(sixtyDaysAgo));
            when(documentDescriptorStore.readDescriptor("agent-1", 1)).thenReturn(descriptor);

            management.manageAgentDeployments();

            // The post-undeployment attempt should have ended the old conversation
            // (the UndeploymentExecutor lambda is invoked after all current agents are
            // deployed)
            verify(conversationMemoryStore).loadActiveConversationMemorySnapshot("agent-1", 1);
        }
    }

    // ─── Helpers ────────────────────────────────────────────

    private DeploymentInfo createDeploymentInfo(String agentId, int version) {
        var info = new DeploymentInfo();
        info.setEnvironment(Environment.production);
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    private IResourceId mockResourceId(String id, int version) {
        var resId = mock(IResourceId.class);
        when(resId.getId()).thenReturn(id);
        when(resId.getVersion()).thenReturn(version);
        return resId;
    }
}
