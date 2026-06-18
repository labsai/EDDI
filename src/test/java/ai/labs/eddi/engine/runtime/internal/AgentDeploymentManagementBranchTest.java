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
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.datastore.IResourceStore;
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
import org.mockito.Mock;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("AgentDeploymentManagement — Branch Coverage Tests")
class AgentDeploymentManagementBranchTest {

    @Mock
    private IDeploymentStore deploymentStore;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IAgentsReadiness agentsReadiness;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IMigrationManager migrationManager;
    @Mock
    private V6RenameMigration v6RenameMigration;
    @Mock
    private V6QuteMigration v6QuteMigration;
    @Mock
    private ChannelConnectorMigration channelConnectorMigration;
    @Mock
    private IRuntime runtime;

    private AgentDeploymentManagement management;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);
        var scheduledExecutorService = mock(java.util.concurrent.ScheduledExecutorService.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduledExecutorService);

        management = new AgentDeploymentManagement(
                deploymentStore, agentFactory, agentStore, agentsReadiness,
                conversationMemoryStore, documentDescriptorStore, migrationManager,
                v6RenameMigration, v6QuteMigration, channelConnectorMigration, runtime, 30);
    }

    @Nested
    @DisplayName("autoDeployAgents")
    class AutoDeployAgents {

        @Test
        @DisplayName("migrations all succeed, then deploys agents")
        void migrationsSucceedThenDeploy() throws Exception {
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());

            doAnswer(inv -> {
                ((IMigrationManager.IMigrationFinished) inv.getArgument(0)).onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            management.autoDeployAgents();

            verify(v6RenameMigration).runIfNeeded();
            verify(v6QuteMigration).runIfNeeded();
            verify(channelConnectorMigration).runIfNeeded();
            verify(agentsReadiness).setAgentsReadiness(true);
        }

        @Test
        @DisplayName("v6 rename migration exception is caught")
        void v6RenameException() throws Exception {
            doThrow(new RuntimeException("rename error")).when(v6RenameMigration).runIfNeeded();
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());

            doAnswer(inv -> {
                ((IMigrationManager.IMigrationFinished) inv.getArgument(0)).onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            assertDoesNotThrow(() -> management.autoDeployAgents());
        }

        @Test
        @DisplayName("v6 Qute migration exception is caught")
        void v6QuteException() throws Exception {
            doThrow(new RuntimeException("qute error")).when(v6QuteMigration).runIfNeeded();
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());

            doAnswer(inv -> {
                ((IMigrationManager.IMigrationFinished) inv.getArgument(0)).onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            assertDoesNotThrow(() -> management.autoDeployAgents());
        }

        @Test
        @DisplayName("channel connector migration exception is caught")
        void channelMigrationException() throws Exception {
            doThrow(new RuntimeException("channel error")).when(channelConnectorMigration).runIfNeeded();
            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of());

            doAnswer(inv -> {
                ((IMigrationManager.IMigrationFinished) inv.getArgument(0)).onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            assertDoesNotThrow(() -> management.autoDeployAgents());
        }
    }

    @Nested
    @DisplayName("checkDeployments")
    class CheckDeployments {

        @Test
        @DisplayName("deployment with null agentId is filtered out")
        void nullAgentIdFiltered() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId(null);
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("deployment with null agentVersion is filtered out")
        void nullAgentVersionFiltered() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(null);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("successful deployment adds to deploymentInfos")
        void successfulDeployment() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }

        @Test
        @DisplayName("already deployed agent is not redeployed")
        void alreadyDeployedNotRedeployed() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            // First call deploys
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());

            // Second call skips (already in list)
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("ServiceException during deploy is handled")
        void serviceExceptionHandled() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));
            doThrow(new ServiceException("deploy error")).when(agentFactory)
                    .deployAgent(any(), anyString(), anyInt(), any());

            assertDoesNotThrow(() -> management.checkDeployments());
        }

        @Test
        @DisplayName("generic exception with ResourceNotFound cause marks as undeployed")
        void resourceNotFoundCause() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            var rnfe = new IResourceStore.ResourceNotFoundException("not found");
            var wrapped = new IllegalStateException("wrapped", rnfe);
            doThrow(wrapped).when(agentFactory).deployAgent(any(), anyString(), anyInt(), any());

            management.checkDeployments();

            verify(deploymentStore).setDeploymentInfo(eq("production"), eq("agent1"), eq(1),
                    eq(DeploymentInfo.DeploymentStatus.undeployed));
        }

        @Test
        @DisplayName("generic exception without ResourceNotFound cause does NOT mark as undeployed")
        void genericExceptionNoUndeployMark() throws Exception {
            var info = new DeploymentInfo();
            info.setAgentId("agent1");
            info.setAgentVersion(1);
            info.setEnvironment(Environment.production);

            when(deploymentStore.readDeploymentInfos(deployed)).thenReturn(List.of(info));

            doThrow(new IllegalStateException("no resource cause"))
                    .when(agentFactory).deployAgent(any(), anyString(), anyInt(), any());

            management.checkDeployments();

            verify(deploymentStore, never()).setDeploymentInfo(anyString(), anyString(), anyInt(),
                    eq(DeploymentInfo.DeploymentStatus.undeployed));
        }

        @Test
        @DisplayName("ResourceStoreException in readDeploymentInfos is caught")
        void readDeploymentsException() throws Exception {
            when(deploymentStore.readDeploymentInfos(deployed))
                    .thenThrow(new IResourceStore.ResourceStoreException("db error"));

            assertDoesNotThrow(() -> management.checkDeployments());
        }
    }
}
