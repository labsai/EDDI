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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentFactory Deep Branch Coverage Tests")
class AgentFactoryDeepCoverageTest {

    private AgentFactory agentFactory;
    private IAgentStoreClientLibrary agentStoreClientLibrary;
    private IDeploymentListener deploymentListener;

    @BeforeEach
    void setUp() {
        agentStoreClientLibrary = mock(IAgentStoreClientLibrary.class);
        deploymentListener = mock(IDeploymentListener.class);
        agentFactory = new AgentFactory(agentStoreClientLibrary, deploymentListener, new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("getLatestAgent()")
    class GetLatestAgentTests {

        @Test
        @DisplayName("returns null when no agent deployed")
        void noAgentDeployed() {
            IAgent result = agentFactory.getLatestAgent(Deployment.Environment.production, "nonexistent");
            assertNull(result);
        }

        @Test
        @DisplayName("returns deployed agent when READY")
        void returnsReadyAgent() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            IAgent result = agentFactory.getLatestAgent(Deployment.Environment.production, agentId);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("getLatestReadyAgent()")
    class GetLatestReadyAgentTests {

        @Test
        @DisplayName("returns null when no agents are READY")
        void noReadyAgent() {
            IAgent result = agentFactory.getLatestReadyAgent(Deployment.Environment.production, "nonexistent");
            assertNull(result);
        }

        @Test
        @DisplayName("returns READY agent")
        void returnsReadyAgent() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent agentV1 = new Agent(agentId, 1);
            agentV1.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agentV1).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            IAgent result = agentFactory.getLatestReadyAgent(Deployment.Environment.production, agentId);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("getAgent()")
    class GetAgentTests {

        @Test
        @DisplayName("returns null when agent not found")
        void notFound() {
            IAgent result = agentFactory.getAgent(Deployment.Environment.production, "missing", 1);
            assertNull(result);
        }

        @Test
        @DisplayName("returns agent immediately when READY")
        void returnsReady() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            IAgent result = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("getAllLatestAgents()")
    class GetAllLatestAgentsTests {

        @Test
        @DisplayName("returns empty list when no agents deployed")
        void emptyWhenNone() {
            List<IAgent> result = agentFactory.getAllLatestAgents(Deployment.Environment.production);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns single agent")
        void singleAgent() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            List<IAgent> result = agentFactory.getAllLatestAgents(Deployment.Environment.production);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("returns latest version of each agent")
        void latestVersions() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent agentV1 = new Agent(agentId, 1);
            agentV1.setDeploymentStatus(Deployment.Status.READY);
            Agent agentV2 = new Agent(agentId, 2);
            agentV2.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agentV1).when(agentStoreClientLibrary).getAgent(agentId, 1);
            doReturn(agentV2).when(agentStoreClientLibrary).getAgent(agentId, 2);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 2, null);

            List<IAgent> result = agentFactory.getAllLatestAgents(Deployment.Environment.production);
            assertEquals(1, result.size());
            assertEquals(2, result.getFirst().getAgentVersion());
        }
    }

    @Nested
    @DisplayName("deployAgent()")
    class DeployAgentTests {

        @Test
        @DisplayName("deploys new agent successfully")
        void deployNewAgent() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            IAgent result = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(result);
        }

        @Test
        @DisplayName("skips redeployment when agent already READY")
        void skipRedeployWhenReady() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            // Only called once because second deploy sees READY and skips
            verify(agentStoreClientLibrary, times(1)).getAgent(agentId, 1);
        }

        @Test
        @DisplayName("handles deployment failure gracefully — sets ERROR status")
        void deploymentFailure() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            doThrow(new ServiceException("Agent not found"))
                    .when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            IAgent result = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.ERROR, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("null deploymentProcess uses no-op default")
        void nullDeploymentProcess() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            assertDoesNotThrow(() -> agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null));
        }

        @Test
        @DisplayName("deployment with callback notifies READY")
        void deploymentWithCallback() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            var statusHolder = new Deployment.Status[1];
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1,
                    status -> statusHolder[0] = status);

            assertEquals(Deployment.Status.READY, statusHolder[0]);
        }

        @Test
        @DisplayName("deployment failure notifies ERROR to callback")
        void deploymentFailureCallback() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            doThrow(new ServiceException("fail"))
                    .when(agentStoreClientLibrary).getAgent(agentId, 1);

            var statusHolder = new Deployment.Status[1];
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1,
                    status -> statusHolder[0] = status);

            assertEquals(Deployment.Status.ERROR, statusHolder[0]);
        }

        @Test
        @DisplayName("deployment in test environment is isolated")
        void testEnvironment() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.test, agentId, 1, null);

            IAgent testResult = agentFactory.getAgent(Deployment.Environment.test, agentId, 1);
            assertNotNull(testResult);

            IAgent prodResult = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNull(prodResult);
        }
    }

    @Nested
    @DisplayName("undeployAgent()")
    class UndeployAgentTests {

        @Test
        @DisplayName("removes deployed agent")
        void undeploy() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            Agent mockAgent = new Agent(agentId, 1);
            mockAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(mockAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            assertNotNull(agentFactory.getAgent(Deployment.Environment.production, agentId, 1));

            agentFactory.undeployAgent(Deployment.Environment.production, agentId, 1);
            assertNull(agentFactory.getAgent(Deployment.Environment.production, agentId, 1));
        }

        @Test
        @DisplayName("undeploying non-existent agent does not throw")
        void undeployNonExistent() {
            assertDoesNotThrow(() -> agentFactory.undeployAgent(Deployment.Environment.production, "nonexistent", 1));
        }
    }

    @Nested
    @DisplayName("multiple agents in same environment")
    class MultipleAgentsTests {

        @Test
        @DisplayName("different agentIds coexist independently")
        void differentAgentIds() throws Exception {
            Agent agent1 = new Agent("agent1id1id1id1id1id1id1", 1);
            agent1.setDeploymentStatus(Deployment.Status.READY);
            Agent agent2 = new Agent("agent2id2id2id2id2id2id2", 1);
            agent2.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agent1).when(agentStoreClientLibrary).getAgent("agent1id1id1id1id1id1id1", 1);
            doReturn(agent2).when(agentStoreClientLibrary).getAgent("agent2id2id2id2id2id2id2", 1);

            agentFactory.deployAgent(Deployment.Environment.production, "agent1id1id1id1id1id1id1", 1, null);
            agentFactory.deployAgent(Deployment.Environment.production, "agent2id2id2id2id2id2id2", 1, null);

            List<IAgent> all = agentFactory.getAllLatestAgents(Deployment.Environment.production);
            assertEquals(2, all.size());
        }
    }
}
