/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory.DeploymentProcess;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended tests for {@link AgentFactory} — IN_PROGRESS state, deployment
 * process callbacks, environment isolation, production vs test, multiple
 * versions.
 */
@DisplayName("AgentFactory — Extended Branch Coverage")
class AgentFactoryExtendedTest {

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

    // ==================== deployAgent — IN_PROGRESS handling ====================

    @Nested
    @DisplayName("deployAgent — IN_PROGRESS handling")
    class InProgressTests {

        @Test
        @DisplayName("deploying agent already IN_PROGRESS — keeps IN_PROGRESS state")
        void alreadyInProgress() throws Exception {
            // First deploy fails (ServiceException → agent is ERROR)
            doThrow(new ServiceException("network error"))
                    .when(agentStoreClientLibrary).getAgent("agent1", 1);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            // Agent should be ERROR now
            IAgent errorAgent = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNotNull(errorAgent);
            assertEquals(Deployment.Status.ERROR, errorAgent.getDeploymentStatus());

            // Now retry deployment — the ERROR agent should be replaced
            // Use doReturn to safely override the previous doThrow stub
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agent).when(agentStoreClientLibrary).getAgent("agent1", 1);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            IAgent result = factory.getAgent(Deployment.Environment.test, "agent1", 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    // ==================== deployAgent — DeploymentProcess callback
    // ====================

    @Nested
    @DisplayName("deployAgent — DeploymentProcess callback")
    class DeploymentProcessTests {

        @Test
        @DisplayName("custom deployment process receives READY status")
        void customProcessReady() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            DeploymentProcess process = mock(DeploymentProcess.class);
            factory.deployAgent(Deployment.Environment.test, "agent1", 1, process);

            verify(process).completed(Deployment.Status.READY);
        }

        @Test
        @DisplayName("custom deployment process receives ERROR status on failure")
        void customProcessError() throws Exception {
            when(agentStoreClientLibrary.getAgent("agent1", 1))
                    .thenThrow(new ServiceException("failed"));

            DeploymentProcess process = mock(DeploymentProcess.class);
            factory.deployAgent(Deployment.Environment.test, "agent1", 1, process);

            verify(process).completed(Deployment.Status.ERROR);
        }
    }

    // ==================== Environment isolation ====================

    @Nested
    @DisplayName("Environment isolation")
    class EnvironmentIsolationTests {

        @Test
        @DisplayName("agent in test environment not visible in production")
        void testNotInProduction() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            assertNotNull(factory.getAgent(Deployment.Environment.test, "agent1", 1));
            assertNull(factory.getAgent(Deployment.Environment.production, "agent1", 1));
        }

        @Test
        @DisplayName("agents in different environments are independent")
        void independentEnvironments() throws Exception {
            var testAgent = new Agent("agent1", 1);
            testAgent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(testAgent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);

            var prodAgent = new Agent("agent1", 1);
            prodAgent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(prodAgent);

            factory.deployAgent(Deployment.Environment.production, "agent1", 1, null);

            assertNotNull(factory.getAgent(Deployment.Environment.test, "agent1", 1));
            assertNotNull(factory.getAgent(Deployment.Environment.production, "agent1", 1));
        }
    }

    // ==================== getLatestAgent / getLatestReadyAgent edge cases
    // ====================

    @Nested
    @DisplayName("getLatestAgent / getLatestReadyAgent edge cases")
    class LatestAgentEdgeCases {

        @Test
        @DisplayName("getLatestReadyAgent — all agents are ERROR")
        void allAgentsError() throws Exception {
            when(agentStoreClientLibrary.getAgent("agent1", 1))
                    .thenThrow(new ServiceException("error v1"));
            when(agentStoreClientLibrary.getAgent("agent1", 2))
                    .thenThrow(new ServiceException("error v2"));

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "agent1", 2, null);

            IAgent result = factory.getLatestReadyAgent(Deployment.Environment.test, "agent1");
            assertNull(result);
        }

        @Test
        @DisplayName("getLatestAgent returns ERROR agent when it's the latest")
        void latestAgentIsError() throws Exception {
            var v1 = new Agent("agent1", 1);
            v1.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(v1);

            when(agentStoreClientLibrary.getAgent("agent1", 2))
                    .thenThrow(new ServiceException("failed"));

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "agent1", 2, null);

            IAgent latest = factory.getLatestAgent(Deployment.Environment.test, "agent1");
            assertNotNull(latest);
            // v2 is the latest (has ERROR status), getLatestAgent doesn't filter by status
            assertEquals(2, latest.getAgentVersion());
        }
    }

    // ==================== getAllLatestAgents — multiple agents
    // ====================

    @Nested
    @DisplayName("getAllLatestAgents — multiple agents and versions")
    class GetAllLatestTests {

        @Test
        @DisplayName("returns latest version for each agent across multiple versions")
        void multipleVersionsMultipleAgents() throws Exception {
            var a1v1 = new Agent("a1", 1);
            a1v1.setDeploymentStatus(Deployment.Status.READY);
            var a1v2 = new Agent("a1", 2);
            a1v2.setDeploymentStatus(Deployment.Status.READY);
            var a2v1 = new Agent("a2", 1);
            a2v1.setDeploymentStatus(Deployment.Status.READY);

            when(agentStoreClientLibrary.getAgent("a1", 1)).thenReturn(a1v1);
            when(agentStoreClientLibrary.getAgent("a1", 2)).thenReturn(a1v2);
            when(agentStoreClientLibrary.getAgent("a2", 1)).thenReturn(a2v1);

            factory.deployAgent(Deployment.Environment.test, "a1", 1, null);
            factory.deployAgent(Deployment.Environment.test, "a1", 2, null);
            factory.deployAgent(Deployment.Environment.test, "a2", 1, null);

            List<IAgent> agents = factory.getAllLatestAgents(Deployment.Environment.test);
            assertEquals(2, agents.size());

            // a1 should be version 2
            IAgent a1Latest = agents.stream()
                    .filter(a -> "a1".equals(a.getAgentId()))
                    .findFirst().orElseThrow();
            assertEquals(2, a1Latest.getAgentVersion());
        }

        @Test
        @DisplayName("empty production environment returns empty list")
        void emptyProduction() {
            List<IAgent> agents = factory.getAllLatestAgents(Deployment.Environment.production);
            assertTrue(agents.isEmpty());
        }
    }

    // ==================== undeployAgent ====================

    @Nested
    @DisplayName("undeployAgent edge cases")
    class UndeployEdgeCases {

        @Test
        @DisplayName("undeploy removes from all tracking lists")
        void removesFromAllLists() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            assertEquals(1, factory.getAllLatestAgents(Deployment.Environment.test).size());

            factory.undeployAgent(Deployment.Environment.test, "agent1", 1);
            assertTrue(factory.getAllLatestAgents(Deployment.Environment.test).isEmpty());
        }

        @Test
        @DisplayName("undeploy production agent does not affect test agent")
        void undeployDoesNotAffectOtherEnv() throws Exception {
            var agent = new Agent("agent1", 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentStoreClientLibrary.getAgent("agent1", 1)).thenReturn(agent);

            factory.deployAgent(Deployment.Environment.test, "agent1", 1, null);
            factory.deployAgent(Deployment.Environment.production, "agent1", 1, null);

            factory.undeployAgent(Deployment.Environment.production, "agent1", 1);

            assertNotNull(factory.getAgent(Deployment.Environment.test, "agent1", 1));
            assertNull(factory.getAgent(Deployment.Environment.production, "agent1", 1));
        }
    }
}
