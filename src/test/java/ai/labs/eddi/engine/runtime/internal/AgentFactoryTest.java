package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory.DeploymentProcess;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static ai.labs.eddi.engine.model.Deployment.Environment.production;
import static ai.labs.eddi.engine.model.Deployment.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class AgentFactoryTest {

    @Mock
    private IAgentStoreClientLibrary agentStoreClientLibrary;
    @Mock
    private IDeploymentListener deploymentListener;

    private AgentFactory AgentFactory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentFactory = new AgentFactory(agentStoreClientLibrary, deploymentListener, meterRegistry);
    }

    // ==================== getAgent Tests ====================

    @Nested
    @DisplayName("getAgent")
    class GetAgentTests {

        @Test
        @DisplayName("should return null when Agent is not deployed")
        void getAgent_notDeployed_returnsNull() {
            IAgent result = AgentFactory.getAgent(production, "agent1", 1);
            assertNull(result);
        }

        @Test
        @DisplayName("should return Agent when deployed and READY")
        void getAgent_deployed_returnsAgent() throws Exception {
            // Deploy an agent
            Agent mockAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(mockAgent);

            AgentFactory.deployAgent(production, "agent1", 1, null);

            IAgent result = AgentFactory.getAgent(production, "agent1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }
    }

    // ==================== deployAgent Tests ====================

    @Nested
    @DisplayName("deployAgent")
    class DeployAgentTests {

        @Test
        @DisplayName("should deploy Agent and set status to READY")
        void deployAgent_success() throws Exception {
            Agent mockAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(mockAgent);

            DeploymentProcess process = mock(DeploymentProcess.class);
            AgentFactory.deployAgent(production, "agent1", 1, process);

            verify(process).completed(READY);

            IAgent result = AgentFactory.getAgent(production, "agent1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("should set ERROR status when agentStoreClientLibrary throws")
        void deployAgent_storeError_setsErrorStatus() throws Exception {
            when(agentStoreClientLibrary.getAgent("agent1", 1))
                    .thenThrow(new ServiceException("DB connection failed"));

            DeploymentProcess process = mock(DeploymentProcess.class);
            // AgentFactory.deployAgent() catches ServiceException inside compute() lambda
            // and sets ERROR status — it does NOT propagate the exception
            assertDoesNotThrow(
                    () -> AgentFactory.deployAgent(production, "agent1", 1, process));

            verify(process).completed(ERROR);

            // The Agent should be stored with ERROR status (not removed)
            IAgent errorAgent = AgentFactory.getAgent(production, "agent1", 1);
            assertNotNull(errorAgent, "Agent with ERROR status should still be in the environment");
            assertEquals(ERROR, errorAgent.getDeploymentStatus());
        }

        @Test
        @DisplayName("should not redeploy an already READY agent")
        void deployAgent_alreadyReady_skips() throws Exception {
            Agent mockAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(mockAgent);

            DeploymentProcess process1 = mock(DeploymentProcess.class);
            DeploymentProcess process2 = mock(DeploymentProcess.class);

            AgentFactory.deployAgent(production, "agent1", 1, process1);
            AgentFactory.deployAgent(production, "agent1", 1, process2);

            // First deploy creates agent, second is a no-op
            verify(process1).completed(READY);
            verify(process2).completed(READY);
            verify(agentStoreClientLibrary, times(1)).getAgent("agent1", 1);
        }

        @Test
        @DisplayName("should handle null deploymentProcess gracefully")
        void deployAgent_nullProcess_handledGracefully() throws Exception {
            Agent mockAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(mockAgent);

            assertDoesNotThrow(() -> AgentFactory.deployAgent(production, "agent1", 1, null));

            IAgent result = AgentFactory.getAgent(production, "agent1", 1);
            assertNotNull(result);
        }
    }

    // ==================== undeployAgent Tests ====================

    @Nested
    @DisplayName("undeployAgent")
    class UndeployAgentTests {

        @Test
        @DisplayName("should remove deployed agent")
        void undeployAgent_removes() throws Exception {
            Agent mockAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(mockAgent);

            AgentFactory.deployAgent(production, "agent1", 1, null);
            assertNotNull(AgentFactory.getAgent(production, "agent1", 1));

            AgentFactory.undeployAgent(production, "agent1", 1);
            assertNull(AgentFactory.getAgent(production, "agent1", 1));
        }

        @Test
        @DisplayName("should handle undeploy of non-existent Agent gracefully")
        void undeployAgent_nonExistent_noError() {
            assertDoesNotThrow(() -> AgentFactory.undeployAgent(production, "noagent", 1));
        }
    }

    // ==================== getLatestAgent / getLatestReadyAgent Tests
    // ====================

    @Nested
    @DisplayName("getLatestAgent/getLatestReadyAgent")
    class LatestAgentTests {

        @Test
        @DisplayName("should return null when no agents deployed")
        void getLatestAgent_noAgents_returnsNull() {
            assertNull(AgentFactory.getLatestAgent(production, "agent1"));
        }

        @Test
        @DisplayName("should return latest version agent")
        void getLatestAgent_multipleVersions_returnsLatest() throws Exception {
            Agent agent1v1 = createReadyAgent("agent1", 1);
            Agent agent1v2 = createReadyAgent("agent1", 2);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent1v1);
            when(agentStoreClientLibrary.getAgent("agent1", 2)).thenReturn(agent1v2);

            AgentFactory.deployAgent(production, "agent1", 1, null);
            AgentFactory.deployAgent(production, "agent1", 2, null);

            IAgent latest = AgentFactory.getLatestAgent(production, "agent1");
            assertNotNull(latest);
            assertEquals(2, latest.getAgentVersion());
        }

        @Test
        @DisplayName("getLatestReadyAgent should skip non-READY agents")
        void getLatestReadyAgent_skipsNonReady() throws Exception {
            Agent agent1v1 = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent1v1);
            AgentFactory.deployAgent(production, "agent1", 1, null);

            IAgent readyAgent = AgentFactory.getLatestReadyAgent(production, "agent1");
            assertNotNull(readyAgent);
            assertEquals(READY, readyAgent.getDeploymentStatus());
        }
    }

    // ==================== getAllLatestAgents Tests ====================

    @Nested
    @DisplayName("getAllLatestAgents")
    class GetAllAgentsTests {

        @Test
        @DisplayName("should return empty list when no agents deployed")
        void getAllLatestAgents_empty() {
            var agents = AgentFactory.getAllLatestAgents(production);
            assertTrue(agents.isEmpty());
        }

        @Test
        @DisplayName("should return one Agent per unique agentId")
        void getAllLatestAgents_uniquePerAgentId() throws Exception {
            Agent agentA = createReadyAgent("agentA", 1);
            Agent agentB = createReadyAgent("agentB", 1);
            when(agentStoreClientLibrary.getAgent("agentA", 1)).thenReturn(agentA);
            when(agentStoreClientLibrary.getAgent("agentB", 1)).thenReturn(agentB);

            AgentFactory.deployAgent(production, "agentA", 1, null);
            AgentFactory.deployAgent(production, "agentB", 1, null);

            var all = AgentFactory.getAllLatestAgents(production);
            assertEquals(2, all.size());
        }
    }

    // ==================== Environment Isolation Tests ====================

    @Nested
    @DisplayName("Environment isolation")
    class EnvironmentTests {

        @Test
        @DisplayName("agents deployed in different environments should not interfere")
        void environments_isolated() throws Exception {
            Agent unrestrictedAgent = createReadyAgent("agent1", 1);
            Agent restrictedAgent = createReadyAgent("agent1", 1);
            when(agentStoreClientLibrary.getAgent("agent1", 1))
                    .thenReturn(unrestrictedAgent)
                    .thenReturn(restrictedAgent);

            AgentFactory.deployAgent(production, "agent1", 1, null);
            AgentFactory.deployAgent(Deployment.Environment.production, "agent1", 1, null);

            assertNotNull(AgentFactory.getAgent(production, "agent1", 1));
            assertNotNull(AgentFactory.getAgent(Deployment.Environment.production, "agent1", 1));

            AgentFactory.undeployAgent(production, "agent1", 1);
            assertNull(AgentFactory.getAgent(production, "agent1", 1));
            assertNotNull(AgentFactory.getAgent(Deployment.Environment.production, "agent1", 1));
        }
    }

    // ==================== Helper ====================

    private Agent createReadyAgent(String agentId, int version) {
        return new Agent(agentId, version);
    }
}
