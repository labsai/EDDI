/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.ConversationStopException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("LifecycleManager Tests")
class LifecycleManagerTest {

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

    @Nested
    @DisplayName("addLifecycleTask")
    class AddLifecycleTaskTests {

        @Test
        @DisplayName("null task throws IllegalArgumentException")
        void addNull() {
            assertThrows(IllegalArgumentException.class, () -> lifecycleManager.addLifecycleTask(null));
        }

        @Test
        @DisplayName("valid task is accepted")
        void addValidTask() {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("task1"));
            assertDoesNotThrow(() -> lifecycleManager.addLifecycleTask(task));
        }
    }

    @Nested
    @DisplayName("executeLifecycle")
    class ExecuteLifecycleTests {

        @Test
        @DisplayName("null memory throws IllegalArgumentException")
        void nullMemory() {
            assertThrows(IllegalArgumentException.class,
                    () -> lifecycleManager.executeLifecycle(null, null));
        }

        @Test
        @DisplayName("empty task list completes without exception")
        void emptyTaskList() {
            var memory = mock(IConversationMemory.class);
            assertDoesNotThrow(() -> lifecycleManager.executeLifecycle(memory, null));
        }

        @Test
        @DisplayName("single task executes successfully")
        void singleTask() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("parser"));
            when(task.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap("parser")).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(task).execute(eq(memory), any());
        }

        @Test
        @DisplayName("multiple tasks execute in order")
        void multipleTasks() throws Exception {
            var task1 = mock(ILifecycleTask.class);
            when(task1.getId()).thenReturn(new TaskId("parser"));
            when(task1.getType()).thenReturn("input");

            var task2 = mock(ILifecycleTask.class);
            when(task2.getId()).thenReturn(new TaskId("behavior"));
            when(task2.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task1);
            lifecycleManager.addLifecycleTask(task2);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            var inOrder = inOrder(task1, task2);
            inOrder.verify(task1).execute(eq(memory), any());
            inOrder.verify(task2).execute(eq(memory), any());
        }

        @Test
        @DisplayName("STOP_CONVERSATION action throws ConversationStopException")
        void stopConversation() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("behavior"));
            when(task.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            // Simulate STOP_CONVERSATION action
            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of(IConversation.STOP_CONVERSATION));

            assertThrows(ConversationStopException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));
        }

        @Test
        @DisplayName("task failure propagates LifecycleException")
        void taskFailure() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("LLM failed"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));
        }

        @Test
        @DisplayName("RuntimeException in task is wrapped in LifecycleException")
        void runtimeException() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("broken"));
            when(task.getType()).thenReturn("custom");

            doThrow(new RuntimeException("NPE"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));
        }
    }

    @Nested
    @DisplayName("Selective Execution (lifecycleTaskTypes)")
    class SelectiveExecutionTests {

        @Test
        @DisplayName("filter by type — only matching and subsequent tasks execute")
        void filterByType() throws Exception {
            var parser = mock(ILifecycleTask.class);
            when(parser.getId()).thenReturn(new TaskId("parser"));
            when(parser.getType()).thenReturn("input");

            var behavior = mock(ILifecycleTask.class);
            when(behavior.getId()).thenReturn(new TaskId("behavior"));
            when(behavior.getType()).thenReturn("behavior_rules");

            var output = mock(ILifecycleTask.class);
            when(output.getId()).thenReturn(new TaskId("output"));
            when(output.getType()).thenReturn("output");

            lifecycleManager.addLifecycleTask(parser);
            lifecycleManager.addLifecycleTask(behavior);
            lifecycleManager.addLifecycleTask(output);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            // Execute only from "behavior_rules" onward
            lifecycleManager.executeLifecycle(memory, List.of("behavior_rules"));

            verify(parser, never()).execute(any(), any());
            verify(behavior).execute(eq(memory), any());
            verify(output).execute(eq(memory), any());
        }

        @Test
        @DisplayName("filter with no match — no tasks execute")
        void filterNoMatch() throws Exception {
            var parser = mock(ILifecycleTask.class);
            when(parser.getId()).thenReturn(new TaskId("parser"));
            when(parser.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(parser);

            var memory = mock(IConversationMemory.class);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            lifecycleManager.executeLifecycle(memory, List.of("nonexistent_type"));

            verify(parser, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Strict Write Discipline")
    class StrictWriteDisciplineTests {

        @Test
        @DisplayName("task failure with strict write enabled — uncommits data and injects error digest")
        void taskFailureWithStrictWrite() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("API timeout"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            // Enable strict write discipline
            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            // Pre-execution snapshot
            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.HashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify error digest was injected
            verify(currentStep).addConversationOutputList(eq("taskErrors"), anyList());
            verify(currentStep).storeData(any(Data.class));
        }

        @Test
        @DisplayName("task failure with strict write exclude_all — no digest, but data uncommitted")
        void taskFailureExcludeAll() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("api_task"));
            when(task.getType()).thenReturn("httpcalls");

            doThrow(new LifecycleException("API error"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("exclude_all");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.HashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify failure action was still injected (always happens)
            verify(currentStep, atLeastOnce()).set(eq(ACTIONS), anyList());
        }
    }

    @Nested
    @DisplayName("Event Sink Integration")
    class EventSinkTests {

        @Test
        @DisplayName("event sink receives task_start and task_complete events")
        void eventSinkNotified() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("parser"));
            when(task.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(eventSink).onTaskStart(new TaskId("parser"), "input", 0);
            verify(eventSink).onTaskComplete(eq(new TaskId("parser")), eq("input"), anyLong(), anyMap());
        }
    }

    @Nested
    @DisplayName("Audit Collector Integration")
    class AuditCollectorTests {

        @Test
        @DisplayName("audit collector receives audit entry when set")
        void auditCollectorNotified() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("behavior"));
            when(task.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            var auditCollector = mock(IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(auditCollector).collect(any());
        }

        @Test
        @DisplayName("a failing audit collector does not mask the original task exception")
        void auditFailureDoesNotMaskTaskException() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("behavior"));
            when(task.getType()).thenReturn("behavior_rules");
            doThrow(new RuntimeException("task blew up"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            var auditCollector = mock(IAuditEntryCollector.class);
            doThrow(new RuntimeException("audit ledger unavailable"))
                    .when(auditCollector).collect(any());
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            var thrown = assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            assertEquals("task blew up", thrown.getCause().getMessage(),
                    "the original task failure must be the reported cause, not the audit error");
            assertTrue(Arrays.stream(thrown.getCause().getSuppressed())
                    .anyMatch(s -> "audit ledger unavailable".equals(s.getMessage())),
                    "the audit failure should ride along as suppressed, not be swallowed");
        }
    }

    @Nested
    @DisplayName("Thread Interruption")
    class ThreadInterruptionTests {

        @Test
        @DisplayName("interrupted thread throws LifecycleInterruptedException")
        void interruptedThread() {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("parser"));
            when(task.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            // Interrupt the current thread before execution
            Thread.currentThread().interrupt();

            try {
                assertThrows(LifecycleException.LifecycleInterruptedException.class,
                        () -> lifecycleManager.executeLifecycle(memory, null));
            } finally {
                // Clear interrupt flag so it doesn't affect other tests
                Thread.interrupted();
            }
        }
    }

    @Nested
    @DisplayName("Strict Write Discipline — Edge Cases")
    class StrictWriteDisciplineEdgeCases {

        @Test
        @DisplayName("keep_all mode — isEffectivelyEnabled() returns false, no SWD applied")
        void keepAllMode() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("LLM error"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            // keep_all mode makes isEffectivelyEnabled() return false
            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("keep_all");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // keep_all → isEffectivelyEnabled() is false → no strict write applied
            verify(currentStep, never()).addConversationOutputList(anyString(), anyList());
        }

        @Test
        @DisplayName("null memoryPolicy — strict write discipline not applied")
        void nullMemoryPolicy() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("failing_task"));
            when(task.getType()).thenReturn("custom");

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getMemoryPolicy()).thenReturn(null);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // No strict write discipline applied — no uncommit, no digest
            verify(currentStep, never()).addConversationOutputList(anyString(), anyList());
        }

        @Test
        @DisplayName("disabled strict write — no uncommit even on failure")
        void disabledStrictWrite() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("failing_task"));
            when(task.getType()).thenReturn("custom");

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(false);
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Strict write is disabled — no error digest
            verify(currentStep, never()).addConversationOutputList(anyString(), anyList());
        }

        @Test
        @DisplayName("failure after first task — second task does NOT execute")
        void failureStopsPipeline() throws Exception {
            var task1 = mock(ILifecycleTask.class);
            when(task1.getId()).thenReturn(new TaskId("parser"));
            when(task1.getType()).thenReturn("input");

            var task2 = mock(ILifecycleTask.class);
            when(task2.getId()).thenReturn(new TaskId("behavior"));
            when(task2.getType()).thenReturn("behavior_rules");

            doThrow(new LifecycleException("parse error"))
                    .when(task1).execute(any(), any());

            lifecycleManager.addLifecycleTask(task1);
            lifecycleManager.addLifecycleTask(task2);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            verify(task1).execute(any(), any());
            verify(task2, never()).execute(any(), any());
        }
    }

    @Nested
    @DisplayName("Selective Execution — Additional")
    class SelectiveExecutionAdditionalTests {

        @Test
        @DisplayName("empty lifecycleTaskTypes — all tasks execute (same as null)")
        void emptyListExecutesAll() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("parser"));
            when(task.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, List.of());

            verify(task).execute(eq(memory), any());
        }
    }

    @Nested
    @DisplayName("Null TaskId Guard")
    class NullTaskIdTests {

        @Test
        @DisplayName("task with null id throws LifecycleException")
        void nullTaskIdThrows() {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(null);

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var ex = assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));
            assertTrue(ex.getMessage().contains("null TaskId"));
        }
    }

    @Nested
    @DisplayName("handleTaskFailure — Output Rollback")
    class OutputRollbackTests {

        @Test
        @DisplayName("output keys added by failing task are removed")
        void outputKeysRolledBack() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            var conversationOutput = new ai.labs.eddi.engine.memory.model.ConversationOutput();
            // Pre-existing key that should survive rollback
            conversationOutput.put("existingKey", "existingValue");

            var currentStep = mock(ConversationStep.class);
            // Snapshot before: only "existingKey"
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>(java.util.Set.of("existingKey")));
            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(conversationOutput);

            // Simulate task adding a new key before throwing
            doAnswer(invocation -> {
                conversationOutput.put("dirtyKey", "dirtyValue");
                throw new LifecycleException("LLM timeout");
            }).when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("exclude_all");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // "dirtyKey" should have been removed, "existingKey" preserved
            assertTrue(conversationOutput.containsKey("existingKey"),
                    "Pre-existing output key should survive rollback");
            assertFalse(conversationOutput.containsKey("dirtyKey"),
                    "Output key added by failed task should be removed");
        }
    }

    @Nested
    @DisplayName("handleTaskFailure — Data Uncommit with Overwritten Entries")
    class DataUncommitOverwriteTests {

        @Test
        @DisplayName("overwritten data entries are marked uncommitted")
        void overwrittenEntriesUncommitted() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            // Create the "before" IData reference
            IData<?> originalData = mock(IData.class);
            when(originalData.getKey()).thenReturn("someKey");

            // Create the "after" IData reference (different object = overwritten)
            IData<?> overwrittenData = mock(IData.class);
            when(overwrittenData.getKey()).thenReturn("someKey");

            // Snapshot before: "someKey" -> originalData
            var beforeSnapshot = new HashMap<String, IData<?>>();
            beforeSnapshot.put("someKey", originalData);

            var currentStep = mock(ConversationStep.class);
            when(currentStep.snapshotDataIdentities()).thenReturn(beforeSnapshot);
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            // After failure: step contains the overwritten entry
            when(currentStep.getAllElements()).thenReturn(new java.util.LinkedList<>(List.of(overwrittenData)));
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // overwrittenData is a different reference than originalData → uncommitted
            verify(overwrittenData).setCommitted(false);
        }
    }

    @Nested
    @DisplayName("summarizeException — Edge Cases (via digest mode)")
    class SummarizeExceptionEdgeCaseTests {

        @Test
        @DisplayName("null exception message uses class simple name in digest")
        void nullExceptionMessage() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException(null))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify digest was injected (uses class name since message is null)
            verify(currentStep).addConversationOutputList(eq("taskErrors"), anyList());
            verify(currentStep).storeData(argThat(data -> data instanceof Data<?> d && d.getKey().equals("taskError:eddi://llm_task")
                    && d.getResult().toString().contains("LifecycleException")));
        }

        @Test
        @DisplayName("URL in exception message is stripped to [url]")
        void urlStripping() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("api_task"));
            when(task.getType()).thenReturn("httpcalls");

            doThrow(new LifecycleException("Connection to https://api.example.com/v1/chat refused"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify digest was stored with URL replaced by [url]
            verify(currentStep).storeData(argThat(data -> data instanceof Data<?> d && d.getKey().equals("taskError:eddi://api_task")
                    && d.getResult().toString().contains("[url]")
                    && !d.getResult().toString().contains("https://api.example.com")));
        }

        @Test
        @DisplayName("long exception message is truncated to 200 chars + ellipsis")
        void truncation() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("long_error"));
            when(task.getType()).thenReturn("custom");

            String longMessage = "A".repeat(300);
            doThrow(new LifecycleException(longMessage))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify digest was stored with truncated message ending in "..."
            verify(currentStep).storeData(argThat(data -> data instanceof Data<?> d && d.getKey().equals("taskError:eddi://long_error")
                    && d.getResult().toString().endsWith("...")
                    && d.getResult().toString().length() < 300));
        }
    }

    @Nested
    @DisplayName("resolveOnFailureMode — Unknown Mode")
    class UnknownOnFailureModeTests {

        @Test
        @DisplayName("unknown onFailure mode defaults to digest behavior")
        void unknownModeDefaultsToDigest() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("bogus_mode"); // unknown mode
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Unknown mode defaults to "digest" → error digest IS injected
            verify(currentStep).addConversationOutputList(eq("taskErrors"), anyList());
            verify(currentStep).storeData(any(Data.class));
        }
    }

    @Nested
    @DisplayName("injectFailureAction — Pre-existing Actions")
    class InjectFailureActionTests {

        @Test
        @DisplayName("failure action list includes pre-existing actions + task_failed action")
        void preExistingActionsPlusFailure() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            // Set up pre-existing actions before the task runs
            var preActionData = mock(IData.class);
            when(preActionData.getResult()).thenReturn(List.of("greet", "search"));
            when(currentStep.getLatestData(ACTIONS)).thenReturn(preActionData);

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // Verify the rebuilt action list contains pre-failure actions + failure action
            verify(currentStep).set(eq(ACTIONS), argThat(actions -> actions instanceof List<?> list
                    && list.size() == 3
                    && list.get(0).equals("greet")
                    && list.get(1).equals("search")
                    && list.get(2).equals("task_failed_llm_task")));
        }
    }

    @Nested
    @DisplayName("buildTaskSummary — Actions, ToolTrace, Confidence")
    class BuildTaskSummaryTests {

        @Test
        @DisplayName("task summary includes actions when present")
        void summaryWithActions() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("behavior"));
            when(task.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            // Set up actions data
            var actionData = mock(IData.class);
            when(actionData.getResult()).thenReturn(List.of("greet", "search"));
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(eventSink).onTaskComplete(eq(new TaskId("behavior")), eq("behavior_rules"), anyLong(),
                    argThat(summary -> summary.containsKey("actions")));
        }

        /**
         * Runs one lifecycle turn for a single task of the given type over the given
         * step elements, and returns the summary map handed to {@code onTaskComplete}.
         * <p>
         * Deliberately stubs only {@code getAllElements()} — never
         * {@code getLatestData(...)}. The predecessor of these tests hand-stubbed the
         * exact key the reader computed, which made it assert the reader against its
         * own stub instead of against the key LlmTask actually writes.
         */
        private Map<String, Object> runAndCaptureSummary(String taskType, List<IData<?>> stepElements) throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("ai.labs.llm"));
            when(task.getType()).thenReturn(taskType);

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(currentStep.getAllElements()).thenReturn(stepElements);

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventSink).onTaskComplete(eq(new TaskId("ai.labs.llm")), eq(taskType), anyLong(),
                    summaryCaptor.capture());
            return summaryCaptor.getValue();
        }

        @Test
        @DisplayName("langchain task: trace key written by LlmTask reaches the summary")
        void summaryIncludesToolTraceForLangchainTask() throws Exception {
            // Exactly the key LlmTask writes for a task with type=openai, id=taskA —
            // see LlmTaskCoverageTest.resume_nonEmptyTrace_stored (writer half).
            var call = Map.<String, Object>of("type", "tool_call", "tool", "weather");
            var result = Map.<String, Object>of("type", "tool_result", "tool", "weather");
            var summary = runAndCaptureSummary("langchain",
                    List.<IData<?>>of(new Data<>("langchain:trace:openai:taskA", List.of(call, result))));

            assertEquals(List.of(call, result), summary.get("toolTrace"));
        }

        @Test
        @DisplayName("langchain task: multiple trace keys are aggregated in write order")
        void summaryAggregatesMultipleToolTraceKeys() throws Exception {
            // LlmTask writes ONE trace key per LLM config task, so a reader that takes
            // only the latest match (getLatestData) silently drops all but the last.
            var first = Map.<String, Object>of("type", "tool_call", "tool", "weather");
            var second = Map.<String, Object>of("type", "tool_call", "tool", "calculator");
            var summary = runAndCaptureSummary("langchain", List.<IData<?>>of(
                    new Data<>("langchain:trace:openai:taskA", List.of(first)),
                    new Data<>("langchain:trace:anthropic:taskB", List.of(second))));

            assertEquals(List.of(first, second), summary.get("toolTrace"));
        }

        @Test
        @DisplayName("non-langchain task: trace lingering in the step is NOT reported")
        void summaryOmitsToolTraceForNonLangchainTask() throws Exception {
            // Step data survives across tasks, so without the task-type gate every task
            // running after the LLM task would report the LLM's trace as its own.
            var summary = runAndCaptureSummary("behavior_rules", List.<IData<?>>of(
                    new Data<>("langchain:trace:openai:taskA",
                            List.of(Map.<String, Object>of("type", "tool_call", "tool", "weather")))));

            assertFalse(summary.containsKey("toolTrace"));
        }

        @Test
        @DisplayName("langchain task: no trace keys → no toolTrace field")
        void summaryOmitsToolTraceWhenNoTraceKeys() throws Exception {
            var summary = runAndCaptureSummary("langchain", List.<IData<?>>of(
                    new Data<>("input", "hello"),
                    new Data<>("actions", List.of("greet"))));

            assertFalse(summary.containsKey("toolTrace"));
        }

        @Test
        @DisplayName("langchain task: langchain:cascade:trace: is not swept into toolTrace")
        void summaryIgnoresCascadeTraceKey() throws Exception {
            var summary = runAndCaptureSummary("langchain", List.<IData<?>>of(
                    new Data<>("langchain:cascade:trace:taskA",
                            List.of(Map.<String, Object>of("step", 0, "model", "gpt-4o-mini")))));

            assertFalse(summary.containsKey("toolTrace"));
        }

        @Test
        @DisplayName("langchain task: non-List trace result is ignored, not cast")
        void summaryIgnoresNonListTraceResult() throws Exception {
            var summary = assertDoesNotThrow(() -> runAndCaptureSummary("langchain",
                    List.<IData<?>>of(new Data<>("langchain:trace:openai:taskA", "not-a-list"))));

            assertFalse(summary.containsKey("toolTrace"));
        }

        @Test
        @DisplayName("task summary includes confidence when present")
        void summaryWithConfidence() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm"));
            when(task.getType()).thenReturn("langchain");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var eventSink = mock(ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            // Set up confidence data
            IData<Double> confidenceData = mock(IData.class);
            when(confidenceData.getResult()).thenReturn(0.95);
            doReturn(confidenceData).when(currentStep).getLatestData("audit:confidence");

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(eventSink).onTaskComplete(eq(new TaskId("llm")), eq("langchain"), anyLong(),
                    argThat(summary -> summary.containsKey("confidence")));
        }
    }

    @Nested
    @DisplayName("buildAuditEntry — Input/Output/LLM Details")
    class BuildAuditEntryDetailTests {

        @Test
        @DisplayName("audit entry populates input and output data")
        void auditEntryWithInputOutput() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("behavior"));
            when(task.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            // Set up input data
            IData<String> inputData = mock(IData.class);
            when(inputData.getResult()).thenReturn("Hello!");
            doReturn(inputData).when(currentStep).getLatestData("input");

            // Set up output data
            IData<List<String>> outputData = mock(IData.class);
            when(outputData.getResult()).thenReturn(List.of("Hi there!"));
            doReturn(outputData).when(currentStep).getLatestData("output");

            var auditCollector = mock(IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(auditCollector).collect(argThat(entry -> {
                assertNotNull(entry.input());
                assertNotNull(entry.output());
                return true;
            }));
        }

        @Test
        @DisplayName("audit entry populates LLM details when compiled_prompt present")
        void auditEntryWithLlmDetails() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            // Set up LLM detail data
            IData<String> promptData = mock(IData.class);
            when(promptData.getResult()).thenReturn("You are a helpful assistant");
            doReturn(promptData).when(currentStep).getLatestData("audit:compiled_prompt");

            IData<String> responseData = mock(IData.class);
            when(responseData.getResult()).thenReturn("Hello, how can I help?");
            doReturn(responseData).when(currentStep).getLatestData("audit:model_response");

            IData<String> modelData = mock(IData.class);
            when(modelData.getResult()).thenReturn("gpt-4");
            doReturn(modelData).when(currentStep).getLatestData("audit:model_name");

            IData<java.util.Map<String, Object>> tokenData = mock(IData.class);
            when(tokenData.getResult()).thenReturn(java.util.Map.of("input", 10, "output", 20));
            doReturn(tokenData).when(currentStep).getLatestData("audit:token_usage");

            var auditCollector = mock(IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(auditCollector).collect(argThat(entry -> {
                assertNotNull(entry.llmDetail());
                assertTrue(entry.llmDetail().containsKey("compiledPrompt"));
                assertTrue(entry.llmDetail().containsKey("modelResponse"));
                assertTrue(entry.llmDetail().containsKey("modelName"));
                // containsKey alone passed while the entry carried null: assert the value.
                assertEquals(java.util.Map.of("input", 10, "output", 20), entry.llmDetail().get("tokenUsage"));
                return true;
            }));
        }

        @Test
        @DisplayName("audit entry omits tokenUsage when no LLM call reported any")
        void auditEntryOmitsTokenUsageWhenAbsent() throws Exception {
            var auditCollector = auditRun(currentStep -> {
                IData<String> promptData = mock(IData.class);
                when(promptData.getResult()).thenReturn("You are a helpful assistant");
                doReturn(promptData).when(currentStep).getLatestData("audit:compiled_prompt");
                // Key present but empty-resulted — the loose "data != null" guard would
                // put a null tokenUsage into llmDetail and ship it to the ledger.
                IData<java.util.Map<String, Object>> tokenData = mock(IData.class);
                when(tokenData.getResult()).thenReturn(null);
                doReturn(tokenData).when(currentStep).getLatestData("audit:token_usage");
            });

            verify(auditCollector).collect(argThat(entry -> {
                assertNotNull(entry.llmDetail());
                assertFalse(entry.llmDetail().containsKey("tokenUsage"));
                return true;
            }));
        }

        @Test
        @DisplayName("audit entry carries cascadeModel and a Double confidence in llmDetail")
        void auditEntryLlmDetailCarriesConfidenceAndCascadeModel() throws Exception {
            var auditCollector = auditRun(currentStep -> {
                IData<String> promptData = mock(IData.class);
                when(promptData.getResult()).thenReturn("prompt");
                doReturn(promptData).when(currentStep).getLatestData("audit:compiled_prompt");

                IData<String> cascadeModelData = mock(IData.class);
                when(cascadeModelData.getResult()).thenReturn("openai/gpt-4o (step 1)");
                doReturn(cascadeModelData).when(currentStep).getLatestData("audit:cascade_model");

                IData<Double> confidenceData = mock(IData.class);
                when(confidenceData.getResult()).thenReturn(0.87);
                doReturn(confidenceData).when(currentStep).getLatestData("audit:confidence");
            });

            verify(auditCollector).collect(argThat(entry -> {
                assertEquals("openai/gpt-4o (step 1)", entry.llmDetail().get("cascadeModel"));
                assertEquals(0.87, entry.llmDetail().get("confidence"));
                return true;
            }));
        }

        /**
         * {@code toolCalls} was passed as a literal {@code null} to every audit entry
         * the engine ever produced, with a comment claiming LlmTask set it in memory.
         */
        @Test
        @DisplayName("audit entry populates toolCalls from memory")
        void auditEntryPopulatesToolCallsFromMemory() throws Exception {
            var calls = List.of(java.util.Map.<String, Object>of("tool", "calculator", "llmTaskId", "taskA"));
            var auditCollector = auditRun(currentStep -> {
                IData<java.util.Map<String, Object>> toolCallData = mock(IData.class);
                when(toolCallData.getResult()).thenReturn(java.util.Map.of("calls", calls));
                doReturn(toolCallData).when(currentStep).getLatestData("audit:tool_calls");
            });

            verify(auditCollector).collect(argThat(entry -> {
                assertNotNull(entry.toolCalls(), "toolCalls must no longer be hard-coded null");
                assertEquals(calls, entry.toolCalls().get("calls"));
                return true;
            }));
        }

        @Test
        @DisplayName("audit entry leaves toolCalls null when the key is absent or empty")
        void auditEntryToolCallsNullWhenAbsentOrEmpty() throws Exception {
            var absent = auditRun(currentStep -> {
            });
            verify(absent).collect(argThat(entry -> {
                assertNull(entry.toolCalls());
                return true;
            }));

            var empty = auditRun(currentStep -> {
                IData<java.util.Map<String, Object>> toolCallData = mock(IData.class);
                when(toolCallData.getResult()).thenReturn(java.util.Map.of());
                doReturn(toolCallData).when(currentStep).getLatestData("audit:tool_calls");
            });
            verify(empty).collect(argThat(entry -> {
                assertNull(entry.toolCalls());
                return true;
            }));
        }

        /**
         * {@code cost} was a literal {@code 0.0} with a comment claiming a
         * ToolCostTracker integration that never existed.
         */
        @Test
        @DisplayName("audit entry populates cost from memory, and defaults to 0.0 when absent")
        void auditEntryPopulatesCostFromMemory() throws Exception {
            var priced = auditRun(currentStep -> {
                IData<Double> costData = mock(IData.class);
                when(costData.getResult()).thenReturn(0.0042);
                doReturn(costData).when(currentStep).getLatestData("audit:cost");
            });
            verify(priced).collect(argThat(entry -> {
                assertEquals(0.0042, entry.cost(), 1e-9, "cost must no longer be hard-coded 0.0");
                return true;
            }));

            var free = auditRun(currentStep -> {
            });
            verify(free).collect(argThat(entry -> {
                assertEquals(0.0, entry.cost(), 1e-9);
                return true;
            }));
        }

        /**
         * Runs one lifecycle turn with an audit collector attached, letting the caller
         * stub whatever {@code audit:*} data the case needs on the current step.
         */
        private IAuditEntryCollector auditRun(java.util.function.Consumer<IConversationMemory.IWritableConversationStep> stubStep)
                throws Exception {
            var manager = new LifecycleManager(componentCache, workflowId);
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");
            manager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            stubStep.accept(currentStep);

            var auditCollector = mock(IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);
            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            manager.executeLifecycle(memory, null);
            return auditCollector;
        }
    }

    @Nested
    @DisplayName("resolveOnFailureMode — Null SWD")
    class ResolveOnFailureModeNullSwdTests {

        @Test
        @DisplayName("null onFailure value in SWD — defaults to digest")
        void nullOnFailure() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("llm_task"));
            when(task.getType()).thenReturn("langchain");

            doThrow(new LifecycleException("fail"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(ConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure(null); // null onFailure
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            when(currentStep.getAllElements()).thenReturn(new LinkedList<>());
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // null onFailure defaults to "digest" → error digest IS injected
            verify(currentStep).addConversationOutputList(eq("taskErrors"), anyList());
        }
    }

    @Nested
    @DisplayName("handleTaskFailure — New Key Uncommit")
    class NewKeyUncommitTests {

        @Test
        @DisplayName("brand new key added by failed task is marked uncommitted")
        void newKeyUncommitted() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn(new TaskId("api_task"));
            when(task.getType()).thenReturn("httpcalls");

            // New data entry added by the failed task
            IData<?> newData = mock(IData.class);
            when(newData.getKey()).thenReturn("newKey");

            var currentStep = mock(ConversationStep.class);
            // Before snapshot: empty (no pre-existing keys)
            when(currentStep.snapshotDataIdentities()).thenReturn(new HashMap<>());
            when(currentStep.snapshotOutputKeys()).thenReturn(new java.util.LinkedHashSet<>());
            // After failure: step contains the new entry
            when(currentStep.getAllElements()).thenReturn(new java.util.LinkedList<>(List.of(newData)));
            when(currentStep.getConversationOutput()).thenReturn(new ai.labs.eddi.engine.memory.model.ConversationOutput());

            doThrow(new LifecycleException("timeout"))
                    .when(task).execute(any(), any());

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var memoryPolicy = new AgentConfiguration.MemoryPolicy();
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("exclude_all");
            memoryPolicy.setStrictWriteDiscipline(swd);
            when(memory.getMemoryPolicy()).thenReturn(memoryPolicy);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            assertThrows(LifecycleException.class,
                    () -> lifecycleManager.executeLifecycle(memory, null));

            // New key not in before snapshot → marked uncommitted
            verify(newData).setCommitted(false);
        }
    }
}
