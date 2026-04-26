/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.datastore.model.ResourceId;
import ai.labs.eddi.engine.lifecycle.internal.ComponentCache;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
@DisplayName("LifecycleManager")
public class LifecycleManagerTest {
    private ILifecycleManager lifecycleManager;
    private IConversationMemory memory;

    @BeforeEach
    public void setUp() {
        lifecycleManager = new LifecycleManager(new ComponentCache(), new ResourceId("id", 1));
        memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        Mockito.when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);
    }

    @Test
    @DisplayName("should execute lifecycle task when memory is valid")
    public void testExecuteLifecycle() throws Exception {
        // setup
        ILifecycleTask lifecycleTask = mock(ILifecycleTask.class);
        lifecycleManager.addLifecycleTask(lifecycleTask);

        // test
        lifecycleManager.executeLifecycle(memory, null);

        // assert
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).execute(memory, null);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when memory is null")
    public void testValidationWhenMemoryIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleManager.executeLifecycle(null, null));
    }

    @Test
    @DisplayName("should throw IllegalArgumentException when lifecycle task is null")
    public void testValidationWhenLifecycleIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> lifecycleManager.addLifecycleTask(null));
    }

    // === L4 Fix: Prefix match direction tests ===

    @Test
    @DisplayName("should only execute tasks matching filter type and after")
    public void testExecuteLifecycle_FilterByType_MatchesPrefix() throws Exception {
        // Setup: tasks with types "behavior_rules" and "langchain"
        ILifecycleTask behaviorTask = mock(ILifecycleTask.class);
        when(behaviorTask.getType()).thenReturn("behavior_rules");
        when(behaviorTask.getId()).thenReturn("behavior_rules");

        ILifecycleTask langchainTask = mock(ILifecycleTask.class);
        when(langchainTask.getType()).thenReturn("langchain");
        when(langchainTask.getId()).thenReturn("langchain");

        lifecycleManager.addLifecycleTask(behaviorTask);
        lifecycleManager.addLifecycleTask(langchainTask);

        // Filter by "langchain" - should only execute langchain and after
        lifecycleManager.executeLifecycle(memory, List.of("langchain"));

        verify(behaviorTask, never()).execute(any(), any());
        verify(langchainTask, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("should match task type by prefix (e.g., 'behavior' matches 'behavior_rules')")
    public void testExecuteLifecycle_FilterByType_PrefixMatchWorks() throws Exception {
        // Setup: task with type "behavior_rules"
        ILifecycleTask behaviorTask = mock(ILifecycleTask.class);
        when(behaviorTask.getType()).thenReturn("behavior_rules");
        when(behaviorTask.getId()).thenReturn("behavior_rules");

        ILifecycleTask outputTask = mock(ILifecycleTask.class);
        when(outputTask.getType()).thenReturn("output");
        when(outputTask.getId()).thenReturn("output");

        lifecycleManager.addLifecycleTask(behaviorTask);
        lifecycleManager.addLifecycleTask(outputTask);

        // Filter by "behavior" prefix - should match "behavior_rules" task type
        lifecycleManager.executeLifecycle(memory, List.of("behavior"));

        verify(behaviorTask, times(1)).execute(any(), any());
        verify(outputTask, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("should NOT match when filter is longer than task type (no reverse prefix)")
    public void testExecuteLifecycle_FilterByType_DoesNotMatchReversed() throws Exception {
        // With L4 fix: type.startsWith(filter) not filter.startsWith(type)
        // Filter "behavior_rules_extended" should NOT match task type "behavior_rules"
        // because "behavior_rules".startsWith("behavior_rules_extended") is false
        ILifecycleTask behaviorTask = mock(ILifecycleTask.class);
        when(behaviorTask.getType()).thenReturn("behavior_rules");
        when(behaviorTask.getId()).thenReturn("behavior_rules");

        lifecycleManager.addLifecycleTask(behaviorTask);

        // Filter with longer string - should NOT match
        lifecycleManager.executeLifecycle(memory, List.of("behavior_rules_extended"));

        verify(behaviorTask, never()).execute(any(), any());
    }

    @Test
    @DisplayName("should execute all tasks when no filter is applied")
    public void testExecuteLifecycle_NoFilter_ExecutesAll() throws Exception {
        ILifecycleTask task1 = mock(ILifecycleTask.class);
        when(task1.getType()).thenReturn("parser");
        when(task1.getId()).thenReturn("parser");

        ILifecycleTask task2 = mock(ILifecycleTask.class);
        when(task2.getType()).thenReturn("output");
        when(task2.getId()).thenReturn("output");

        lifecycleManager.addLifecycleTask(task1);
        lifecycleManager.addLifecycleTask(task2);

        lifecycleManager.executeLifecycle(memory, null);

        verify(task1, times(1)).execute(any(), any());
        verify(task2, times(1)).execute(any(), any());
    }

    @Test
    @DisplayName("should execute all tasks when filter is empty list")
    public void testExecuteLifecycle_EmptyFilter_ExecutesAll() throws Exception {
        ILifecycleTask task = mock(ILifecycleTask.class);
        when(task.getType()).thenReturn("parser");
        when(task.getId()).thenReturn("parser");

        lifecycleManager.addLifecycleTask(task);

        lifecycleManager.executeLifecycle(memory, List.of());

        verify(task, times(1)).execute(any(), any());
    }
}
