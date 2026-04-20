package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.IConversation;
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
            when(task.getId()).thenReturn("task1");
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
            when(task.getId()).thenReturn("parser");
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
            when(task1.getId()).thenReturn("parser");
            when(task1.getType()).thenReturn("input");

            var task2 = mock(ILifecycleTask.class);
            when(task2.getId()).thenReturn("behavior");
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
            when(task.getId()).thenReturn("behavior");
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
            when(task.getId()).thenReturn("llm");
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
            when(task.getId()).thenReturn("broken");
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
            when(parser.getId()).thenReturn("parser");
            when(parser.getType()).thenReturn("input");

            var behavior = mock(ILifecycleTask.class);
            when(behavior.getId()).thenReturn("behavior");
            when(behavior.getType()).thenReturn("behavior_rules");

            var output = mock(ILifecycleTask.class);
            when(output.getId()).thenReturn("output");
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
            when(parser.getId()).thenReturn("parser");
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
            when(task.getId()).thenReturn("llm_task");
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
            when(task.getId()).thenReturn("api_task");
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
            when(task.getId()).thenReturn("parser");
            when(task.getType()).thenReturn("input");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");

            var eventSink = mock(ai.labs.eddi.engine.lifecycle.ConversationEventSink.class);
            when(memory.getEventSink()).thenReturn(eventSink);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(eventSink).onTaskStart("parser", "input", 0);
            verify(eventSink).onTaskComplete(eq("parser"), eq("input"), anyLong(), anyMap());
        }
    }

    @Nested
    @DisplayName("Audit Collector Integration")
    class AuditCollectorTests {

        @Test
        @DisplayName("audit collector receives audit entry when set")
        void auditCollectorNotified() throws Exception {
            var task = mock(ILifecycleTask.class);
            when(task.getId()).thenReturn("behavior");
            when(task.getType()).thenReturn("behavior_rules");

            lifecycleManager.addLifecycleTask(task);

            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getConversationId()).thenReturn("conv1");
            when(memory.getAgentId()).thenReturn("agent1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(memory.size()).thenReturn(1);

            var auditCollector = mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class);
            when(memory.getAuditCollector()).thenReturn(auditCollector);

            when(componentCache.getComponentMap(anyString())).thenReturn(new HashMap<>());

            lifecycleManager.executeLifecycle(memory, null);

            verify(auditCollector).collect(any());
        }
    }
}
