/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("AgentFactory Tests")
class AgentFactoryTest {

    @Mock
    private IAgentStoreClientLibrary agentStoreClientLibrary;
    @Mock
    private IDeploymentListener deploymentListener;

    private AgentFactory factory;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        factory = new AgentFactory(agentStoreClientLibrary, deploymentListener, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Nested
    @DisplayName("getAgent")
    class GetAgentTests {

        @Test
        @DisplayName("no agent deployed — returns null")
        void noAgentDeployed() {
            IAgent agent = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNull(agent);
        }

        @Test
        @DisplayName("agent deployed with READY status — returns agent")
        void agentReadyReturned() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);

            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            IAgent result = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("getLatestAgent")
    class GetLatestAgentTests {

        @Test
        @DisplayName("no agent — returns null")
        void noAgent() {
            assertNull(factory.getLatestAgent(Deployment.Environment.test, "agent1"));
        }

        @Test
        @DisplayName("multiple versions — returns latest")
        void multipleVersions() throws Exception {
            var agent1 = new Agent("agent1", 1);
            agent1.setDeploymentStatus(Deployment.Status.READY);

            var agent2 = new Agent("agent1", 2);
            agent2.setDeploymentStatus(Deployment.Status.READY);

            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent1);
            when(agentStoreClientLibrary.getAgent("agent1", 2)).thenReturn(agent2);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "agent1", 2, null);

            IAgent result = factory.getLatestAgent(Deployment.Environment.test, "agent1");
            assertNotNull(result);
            assertEquals(2, result.getAgentVersion());
        }
    }

    @Nested
    @DisplayName("getLatestReadyAgent")
    class GetLatestReadyAgentTests {

        @Test
        @DisplayName("no ready agent — returns null")
        void noReadyAgent() {
            assertNull(factory.getLatestReadyAgent(Deployment.Environment.test, "agent1"));
        }

        @Test
        @DisplayName("returns latest READY agent, skipping ERROR")
        void skipsErrorAgent() throws Exception {
            var agent1 = new Agent("agent1", 1);
            agent1.setDeploymentStatus(Deployment.Status.READY);

            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent1);
            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            // Deploy version 2 with failure
            when(agentStoreClientLibrary.getAgent("agent1", 2))
                    .thenThrow(new ServiceException("failed"));
            factory.deployAgent(Deployment.Environment.test, "agent1", 2, null);

            IAgent result = factory.getLatestReadyAgent(Deployment.Environment.test, "agent1");
            assertNotNull(result);
            assertEquals(1, result.getAgentVersion());
        }
    }

    @Nested
    @DisplayName("getAllLatestAgents")
    class GetAllLatestAgentsTests {

        @Test
        @DisplayName("no agents — returns empty list")
        void noAgents() {
            List<IAgent> agents = factory.getAllLatestAgents(Deployment.Environment.test);
            assertNotNull(agents);
            assertTrue(agents.isEmpty());
        }

        @Test
        @DisplayName("returns latest version for each agent")
        void returnsLatestVersions() throws Exception {
            var agent1v1 = new Agent("a1", 1);
            agent1v1.setDeploymentStatus(Deployment.Status.READY);

            var agent2v1 = new Agent("a2", 1);
            agent2v1.setDeploymentStatus(Deployment.Status.READY);

            when(agentStoreClientLibrary.getAgent("a1", 1)).thenReturn(agent1v1);
            when(agentStoreClientLibrary.getAgent("a2", 1)).thenReturn(agent2v1);

            factory.deployAgent(Deployment.Environment.test, "a1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "a2", 1, null);

            List<IAgent> agents = factory.getAllLatestAgents(Deployment.Environment.test);
            assertEquals(2, agents.size());
        }
    }

    @Nested
    @DisplayName("deployAgent")
    class DeployAgentTests {

        @Test
        @DisplayName("successful deployment — agent has READY status")
        void successfulDeploy() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            IAgent result = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("deploy already READY agent — does not redeploy")
        void alreadyReady() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            // Only fetched once since second deploy finds READY agent
            verify(agentStoreClientLibrary, times(1)).getAgent("agent1", 1);
        }

        @Test
        @DisplayName("ServiceException — results in ERROR status")
        void serviceExceptionDeployment() throws Exception {
            when(agentStoreClientLibrary.getAgent("agent1", 1))
                    .thenThrow(new ServiceException("network error"));

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            IAgent result = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.ERROR, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("null deploymentProcess — uses default no-op")
        void nullDeploymentProcess() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            assertDoesNotThrow(() -> factory.deployAgent(Deployment.Environment.test, "agent1", 1, null));
        }
    }

    @Nested
    @DisplayName("undeployAgent")
    class UndeployAgentTests {

        @Test
        @DisplayName("undeploy removes agent")
        void undeployRemoves() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            assertNotNull(factory.getAgent(Deployment.Environment.test, "agent1", 1));

            factory.undeployAgent(Deployment.Environment.test, "agent1", 1);
            assertNull(factory.getAgent(Deployment.Environment.test, "agent1", 1));
        }

        @Test
        @DisplayName("undeploy non-existent agent — no error")
        void undeployNonExistent() {
            assertDoesNotThrow(() -> factory.undeployAgent(Deployment.Environment.test, "nonexistent", 1));
        }
    }
}
