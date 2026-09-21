/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@code ContextualToolsProvider.resolveGroupIds}.
 * <p>
 * {@code groupId} reaches a member conversation as a <b>context</b> value; no
 * code path anywhere writes it as a conversation <em>property</em>. Reading
 * only the property (as this did before) left {@code groupIds} empty on every
 * group member turn, so {@code UserMemoryTool} silently ran self-scoped and
 * could neither recall nor write group-visible memories — while conversation
 * init, which reads the context correctly, loaded them. Raised by Copilot on PR
 * #626.
 *
 * @author tests
 */
class ContextualToolsProviderGroupIdTest {

    private static final String CONTEXT_KEY = "context:groupId";

    @SuppressWarnings("unchecked")
    private IData<Object> contextData(Object value) {
        IData<Object> data = mock(IData.class);
        when(data.getResult()).thenReturn(value);
        return data;
    }

    private IConversationMemory memoryWithCurrentStepContext(Object value) {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        // Build the inner mock BEFORE opening the when(...) — creating a mock inside
        // an in-progress stubbing is what Mockito reports as UnfinishedStubbing.
        var data = contextData(value);
        when(currentStep.getLatestData(CONTEXT_KEY)).thenReturn(data);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        return memory;
    }

    @Test
    void groupIdFromCurrentStepContext_isResolved() {
        var memory = memoryWithCurrentStepContext(new Context(Context.ContextType.string, "group-42"));

        assertEquals(List.of("group-42"), ContextualToolsProvider.resolveGroupIds(memory),
                "the context value MemberTurnExecutor injects must reach UserMemoryTool's group scope");
    }

    @Test
    void groupIdStoredAsBareString_isResolved() {
        // Defensive: a persisted/rehydrated step may hand back the raw value rather
        // than a Context wrapper.
        var memory = memoryWithCurrentStepContext("group-7");

        assertEquals(List.of("group-7"), ContextualToolsProvider.resolveGroupIds(memory));
    }

    @Test
    void groupIdFromAnEarlierStep_isResolved() {
        // A resumed turn re-enters without the original context map, so the value
        // only exists on an earlier step.
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(currentStep.getLatestData(CONTEXT_KEY)).thenReturn(null);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        var earlierData = List.of(contextData(new Context(Context.ContextType.string, "group-earlier")));
        when(allSteps.getAllLatestData(CONTEXT_KEY)).thenReturn(earlierData);
        when(memory.getAllSteps()).thenReturn(allSteps);

        assertEquals(List.of("group-earlier"), ContextualToolsProvider.resolveGroupIds(memory));
    }

    @Test
    void propertyFallback_stillWorks() {
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(null);
        when(memory.getAllSteps()).thenReturn(null);
        var props = mock(IConversationMemory.IConversationProperties.class);
        when(props.get("groupId")).thenReturn(new Property("groupId", "group-prop", Property.Scope.conversation));
        when(memory.getConversationProperties()).thenReturn(props);

        assertEquals(List.of("group-prop"), ContextualToolsProvider.resolveGroupIds(memory),
                "a config that genuinely sets a groupId property must keep working");
    }

    @Test
    void noGroupContextAnywhere_yieldsEmpty() {
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(null);
        when(memory.getAllSteps()).thenReturn(null);
        when(memory.getConversationProperties()).thenReturn(null);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory).isEmpty(),
                "an ordinary non-group conversation must stay self-scoped");
    }

    @Test
    void blankGroupId_isTreatedAsAbsent() {
        var memory = memoryWithCurrentStepContext(new Context(Context.ContextType.string, "   "));
        when(memory.getAllSteps()).thenReturn(null);
        when(memory.getConversationProperties()).thenReturn(null);

        assertTrue(ContextualToolsProvider.resolveGroupIds(memory).isEmpty(),
                "a blank id must not become a real group scope");
    }
}
