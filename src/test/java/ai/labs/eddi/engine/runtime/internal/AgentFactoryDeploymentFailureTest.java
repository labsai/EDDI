/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.VaultGrantGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A deployment in ERROR keeps the reason it failed, so the API can return it —
 * it used to reach the server log only.
 */
@DisplayName("AgentFactory — why a deployment failed")
class AgentFactoryDeploymentFailureTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final Deployment.Environment ENV = Deployment.Environment.production;

    private IAgentStoreClientLibrary library;
    private VaultGrantChecker checker;
    private AgentFactory factory;

    @BeforeEach
    void setUp() {
        library = mock(IAgentStoreClientLibrary.class);
        checker = mock(VaultGrantChecker.class);
        factory = new AgentFactory(library, mock(IDeploymentListener.class), new SimpleMeterRegistry());
        factory.vaultGrantGate = new VaultGrantGate(checker, "enforce");
    }

    @Test
    @DisplayName("a grant refusal is recorded as VAULT_GRANT_MISSING with the secret and the fix")
    void grantRefusalRecorded() {
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of("${vault:gemini-api-key}"));

        factory.deployAgent(ENV, AGENT_ID, 1, null);

        IAgent agent = factory.getAgent(ENV, AGENT_ID, 1);
        assertEquals(Deployment.Status.ERROR, agent.getDeploymentStatus());
        DeploymentFailure failure = agent.getDeploymentFailure();
        assertEquals(DeploymentFailure.VAULT_GRANT_MISSING, failure.code());
        assertEquals("gemini-api-key", failure.secrets().get(0).keyName());
        assertEquals(List.of("POST /secretstore/secrets/default/gemini-api-key/grant/agents/" + AGENT_ID), failure.fix().endpoints());
    }

    @Test
    @DisplayName("a later successful deploy clears the failure")
    void successClearsFailure() throws Exception {
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of("${vault:gemini-api-key}"));
        factory.deployAgent(ENV, AGENT_ID, 1, null);

        // The admin grants it; the next deploy passes the gate.
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of());
        var built = new Agent(AGENT_ID, 1);
        when(library.getAgent(AGENT_ID, 1)).thenReturn(built);
        factory.deployAgent(ENV, AGENT_ID, 1, null);

        IAgent agent = factory.getAgent(ENV, AGENT_ID, 1);
        assertEquals(Deployment.Status.READY, agent.getDeploymentStatus());
        assertNull(agent.getDeploymentFailure());
    }

    @Test
    @DisplayName("any other failure is recorded as DEPLOYMENT_FAILED with the cause's message")
    void otherFailureRecorded() throws Exception {
        when(library.getAgent(AGENT_ID, 1)).thenThrow(new ServiceException("workflow 42 could not be read"));

        factory.deployAgent(ENV, AGENT_ID, 1, null);

        IAgent agent = factory.getAgent(ENV, AGENT_ID, 1);
        assertEquals(Deployment.Status.ERROR, agent.getDeploymentStatus());
        assertEquals(DeploymentFailure.DEPLOYMENT_FAILED, agent.getDeploymentFailure().code());
        assertTrue(agent.getDeploymentFailure().message().contains("workflow 42 could not be read"));
    }

    @Test
    @DisplayName("an unchecked failure ends in ERROR instead of a placeholder stuck IN_PROGRESS")
    void uncheckedFailureIsNotStuckInProgress() throws Exception {
        when(library.getAgent(AGENT_ID, 1)).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> factory.deployAgent(ENV, AGENT_ID, 1, null));

        IAgent agent = factory.getAgent(ENV, AGENT_ID, 1);
        assertEquals(Deployment.Status.ERROR, agent.getDeploymentStatus());
        assertTrue(agent.getDeploymentFailure().message().contains("boom"));
    }

    @Test
    @DisplayName("a very long cause is bounded")
    void longCauseBounded() {
        String message = AgentFactory.failureMessage(AGENT_ID, 1, new RuntimeException("x".repeat(5_000)));
        assertTrue(message.length() < 700, String.valueOf(message.length()));
    }
}
