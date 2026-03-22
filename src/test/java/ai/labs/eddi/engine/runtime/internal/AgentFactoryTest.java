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
    class GetBotTests {

        @Test
        @DisplayName("should return null when Agent is not deployed")
        void getBot_notDeployed_returnsNull() {
            IAgent result = AgentFactory.getAgent(production, "bot1", 1);
            assertNull(result);
        }

        @Test
        @DisplayName("should return Agent when deployed and READY")
        void getBot_deployed_returnsBot() throws Exception {
            // Deploy a bot
            Agent mockBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(mockBot);

            AgentFactory.deployAgent(production, "bot1", 1, null);

            IAgent result = AgentFactory.getAgent(production, "bot1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }
    }

    // ==================== deployAgent Tests ====================

    @Nested
    @DisplayName("deployAgent")
    class DeployBotTests {

        @Test
        @DisplayName("should deploy Agent and set status to READY")
        void deployBot_success() throws Exception {
            Agent mockBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(mockBot);

            DeploymentProcess process = mock(DeploymentProcess.class);
            AgentFactory.deployAgent(production, "bot1", 1, process);

            verify(process).completed(READY);

            IAgent result = AgentFactory.getAgent(production, "bot1", 1);
            assertNotNull(result);
            assertEquals(READY, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("should set ERROR status when agentStoreClientLibrary throws")
        void deployBot_storeError_setsErrorStatus() throws Exception {
            when(agentStoreClientLibrary.getAgent("bot1", 1))
                    .thenThrow(new ServiceException("DB connection failed"));

            DeploymentProcess process = mock(DeploymentProcess.class);
            // AgentFactory.deployAgent() catches ServiceException inside compute() lambda
            // and sets ERROR status — it does NOT propagate the exception
            assertDoesNotThrow(
                    () -> AgentFactory.deployAgent(production, "bot1", 1, process));

            verify(process).completed(ERROR);

            // The Agent should be stored with ERROR status (not removed)
            IAgent errorBot = AgentFactory.getAgent(production, "bot1", 1);
            assertNotNull(errorBot, "Bot with ERROR status should still be in the environment");
            assertEquals(ERROR, errorBot.getDeploymentStatus());
        }

        @Test
        @DisplayName("should not redeploy an already READY bot")
        void deployBot_alreadyReady_skips() throws Exception {
            Agent mockBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(mockBot);

            DeploymentProcess process1 = mock(DeploymentProcess.class);
            DeploymentProcess process2 = mock(DeploymentProcess.class);

            AgentFactory.deployAgent(production, "bot1", 1, process1);
            AgentFactory.deployAgent(production, "bot1", 1, process2);

            // First deploy creates bot, second is a no-op
            verify(process1).completed(READY);
            verify(process2).completed(READY);
            verify(agentStoreClientLibrary, times(1)).getAgent("bot1", 1);
        }

        @Test
        @DisplayName("should handle null deploymentProcess gracefully")
        void deployBot_nullProcess_handledGracefully() throws Exception {
            Agent mockBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(mockBot);

            assertDoesNotThrow(() -> AgentFactory.deployAgent(production, "bot1", 1, null));

            IAgent result = AgentFactory.getAgent(production, "bot1", 1);
            assertNotNull(result);
        }
    }

    // ==================== undeployAgent Tests ====================

    @Nested
    @DisplayName("undeployAgent")
    class UndeployBotTests {

        @Test
        @DisplayName("should remove deployed bot")
        void undeployBot_removes() throws Exception {
            Agent mockBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(mockBot);

            AgentFactory.deployAgent(production, "bot1", 1, null);
            assertNotNull(AgentFactory.getAgent(production, "bot1", 1));

            AgentFactory.undeployAgent(production, "bot1", 1);
            assertNull(AgentFactory.getAgent(production, "bot1", 1));
        }

        @Test
        @DisplayName("should handle undeploy of non-existent Agent gracefully")
        void undeployBot_nonExistent_noError() {
            assertDoesNotThrow(() -> AgentFactory.undeployAgent(production, "nobot", 1));
        }
    }

    // ==================== getLatestAgent / getLatestReadyAgent Tests
    // ====================

    @Nested
    @DisplayName("getLatestAgent/getLatestReadyAgent")
    class LatestBotTests {

        @Test
        @DisplayName("should return null when no bots deployed")
        void getLatestBot_noBots_returnsNull() {
            assertNull(AgentFactory.getLatestAgent(production, "bot1"));
        }

        @Test
        @DisplayName("should return latest version bot")
        void getLatestBot_multipleVersions_returnsLatest() throws Exception {
            Agent bot1v1 = createReadyBot("bot1", 1);
            Agent bot1v2 = createReadyBot("bot1", 2);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(bot1v1);
            when(agentStoreClientLibrary.getAgent("bot1", 2)).thenReturn(bot1v2);

            AgentFactory.deployAgent(production, "bot1", 1, null);
            AgentFactory.deployAgent(production, "bot1", 2, null);

            IAgent latest = AgentFactory.getLatestAgent(production, "bot1");
            assertNotNull(latest);
            assertEquals(2, latest.getAgentVersion());
        }

        @Test
        @DisplayName("getLatestReadyAgent should skip non-READY bots")
        void getLatestReadyBot_skipsNonReady() throws Exception {
            Agent bot1v1 = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1)).thenReturn(bot1v1);
            AgentFactory.deployAgent(production, "bot1", 1, null);

            IAgent readyBot = AgentFactory.getLatestReadyAgent(production, "bot1");
            assertNotNull(readyBot);
            assertEquals(READY, readyBot.getDeploymentStatus());
        }
    }

    // ==================== getAllLatestAgents Tests ====================

    @Nested
    @DisplayName("getAllLatestAgents")
    class GetAllBotsTests {

        @Test
        @DisplayName("should return empty list when no bots deployed")
        void getAllLatestBots_empty() {
            var bots = AgentFactory.getAllLatestAgents(production);
            assertTrue(bots.isEmpty());
        }

        @Test
        @DisplayName("should return one Agent per unique agentId")
        void getAllLatestBots_uniquePerBotId() throws Exception {
            Agent botA = createReadyBot("botA", 1);
            Agent botB = createReadyBot("botB", 1);
            when(agentStoreClientLibrary.getAgent("botA", 1)).thenReturn(botA);
            when(agentStoreClientLibrary.getAgent("botB", 1)).thenReturn(botB);

            AgentFactory.deployAgent(production, "botA", 1, null);
            AgentFactory.deployAgent(production, "botB", 1, null);

            var all = AgentFactory.getAllLatestAgents(production);
            assertEquals(2, all.size());
        }
    }

    // ==================== Environment Isolation Tests ====================

    @Nested
    @DisplayName("Environment isolation")
    class EnvironmentTests {

        @Test
        @DisplayName("bots deployed in different environments should not interfere")
        void environments_isolated() throws Exception {
            Agent unrestrictedBot = createReadyBot("bot1", 1);
            Agent restrictedBot = createReadyBot("bot1", 1);
            when(agentStoreClientLibrary.getAgent("bot1", 1))
                    .thenReturn(unrestrictedBot)
                    .thenReturn(restrictedBot);

            AgentFactory.deployAgent(production, "bot1", 1, null);
            AgentFactory.deployAgent(Deployment.Environment.production, "bot1", 1, null);

            assertNotNull(AgentFactory.getAgent(production, "bot1", 1));
            assertNotNull(AgentFactory.getAgent(Deployment.Environment.production, "bot1", 1));

            AgentFactory.undeployAgent(production, "bot1", 1);
            assertNull(AgentFactory.getAgent(production, "bot1", 1));
            assertNotNull(AgentFactory.getAgent(Deployment.Environment.production, "bot1", 1));
        }
    }

    // ==================== Helper ====================

    private Agent createReadyBot(String agentId, int version) {
        return new Agent(agentId, version);
    }
}
