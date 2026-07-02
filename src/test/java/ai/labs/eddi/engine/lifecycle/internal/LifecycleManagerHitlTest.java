/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationPauseException;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LifecycleManager HITL Tests")
class LifecycleManagerHitlTest {

    private IComponentCache componentCache;
    private IResourceStore.IResourceId workflowId;
    private LifecycleManager lifecycleManager;

    @BeforeEach
    void setUp() {
        componentCache = mock(IComponentCache.class);
        workflowId = mock(IResourceStore.IResourceId.class);
        when(workflowId.getId()).thenReturn("wf1");
        when(workflowId.getVersion()).thenReturn(1);

        lifecycleManager = new LifecycleManager(componentCache, workflowId);
    }

    /**
     * Helper: creates a mock task with the given id and type.
     */
    private ILifecycleTask mockTask(String id, String type) {
        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId(id));
        when(task.getType()).thenReturn(type);
        return task;
    }

    /**
     * Helper: creates a mock memory with a current step that returns null for
     * ACTIONS (no actions set). ComponentCache returns empty maps for any id.
     */
    private IConversationMemory mockMemory() {
        var memory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());
        return memory;
    }

    @Nested
    @DisplayName("Cancel Check (isCancelled)")
    class CancelCheckTests {

        @Test
        @DisplayName("isCancelled()=true throws ConversationStopException before task executes")
        void cancelledThrowsStop() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            when(memory.isCancelled()).thenReturn(true);

            assertThrows(ConversationStopException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Task should never have executed
            verify(task, never()).execute(any(), any());
        }

        @Test
        @DisplayName("isCancelled()=false lets task execute normally")
        void notCancelledExecutesTask() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            when(memory.isCancelled()).thenReturn(false);

            lifecycleManager.executeLifecycle(memory, null);

            verify(task).execute(eq(memory), any());
        }
    }

    @Nested
    @DisplayName("PAUSE_CONVERSATION Action")
    class PauseConversationTests {

        @Test
        @DisplayName("PAUSE_CONVERSATION in actions throws ConversationPauseException")
        @SuppressWarnings("unchecked")
        void pauseActionThrowsPause() throws Exception {
            var task = mockTask("behavior", "behavior_rules");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            // Delta-based check: before task runs → no actions; after task →
            // PAUSE_CONVERSATION
            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS))
                    .thenReturn(null) // snapshot before task
                    .thenReturn(actionData); // check after task
            when(actionData.getResult()).thenReturn(List.of(IConversation.PAUSE_CONVERSATION));

            var ex = assertThrows(ConversationPauseException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            assertEquals("wf1", ex.getPausedWorkflowId());
            assertEquals(0, ex.getPausedAbsoluteTaskIndex());
        }

        @Test
        @DisplayName("PAUSE_CONVERSATION carries correct absolute task index")
        @SuppressWarnings("unchecked")
        void pauseAtCorrectIndex() throws Exception {
            // Add 3 tasks so the pause-triggering task is at index 2
            var task0 = mockTask("parser", "input");
            var task1 = mockTask("behavior", "behavior_rules");
            var task2 = mockTask("llm", "langchain");

            lifecycleManager.addLifecycleTask(task0);
            lifecycleManager.addLifecycleTask(task1);
            lifecycleManager.addLifecycleTask(task2);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            // Use answer-based stubbing to return PAUSE only when called after task2
            var noActionData = mock(IData.class);
            when(noActionData.getResult()).thenReturn(List.of());

            var pauseActionData = mock(IData.class);
            when(pauseActionData.getResult()).thenReturn(List.of(IConversation.PAUSE_CONVERSATION));

            // Track which task was last executed to determine what getLatestData returns
            java.util.concurrent.atomic.AtomicInteger lastExecutedTask = new java.util.concurrent.atomic.AtomicInteger(-1);
            doAnswer(inv -> {
                lastExecutedTask.set(0);
                return null;
            }).when(task0).execute(any(), any());
            doAnswer(inv -> {
                lastExecutedTask.set(1);
                return null;
            }).when(task1).execute(any(), any());
            doAnswer(inv -> {
                lastExecutedTask.set(2);
                return null;
            }).when(task2).execute(any(), any());

            when(currentStep.getLatestData(ACTIONS)).thenAnswer(inv -> lastExecutedTask.get() == 2 ? pauseActionData : noActionData);

            var ex = assertThrows(ConversationPauseException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            assertEquals(2, ex.getPausedAbsoluteTaskIndex());
        }
    }

    @Nested
    @DisplayName("STOP_CONVERSATION Action (existing behavior)")
    class StopConversationTests {

        @Test
        @DisplayName("STOP_CONVERSATION still throws ConversationStopException")
        @SuppressWarnings("unchecked")
        void stopActionThrowsStop() throws Exception {
            var task = mockTask("behavior", "behavior_rules");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of(IConversation.STOP_CONVERSATION));

            assertThrows(ConversationStopException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));
        }
    }

    @Nested
    @DisplayName("Empty/Null Actions")
    class EmptyActionsTests {

        @Test
        @DisplayName("empty actions list — no exception thrown")
        @SuppressWarnings("unchecked")
        void emptyActionsNoException() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of());

            assertDoesNotThrow(() -> lifecycleManager.executeLifecycle(memory, null));
            verify(task).execute(eq(memory), any());
        }

        @Test
        @DisplayName("null actions data — no exception thrown")
        void nullActionsNoException() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            when(memory.getCurrentStep().getLatestData(ACTIONS)).thenReturn(null);

            assertDoesNotThrow(() -> lifecycleManager.executeLifecycle(memory, null));
            verify(task).execute(eq(memory), any());
        }

        @Test
        @DisplayName("null result in actions data — no exception thrown")
        @SuppressWarnings("unchecked")
        void nullResultNoException() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(null);

            assertDoesNotThrow(() -> lifecycleManager.executeLifecycle(memory, null));
        }
    }

    @Nested
    @DisplayName("executeLifecycleFromIndex")
    class ExecuteFromIndexTests {

        @Test
        @DisplayName("starting from index 1 — skips task 0, runs task 1")
        void startFromIndex1() throws Exception {
            var task0 = mockTask("parser", "input");
            var task1 = mockTask("behavior", "behavior_rules");

            lifecycleManager.addLifecycleTask(task0);
            lifecycleManager.addLifecycleTask(task1);

            var memory = mockMemory();

            lifecycleManager.executeLifecycleFromIndex(memory, 1);

            verify(task0, never()).execute(any(), any());
            verify(task1).execute(eq(memory), any());
        }

        @Test
        @DisplayName("index >= list size — runs no tasks")
        void indexBeyondSize() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();

            lifecycleManager.executeLifecycleFromIndex(memory, 5);

            verify(task, never()).execute(any(), any());
        }

        @Test
        @DisplayName("index 0 — runs all tasks")
        void indexZeroRunsAll() throws Exception {
            var task0 = mockTask("parser", "input");
            var task1 = mockTask("behavior", "behavior_rules");

            lifecycleManager.addLifecycleTask(task0);
            lifecycleManager.addLifecycleTask(task1);

            var memory = mockMemory();

            lifecycleManager.executeLifecycleFromIndex(memory, 0);

            var inOrder = inOrder(task0, task1);
            inOrder.verify(task0).execute(eq(memory), any());
            inOrder.verify(task1).execute(eq(memory), any());
        }

        @Test
        @DisplayName("executeLifecycleFromIndex respects cancel check")
        void fromIndexRespectsCancel() throws Exception {
            var task = mockTask("parser", "input");
            lifecycleManager.addLifecycleTask(task);

            var memory = mockMemory();
            when(memory.isCancelled()).thenReturn(true);

            assertThrows(ConversationStopException.class,
                    () -> lifecycleManager.executeLifecycleFromIndex(memory, 0));

            verify(task, never()).execute(any(), any());
        }

        @Test
        @DisplayName("executeLifecycleFromIndex does NOT re-pause on stale PAUSE_CONVERSATION (Blocker #1)")
        @SuppressWarnings("unchecked")
        void fromIndexIgnoresStaleAction() throws Exception {
            var task0 = mockTask("parser", "input");
            var task1 = mockTask("behavior", "behavior_rules");

            lifecycleManager.addLifecycleTask(task0);
            lifecycleManager.addLifecycleTask(task1);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            // Seed stale PAUSE_CONVERSATION — already present BEFORE task1 runs
            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of(IConversation.PAUSE_CONVERSATION));

            // Start from index 1 — action was already there, delta-based check
            // sees it in actionsBefore and does NOT re-pause
            assertDoesNotThrow(
                    () -> lifecycleManager.executeLifecycleFromIndex(memory, 1));

            verify(task0, never()).execute(any(), any());
            verify(task1).execute(eq(memory), any());
        }

        @Test
        @DisplayName("executeLifecycleFromIndex detects newly-added PAUSE_CONVERSATION")
        @SuppressWarnings("unchecked")
        void fromIndexDetectsNewPause() throws Exception {
            var task0 = mockTask("parser", "input");
            var task1 = mockTask("behavior", "behavior_rules");

            lifecycleManager.addLifecycleTask(task0);
            lifecycleManager.addLifecycleTask(task1);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            // Before task1 runs: no actions. Task1 adds PAUSE_CONVERSATION.
            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS))
                    .thenReturn(null) // first call: snapshot before task
                    .thenReturn(actionData); // second call: check after task
            when(actionData.getResult()).thenReturn(List.of(IConversation.PAUSE_CONVERSATION));

            // Start from index 1 — action is new, delta check fires
            var ex = assertThrows(ConversationPauseException.class,
                    () -> lifecycleManager.executeLifecycleFromIndex(memory, 1));

            assertEquals(1, ex.getPausedAbsoluteTaskIndex());
            verify(task0, never()).execute(any(), any());
            verify(task1).execute(eq(memory), any());
        }

        @Test
        @DisplayName("executeLifecycle detects PAUSE_CONVERSATION from fresh task execution")
        @SuppressWarnings("unchecked")
        void executeLifecycleDetectsFreshPause() throws Exception {
            var task0 = mockTask("behavior", "behavior_rules");

            lifecycleManager.addLifecycleTask(task0);

            var memory = mockMemory();
            var currentStep = memory.getCurrentStep();

            // Before task0: no actions. Task0 adds PAUSE_CONVERSATION.
            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS))
                    .thenReturn(null)
                    .thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of(IConversation.PAUSE_CONVERSATION));

            var ex = assertThrows(ConversationPauseException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            assertEquals(0, ex.getPausedAbsoluteTaskIndex());
            verify(task0).execute(eq(memory), any());
        }

        @Test
        @DisplayName("null memory throws IllegalArgumentException")
        void nullMemoryThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> lifecycleManager.executeLifecycleFromIndex(null, 0));
        }
    }
}
