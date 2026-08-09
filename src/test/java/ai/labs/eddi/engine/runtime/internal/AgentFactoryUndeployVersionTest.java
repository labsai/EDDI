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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * {@code undeployAgent} with a {@code null} version.
 * <p>
 * {@code AgentId} keys on (id, version), so {@code new AgentId(id, null)}
 * equals no key the environment map ever holds. Both dynamic-agent teardown
 * paths — {@code GroupLifecycleOps#cleanupEphemeralAgents} and
 * {@code TeardownAgentTool} — pass null, so the removal was a silent no-op: the
 * agent stayed resolvable in memory after its configuration had been deleted
 * from the store, and {@code eddi_agents_deployed} never came back down. Every
 * assertion here fails against that behaviour.
 *
 * @author ginccc
 */
@DisplayName("AgentFactory — undeploy with a null version")
class AgentFactoryUndeployVersionTest {

    private static final Deployment.Environment ENV = Deployment.Environment.test;
    private static final String GAUGE = "eddi_agents_deployed";

    @Mock
    private IAgentStoreClientLibrary agentStoreClientLibrary;
    @Mock
    private IDeploymentListener deploymentListener;

    private SimpleMeterRegistry meterRegistry;
    private AgentFactory factory;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        factory = new AgentFactory(agentStoreClientLibrary, deploymentListener, meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private void deploy(String agentId, int version) throws Exception {
        var agent = new Agent(agentId, version);
        when(agentStoreClientLibrary.getAgent(agentId, version)).thenReturn(agent);
        factory.deployAgent(ENV, agentId, version, null);
    }

    private double deployedGauge() {
        return meterRegistry.find(GAUGE).gauge().value();
    }

    @Test
    @DisplayName("null version removes the deployed agent instead of silently doing nothing")
    void nullVersionRemovesTheAgent() throws Exception {
        deploy("ephemeral-1", 1);
        assertNotNull(factory.getLatestReadyAgent(ENV, "ephemeral-1"), "precondition: agent is deployed");

        factory.undeployAgent(ENV, "ephemeral-1", null);

        assertNull(factory.getLatestReadyAgent(ENV, "ephemeral-1"),
                "a null-version undeploy must remove the agent — this is the teardown shape the dynamic-agent paths use");
        assertNull(factory.getAgent(ENV, "ephemeral-1", 1));
    }

    @Test
    @DisplayName("null version removes every deployed version, not just one")
    void nullVersionRemovesAllVersions() throws Exception {
        deploy("multi", 1);
        deploy("multi", 2);
        assertEquals(2.0, deployedGauge(), "precondition: both versions tracked");

        factory.undeployAgent(ENV, "multi", null);

        assertNull(factory.getAgent(ENV, "multi", 1));
        assertNull(factory.getAgent(ENV, "multi", 2));
        assertNull(factory.getLatestAgent(ENV, "multi"));
        assertEquals(0.0, deployedGauge());
    }

    @Test
    @DisplayName("null version decrements the deployed-agents gauge")
    void nullVersionDecrementsTheGauge() throws Exception {
        deploy("ephemeral-2", 1);
        assertEquals(1.0, deployedGauge(), "precondition: the deploy was tracked");

        factory.undeployAgent(ENV, "ephemeral-2", null);

        assertEquals(0.0, deployedGauge(),
                "the gauge grew for the lifetime of the process because the null-version removal never matched a key");
    }

    @Test
    @DisplayName("null version leaves other agents alone")
    void nullVersionDoesNotTouchOtherAgents() throws Exception {
        deploy("keep-me", 1);
        deploy("remove-me", 1);

        factory.undeployAgent(ENV, "remove-me", null);

        assertNotNull(factory.getLatestReadyAgent(ENV, "keep-me"));
        assertNull(factory.getLatestReadyAgent(ENV, "remove-me"));
        assertEquals(1.0, deployedGauge());
    }

    @Test
    @DisplayName("an explicit version still removes only that version")
    void explicitVersionRemovesOnlyThatVersion() throws Exception {
        deploy("versioned", 1);
        deploy("versioned", 2);

        factory.undeployAgent(ENV, "versioned", 1);

        assertNull(factory.getAgent(ENV, "versioned", 1));
        assertNotNull(factory.getAgent(ENV, "versioned", 2), "the REST undeploy path passes a real version and must stay exact");
        assertEquals(1.0, deployedGauge());
    }

    @Test
    @DisplayName("undeploying an unknown agent is a no-op, not an error")
    void unknownAgentIsANoOp() {
        factory.undeployAgent(ENV, "never-deployed", null);
        assertEquals(0.0, deployedGauge());
    }

    @Test
    @DisplayName("repeated deploys of the same version are tracked once")
    void repeatedDeploysTrackedOnce() throws Exception {
        deploy("idempotent", 1);
        factory.deployAgent(ENV, "idempotent", 1, null);
        factory.deployAgent(ENV, "idempotent", 1, null);

        assertEquals(1.0, deployedGauge(), "addIfAbsent must not let a redeploy inflate the gauge");
    }
}
