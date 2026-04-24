/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTest {

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent("agent-1", 3);
    }

    @Test
    void constructor_setsFields() {
        assertEquals("agent-1", agent.getAgentId());
        assertEquals(3, agent.getAgentVersion());
    }

    @Test
    void deploymentStatus_defaultNull() {
        assertNull(agent.getDeploymentStatus());
    }

    @Test
    void setDeploymentStatus_updatesStatus() {
        agent.setDeploymentStatus(Deployment.Status.READY);
        assertEquals(Deployment.Status.READY, agent.getDeploymentStatus());
    }

    @Test
    void userMemoryConfig_defaultNull() {
        assertNull(agent.getUserMemoryConfig());
    }

    @Test
    void setUserMemoryConfig_updatesConfig() {
        var config = new AgentConfiguration.UserMemoryConfig();
        agent.setUserMemoryConfig(config);
        assertSame(config, agent.getUserMemoryConfig());
    }

    @Test
    void memoryPolicy_defaultNull() {
        assertNull(agent.getMemoryPolicy());
    }

    @Test
    void setMemoryPolicy_updatesPolicy() {
        var policy = new AgentConfiguration.MemoryPolicy();
        agent.setMemoryPolicy(policy);
        assertSame(policy, agent.getMemoryPolicy());
    }

    @Test
    void addWorkflow_addsSuccessfully() throws IllegalAccessException {
        var workflow = mock(IExecutableWorkflow.class);
        agent.addWorkflow(workflow);
        // No exception means it was added
    }

    @Test
    void continueConversation_returnsConversationWithMemory() throws Exception {
        var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
        var propertiesHandler = mock(IPropertiesHandler.class);
        var outputRenderer = mock(IConversation.IConversationOutputRenderer.class);

        IConversation conversation = agent.continueConversation(memory, propertiesHandler, outputRenderer);

        assertNotNull(conversation);
        assertSame(memory, conversation.getConversationMemory());
    }

    @Test
    void continueConversation_withMemoryPolicy_appliesPolicy() throws Exception {
        var policy = new AgentConfiguration.MemoryPolicy();
        agent.setMemoryPolicy(policy);

        var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
        var propertiesHandler = mock(IPropertiesHandler.class);
        var outputRenderer = mock(IConversation.IConversationOutputRenderer.class);

        agent.continueConversation(memory, propertiesHandler, outputRenderer);

        assertSame(policy, memory.getMemoryPolicy());
    }
}
