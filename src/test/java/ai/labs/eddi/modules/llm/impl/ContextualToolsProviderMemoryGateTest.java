/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The user-memory tool's enablement rule, pinned at the {@code contribute()}
 * level.
 * <p>
 * The rule is deliberate and two-sided. With {@code enableBuiltInTools: true}
 * and a memory config present, the tool MUST assemble — this is the conjunction
 * whose third leg (the config surviving past {@code init()}) silently failed on
 * every live turn, leaving the model to confabulate saves it never made. With
 * built-ins off, the tool must NOT assemble even though memory is configured:
 * user memory is a persistent cross-conversation <em>write</em>, and a
 * task-level "no built-in capability" statement wins over the agent-level
 * opt-in. That restrictive half is a security posture, so a regression in
 * either direction matters.
 */
@DisplayName("user-memory tool enablement")
class ContextualToolsProviderMemoryGateTest {

    private IConversationMemory memory;
    private LlmConfiguration.Task task;
    private ContextualToolsProvider provider;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getUserId()).thenReturn("user-1");
        lenient().when(memory.getAgentId()).thenReturn("agent-gate-1");
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        lenient().when(memory.getUserMemoryConfig()).thenReturn(new AgentConfiguration.UserMemoryConfig());

        task = mock(LlmConfiguration.Task.class);

        provider = new ContextualToolsProvider(mock(IUserMemoryStore.class), mock(IAttachmentStore.class),
                mock(AttachmentTextExtractor.class));
    }

    private ToolContribution contribute() {
        return provider.contribute(new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-gate-1", null));
    }

    @Test
    @DisplayName("built-ins on + memory config present → UserMemoryTool assembles")
    void memoryToolAssemblesWhenBothSwitchesAgree() {
        when(task.getEnableBuiltInTools()).thenReturn(true);

        ToolContribution contribution = contribute();

        assertFalse(contribution.specs().isEmpty(),
                "with every switch on, no tool means the agent will confabulate saves again");
        assertTrue(contribution.specs().stream().anyMatch(spec -> spec.name().toLowerCase().contains("memor")),
                "the assembled tools must include the user-memory tool: " + contribution.specs());
    }

    @Test
    @DisplayName("built-ins off → no memory tool, despite the memory config")
    void builtInsOffWinsOverTheMemoryConfig() {
        when(task.getEnableBuiltInTools()).thenReturn(false);

        assertTrue(contribute().specs().isEmpty(),
                "a task-level 'no built-ins' must win over the agent-level opt-in for a cross-conversation write");
    }

    @Test
    @DisplayName("null enableBuiltInTools means off — null is the default")
    void nullBuiltInsMeansOff() {
        when(task.getEnableBuiltInTools()).thenReturn(null);

        assertTrue(contribute().specs().isEmpty());
    }

    @Test
    @DisplayName("no memory config → nothing assembles even with built-ins on")
    void noConfigNoTool() {
        when(task.getEnableBuiltInTools()).thenReturn(true);
        when(memory.getUserMemoryConfig()).thenReturn(null);

        assertTrue(contribute().specs().isEmpty(),
                "the config carries enableMemoryTools' verdict — absent means the agent never opted in");
    }
}
