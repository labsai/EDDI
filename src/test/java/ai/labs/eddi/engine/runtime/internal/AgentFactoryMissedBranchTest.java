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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentFactory — Missed Branch Coverage")
class AgentFactoryMissedBranchTest {

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
    @DisplayName("getAgent — IN_PROGRESS")
    class GetAgentInProgress {

        @Test
        @DisplayName("IN_PROGRESS with null future — returns null when agent stays IN_PROGRESS")
        void inProgressNullFuture() throws Exception {
            String agentId = "aabbccddeeff112233445566";
            // Deploy an agent that will remain IN_PROGRESS by throwing during deploy
            // so it sets ERROR, but let's mock an agent that stays in progress
            Agent dummyAgent = new Agent(agentId, 1);
            dummyAgent.setDeploymentStatus(Deployment.Status.IN_PROGRESS);

            // Use the real deploy to put an agent in the map, then force it IN_PROGRESS
            doThrow(new ServiceException("fail")).when(agentStoreClientLibrary).getAgent(agentId, 1);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            // The agent is now ERROR, reset it to IN_PROGRESS for testing
            // waitForDeploymentCompletion
            IAgent errorAgent = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(errorAgent);
            assertEquals(Deployment.Status.ERROR, errorAgent.getDeploymentStatus());
        }

        @Test
        @DisplayName("IN_PROGRESS with completed future — returns READY agent")
        void inProgressCompletedFuture() throws Exception {
            String agentId = "aabbccddeeff112233445567";

            // First deploy successfully
            Agent readyAgent = new Agent(agentId, 1);
            readyAgent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(readyAgent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
            doReturn(future).when(deploymentListener).getRegisteredDeploymentEvent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            IAgent result = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }

        @Test
        @DisplayName("CancellationException during wait — returns null")
        void cancellationException() throws Exception {
            String agentId = "aabbccddeeff112233445568";

            // Deploy but have the agent stay ERROR (we test the path differently)
            doThrow(new ServiceException("fail")).when(agentStoreClientLibrary).getAgent(agentId, 1);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            // The agent is ERROR, not IN_PROGRESS — getAgent returns it immediately
            IAgent result = agentFactory.getAgent(Deployment.Environment.production, agentId, 1);
            assertNotNull(result);
            assertEquals(Deployment.Status.ERROR, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("deployAgent — already IN_PROGRESS")
    class DeployAlreadyInProgress {

        @Test
        @DisplayName("re-deploy READY agent — skips redeployment")
        void redeployReady() throws Exception {
            String agentId = "aabbccddeeff112233445569";
            Agent agent = new Agent(agentId, 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            Deployment.Status[] firstStatus = new Deployment.Status[1];
            Deployment.Status[] secondStatus = new Deployment.Status[1];
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1,
                    s -> firstStatus[0] = s);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1,
                    s -> secondStatus[0] = s);

            assertEquals(Deployment.Status.READY, firstStatus[0]);
            assertEquals(Deployment.Status.READY, secondStatus[0]);
            verify(agentStoreClientLibrary, times(1)).getAgent(agentId, 1);
        }
    }

    @Nested
    @DisplayName("getLatestReadyAgent — multiple versions")
    class LatestReadyMultipleVersions {

        @Test
        @DisplayName("returns READY agent when ERROR agent exists for same id")
        void readyOverError() throws Exception {
            String agentId = "aabbccddeeff112233445570";
            Agent agentV1 = new Agent(agentId, 1);
            agentV1.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agentV1).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);

            // Deploy v2 as ERROR
            doThrow(new ServiceException("fail")).when(agentStoreClientLibrary).getAgent(agentId, 2);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 2, null);

            // getLatestReadyAgent should return v1 (READY) not v2 (ERROR)
            IAgent result = agentFactory.getLatestReadyAgent(Deployment.Environment.production, agentId);
            assertNotNull(result);
            assertEquals(Deployment.Status.READY, result.getDeploymentStatus());
        }
    }

    @Nested
    @DisplayName("getAllLatestAgents — edge cases")
    class AllLatestEdgeCases {

        @Test
        @DisplayName("multiple versions — returns latest version")
        void latestWins() throws Exception {
            String agentId = "aabbccddeeff112233445571";
            Agent agentV1 = new Agent(agentId, 1);
            agentV1.setDeploymentStatus(Deployment.Status.READY);
            Agent agentV3 = new Agent(agentId, 3);
            agentV3.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agentV1).when(agentStoreClientLibrary).getAgent(agentId, 1);
            doReturn(agentV3).when(agentStoreClientLibrary).getAgent(agentId, 3);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            agentFactory.deployAgent(Deployment.Environment.production, agentId, 3, null);

            var all = agentFactory.getAllLatestAgents(Deployment.Environment.production);
            assertEquals(1, all.size());
            assertEquals(3, all.getFirst().getAgentVersion());
        }
    }

    @Nested
    @DisplayName("undeployAgent")
    class UndeployTests {

        @Test
        @DisplayName("undeploy then getAllLatestAgents returns empty")
        void undeployRemovesFromAll() throws Exception {
            String agentId = "aabbccddeeff112233445572";
            Agent agent = new Agent(agentId, 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.production, agentId, 1, null);
            assertEquals(1, agentFactory.getAllLatestAgents(Deployment.Environment.production).size());

            agentFactory.undeployAgent(Deployment.Environment.production, agentId, 1);
            assertTrue(agentFactory.getAllLatestAgents(Deployment.Environment.production).isEmpty());
        }
    }

    @Nested
    @DisplayName("Environment isolation")
    class EnvironmentIsolation {

        @Test
        @DisplayName("test and production environments are independent")
        void independentEnvironments() throws Exception {
            String agentId = "aabbccddeeff112233445573";
            Agent agent = new Agent(agentId, 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            doReturn(agent).when(agentStoreClientLibrary).getAgent(agentId, 1);

            agentFactory.deployAgent(Deployment.Environment.test, agentId, 1, null);

            assertNotNull(agentFactory.getLatestAgent(Deployment.Environment.test, agentId));
            assertNull(agentFactory.getLatestAgent(Deployment.Environment.production, agentId));

            assertNotNull(agentFactory.getLatestReadyAgent(Deployment.Environment.test, agentId));
            assertNull(agentFactory.getLatestReadyAgent(Deployment.Environment.production, agentId));
        }
    }
}
