package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentDeploymentManagement Tests")
class AgentDeploymentManagementTest {

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IAgentsReadiness agentsReadiness;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IMigrationManager migrationManager;
    private V6RenameMigration v6RenameMigration;
    private V6QuteMigration v6QuteMigration;
    private IRuntime runtime;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        agentsReadiness = mock(IAgentsReadiness.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        migrationManager = mock(IMigrationManager.class);
        v6RenameMigration = mock(V6RenameMigration.class);
        v6QuteMigration = mock(V6QuteMigration.class);
        runtime = mock(IRuntime.class);

        var scheduler = mock(ScheduledExecutorService.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduler);

        management = new AgentDeploymentManagement(
                deploymentStore, agentFactory, agentStore, agentsReadiness,
                conversationMemoryStore, documentDescriptorStore,
                migrationManager, v6RenameMigration, v6QuteMigration,
                runtime, 30);
    }

    @Nested
    @DisplayName("checkDeployments")
    class CheckDeploymentsTests {

        @Test
        @DisplayName("deploys new agents from deployment store")
        void deploysNewAgents() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }

        @Test
        @DisplayName("skips agents with null agentId")
        void skipsNullAgentId() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId(null);
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("skips agents with null version")
        void skipsNullVersion() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(null);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("does not re-deploy already deployed agents")
        void doesNotRedeploy() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            // First call deploys
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());

            // Second call should not re-deploy
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("handles ResourceStoreException gracefully")
        void handlesStoreException() throws Exception {
            when(deploymentStore.readDeploymentInfos(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertDoesNotThrow(() -> management.checkDeployments());
        }

        @Test
        @DisplayName("handles deploy failure gracefully")
        void handlesDeployFailure() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));
            doThrow(new IllegalAccessException("Access denied"))
                    .when(agentFactory).deployAgent(any(), any(), anyInt(), any());

            assertDoesNotThrow(() -> management.checkDeployments());
        }

        @Test
        @DisplayName("marks stale deployment as undeployed when ResourceNotFoundException")
        void marksStaleDeployment() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            var rnfe = new IResourceStore.ResourceNotFoundException("Not found");
            doThrow(new IllegalStateException("Wrapped", rnfe))
                    .when(agentFactory).deployAgent(any(), any(), anyInt(), any());

            management.checkDeployments();

            verify(deploymentStore).setDeploymentInfo(
                    eq("production"), eq("agent1"), eq(1),
                    eq(DeploymentInfo.DeploymentStatus.undeployed));
        }
    }

    @Nested
    @DisplayName("autoDeployAgents")
    class AutoDeployTests {

        @Test
        @DisplayName("runs migrations before deployment")
        void runsMigrations() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());

            // migrationManager.startMigrationIfFirstTimeRun runs the callback
            doAnswer(inv -> {
                IMigrationManager.IMigrationFinished callback = inv.getArgument(0);
                callback.onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            management.autoDeployAgents();

            verify(v6RenameMigration).runIfNeeded();
            verify(v6QuteMigration).runIfNeeded();
            verify(migrationManager).startMigrationIfFirstTimeRun(any());
        }
    }
}
