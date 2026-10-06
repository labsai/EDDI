/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.compat.AgentCompatibilityLint;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deploy-time compatibility lint reaches the deployed agent as advisory
 * warnings, and can never turn a deployment into a failure.
 */
@DisplayName("AgentFactory - compatibility lint at deployment")
class AgentFactoryCompatibilityLintTest {

    private IAgentStoreClientLibrary library;
    private AgentCompatibilityLint lint;
    private AgentFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        library = mock(IAgentStoreClientLibrary.class);
        lint = mock(AgentCompatibilityLint.class);
        factory = new AgentFactory(library, mock(IDeploymentListener.class), new SimpleMeterRegistry());
        factory.compatibilityLint = lint;
        when(library.getAgent("agent1", 1)).thenReturn(new Agent("agent1", 1));
    }

    @Test
    @DisplayName("warnings are attached to the READY agent")
    void warningsAreAttached() {
        when(lint.lint("agent1", 1)).thenReturn(List.of("[LLM_API_KEY_TEMPLATE] llm task 'chat': ..."));

        factory.deployAgent(Deployment.Environment.production, "agent1", 1, null);

        var agent = factory.getAgent(Deployment.Environment.production, "agent1", 1);
        assertNotNull(agent);
        assertEquals(Deployment.Status.READY, agent.getDeploymentStatus());
        assertEquals(List.of("[LLM_API_KEY_TEMPLATE] llm task 'chat': ..."), agent.getDeploymentWarnings());
    }

    @Test
    @DisplayName("a lint that throws does not fail the deployment")
    void failingLintDoesNotBlockDeployment() {
        when(lint.lint("agent1", 1)).thenThrow(new IllegalStateException("boom"));

        factory.deployAgent(Deployment.Environment.production, "agent1", 1, null);

        var agent = factory.getAgent(Deployment.Environment.production, "agent1", 1);
        assertEquals(Deployment.Status.READY, agent.getDeploymentStatus());
        assertEquals(List.of(), agent.getDeploymentWarnings());
    }
}
