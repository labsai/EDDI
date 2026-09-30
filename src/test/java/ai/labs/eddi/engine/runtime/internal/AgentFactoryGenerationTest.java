/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * {@link AgentFactory#getLatestReadyAgentOfGeneration}: the version a
 * conversation of a given compatibility generation runs on, on this node.
 */
@DisplayName("AgentFactory — latest ready version of a compatibility generation")
class AgentFactoryGenerationTest {

    private static final Deployment.Environment ENV = Deployment.Environment.production;

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

    private Agent deploy(String agentId, int version, Integer generation) throws Exception {
        return deploy(ENV, agentId, version, generation);
    }

    private Agent deploy(Deployment.Environment environment, String agentId, int version, Integer generation) throws Exception {
        var agent = new Agent(agentId, version);
        agent.setCompatibilityGeneration(generation);
        when(agentStoreClientLibrary.getAgent(agentId, version)).thenReturn(agent);
        factory.deployAgent(environment, agentId, version, null);
        return agent;
    }

    @Test
    @DisplayName("the highest ready version of the generation wins")
    void highestOfGenerationWins() throws Exception {
        deploy("agent-1", 4, 2);
        deploy("agent-1", 6, 2);
        deploy("agent-1", 5, 2);

        assertEquals(6, factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 2).getAgentVersion());
    }

    @Test
    @DisplayName("a newer version of another generation is not a candidate")
    void otherGenerationIgnored() throws Exception {
        deploy("agent-1", 4, 2);
        deploy("agent-1", 7, 3);

        assertEquals(4, factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 2).getAgentVersion());
        assertEquals(7, factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 3).getAgentVersion());
    }

    @Test
    @DisplayName("a version without a generation never matches")
    void legacyVersionNeverMatches() throws Exception {
        deploy("agent-1", 9, null);

        assertNull(factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 1));
    }

    @Test
    @DisplayName("other agents and other environments are not candidates")
    void scopedToAgentAndEnvironment() throws Exception {
        deploy("agent-2", 8, 1);
        deploy(Deployment.Environment.test, "agent-1", 8, 1);

        assertNull(factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 1));
    }

    @Test
    @DisplayName("a version that is not READY is not a candidate")
    void notReadyIgnored() throws Exception {
        deploy("agent-1", 3, 1);
        deploy("agent-1", 4, 1).setDeploymentStatus(Deployment.Status.ERROR);

        assertEquals(3, factory.getLatestReadyAgentOfGeneration(ENV, "agent-1", 1).getAgentVersion());
    }
}
