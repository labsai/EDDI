/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code maxWritesPerTurn} bounds the turn, not one tool instance.
 * <p>
 * A turn assembles its tools more than once — once per step of an agent-mode
 * model cascade, and again when a turn paused for a HITL tool approval resumes
 * — and every assembly builds a new {@link UserMemoryTool}. While the count was
 * a field of the tool, each of them started from zero, so a three-step cascade
 * could write three times the configured budget. The memory here is real, so
 * the count must survive on its current step.
 */
@DisplayName("user-memory write budget per turn")
class ContextualToolsProviderTurnBudgetTest {

    private IUserMemoryStore store;
    private ContextualToolsProvider provider;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IUserMemoryStore.class);
        when(store.upsert(any())).thenReturn("entry-id");
        provider = new ContextualToolsProvider(store, mock(IAttachmentStore.class), mock(AttachmentTextExtractor.class));

        var config = new AgentConfiguration.UserMemoryConfig();
        config.getGuardrails().setMaxWritesPerTurn(1);
        memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        memory.setUserMemoryConfig(config);
        memory.setMemoryToolsEnabled(true);
    }

    private UserMemoryTool assemble() {
        List<Object> tools = new ArrayList<>();
        provider.addUserMemoryToolIfEnabled(tools, memory);
        assertEquals(1, tools.size(), "the memory tool must assemble: " + tools);
        return (UserMemoryTool) tools.get(0);
    }

    @Test
    @DisplayName("a second assembly in the same turn sees the writes of the first")
    void budgetSurvivesToolReassemblyWithinATurn() throws Exception {
        UserMemoryTool firstCascadeStep = assemble();
        UserMemoryTool secondCascadeStep = assemble();

        assertTrue(firstCascadeStep.rememberFact("a", "1", "fact", "self").contains("✅ Remembered"));
        String refused = secondCascadeStep.rememberFact("b", "2", "fact", "self");

        assertTrue(refused.contains("Maximum writes per turn (1) reached"), refused);
        verify(store, times(1)).upsert(any());
    }

    @Test
    @DisplayName("a forget in a later assembly spends the same budget")
    void forgetInALaterAssemblyIsRefusedOnceTheBudgetIsSpent() throws Exception {
        var older = new UserMemoryEntry("id-old", "user-1", "old", "x", "fact", Visibility.self, "agent-1", List.of(), "conv-0", false, 0,
                Instant.now(), Instant.now());
        when(store.getByKey("user-1", "old")).thenReturn(Optional.of(older));
        assertTrue(assemble().rememberFact("a", "1", "fact", "self").contains("✅ Remembered"));

        String refused = assemble().forgetFact("old");

        assertTrue(refused.contains("Maximum writes per turn (1) reached"), refused);
        verify(store, never()).deleteEntry(any());
    }

    @Test
    @DisplayName("the next turn starts with a fresh budget")
    void nextTurnStartsAgainFromZero() throws Exception {
        assertTrue(assemble().rememberFact("a", "1", "fact", "self").contains("✅ Remembered"));

        memory.startNextStep();

        assertTrue(assemble().rememberFact("b", "2", "fact", "self").contains("✅ Remembered"),
                "the budget is per turn, not per conversation");
        verify(store, times(2)).upsert(any());
    }
}
