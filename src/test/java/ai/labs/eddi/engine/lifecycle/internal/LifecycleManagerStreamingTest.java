package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the streaming event emission in {@link LifecycleManager}.
 * Verifies that task_start/task_complete events are emitted when an event
 * sink is present, and that the workflow works normally without one.
 */
class LifecycleManagerStreamingTest {

    private LifecycleManager lifecycleManager;
    private IConversationMemory memory;
    private ConversationEventSink eventSink;
    private ILifecycleTask task1;
    private ILifecycleTask task2;

    @BeforeEach
    void setUp() throws Exception {
        var componentCache = mock(IComponentCache.class);
        var packageId = mock(IResourceStore.IResourceId.class);
        when(packageId.getId()).thenReturn("pkg-1");
        when(packageId.getVersion()).thenReturn(1);

        // Return empty component maps for all tasks
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

        lifecycleManager = new LifecycleManager(componentCache, packageId);

        memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(currentStep.getLatestData(anyString())).thenReturn(null);

        eventSink = mock(ConversationEventSink.class);

        task1 = mock(ILifecycleTask.class);
        when(task1.getId()).thenReturn("ai.labs.parser");
        when(task1.getType()).thenReturn("expressions");

        task2 = mock(ILifecycleTask.class);
        when(task2.getId()).thenReturn("ai.labs.behavior");
        when(task2.getType()).thenReturn("behavior_rules");

        lifecycleManager.addLifecycleTask(task1);
        lifecycleManager.addLifecycleTask(task2);
    }

    @Test
    @DisplayName("With event sink: emits task_start and task_complete for each task")
    void executeLifecycle_withEventSink_emitsEvents() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        lifecycleManager.executeLifecycle(memory, null);

        // task_start for each task
        verify(eventSink).onTaskStart("ai.labs.parser", "expressions", 0);
        verify(eventSink).onTaskStart("ai.labs.behavior", "behavior_rules", 1);

        // task_complete for each task
        verify(eventSink).onTaskComplete(eq("ai.labs.parser"), eq("expressions"),
                anyLong(), any());
        verify(eventSink).onTaskComplete(eq("ai.labs.behavior"), eq("behavior_rules"),
                anyLong(), any());

        // Tasks are still executed
        verify(task1).execute(eq(memory), any());
        verify(task2).execute(eq(memory), any());
    }

    @Test
    @DisplayName("Without event sink: no events emitted, tasks execute normally")
    void executeLifecycle_withoutEventSink_noEvents() throws Exception {
        when(memory.getEventSink()).thenReturn(null);

        lifecycleManager.executeLifecycle(memory, null);

        // Tasks are executed
        verify(task1).execute(eq(memory), any());
        verify(task2).execute(eq(memory), any());

        // No event sink interactions
        verifyNoInteractions(eventSink);
    }

    @Test
    @DisplayName("Event ordering: task_start comes before task_complete")
    void executeLifecycle_eventsInOrder() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        var inOrder = inOrder(eventSink, task1, task2);

        lifecycleManager.executeLifecycle(memory, null);

        // Task 1: start → execute → complete
        inOrder.verify(eventSink).onTaskStart("ai.labs.parser", "expressions", 0);
        inOrder.verify(task1).execute(eq(memory), any());
        inOrder.verify(eventSink).onTaskComplete(eq("ai.labs.parser"), eq("expressions"),
                anyLong(), any());

        // Task 2: start → execute → complete
        inOrder.verify(eventSink).onTaskStart("ai.labs.behavior", "behavior_rules", 1);
        inOrder.verify(task2).execute(eq(memory), any());
        inOrder.verify(eventSink).onTaskComplete(eq("ai.labs.behavior"), eq("behavior_rules"),
                anyLong(), any());
    }

    @Test
    @DisplayName("task_complete duration is non-negative")
    void executeLifecycle_durationIsNonNegative() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        lifecycleManager.executeLifecycle(memory, null);

        verify(eventSink, times(2)).onTaskComplete(anyString(), anyString(),
                longThat(duration -> duration >= 0), any());
    }

    @Test
    @DisplayName("LifecycleException during task still propagates")
    void executeLifecycle_taskThrows_exceptionPropagates() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);
        doThrow(new LifecycleException("test error")).when(task1).execute(any(), any());

        assertThrows(LifecycleException.class,
                () -> lifecycleManager.executeLifecycle(memory, null));

        // task_start should have been emitted before the error
        verify(eventSink).onTaskStart("ai.labs.parser", "expressions", 0);
        // task_complete should NOT have been emitted for the failed task
        verify(eventSink, never()).onTaskComplete(eq("ai.labs.parser"), anyString(),
                anyLong(), any());
    }
}
