/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent's user-memory configuration has to reach memory on every turn, not
 * only the first.
 * <p>
 * {@code userMemoryConfig} is not part of the persisted conversation snapshot
 * and every request rebuilds memory from the store, so assigning it in
 * {@code init()} alone left it null from the second turn onwards. The gate in
 * {@code ContextualToolsProvider.addUserMemoryToolIfEnabled} reads exactly that
 * field, so {@code UserMemoryTool} was never assembled on any turn a user could
 * talk to: an agent with memory fully enabled and verified could not write a
 * single memory, and said nothing about it. The model, handed no tool,
 * confabulated success — once emitting raw pseudo-XML tool syntax into
 * user-visible output.
 * <p>
 * A control from the same sweep proves the isolation: on the same agent and the
 * same turn the calculator built-in ran fine, so tool assembly worked and only
 * the memory tool was missing.
 */
@DisplayName("user memory config reaches every turn")
class ConversationUserMemoryConfigTest {

    private ConversationMemory memory;
    private IPropertiesHandler propertiesHandler;
    private IExecutableWorkflow workflow;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemory.class);
        propertiesHandler = mock(IPropertiesHandler.class);
        workflow = mock(IExecutableWorkflow.class);

        when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        when(memory.getConversationProperties()).thenReturn(mock(IConversationProperties.class));
        when(workflow.getLifecycleManager()).thenReturn(mock(ILifecycleManager.class));
        when(workflow.getWorkflowId()).thenReturn("wf-test");
    }

    private Conversation createConversation() {
        return new Conversation(List.of(workflow), memory, propertiesHandler,
                mock(IConversation.IConversationOutputRenderer.class));
    }

    @Test
    @DisplayName("constructing a turn applies the config — not just init()")
    void configIsAppliedPerTurn() {
        var config = new AgentConfiguration.UserMemoryConfig();
        when(propertiesHandler.getUserMemoryConfig()).thenReturn(config);

        createConversation();

        // Agent#continueConversation builds a Conversation for say, resume and rerun
        // alike, so the constructor is the one point every turn passes through.
        verify(memory).setUserMemoryConfig(config);
    }

    @Test
    @DisplayName("an agent without memory enabled sets nothing")
    void noConfigSetsNothing() {
        when(propertiesHandler.getUserMemoryConfig()).thenReturn(null);

        createConversation();

        verify(memory, never()).setUserMemoryConfig(null);
    }
}
