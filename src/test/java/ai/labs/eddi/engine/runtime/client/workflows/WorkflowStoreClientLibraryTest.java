/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.workflows;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.service.IWorkflowStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.inject.Provider;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component-cache keying in {@link WorkflowStoreClientLibrary}.
 *
 * <p>
 * {@code LifecycleManager} resolves each task's component by the task's
 * position in ITS OWN task list. {@code WorkflowStoreClientLibrary} writes
 * those cache entries while walking the workflow STEP list — and a step whose
 * type URI does not use the {@code eddi} scheme is skipped without producing a
 * task. Keying the write off the raw step index therefore shifts every
 * component after such a step one slot past the task that needs it, and those
 * tasks run with {@code component == null} (i.e. silently no-op with their
 * configuration missing).
 */
class WorkflowStoreClientLibraryTest {

    private static final String WORKFLOW_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final int WORKFLOW_VERSION = 1;

    private static final String PARSER_COMPONENT = "parser-config";
    private static final String OUTPUT_COMPONENT = "output-config";
    private static final String BEHAVIOR_COMPONENT = "behavior-config";

    private IWorkflowStoreService workflowStoreService;
    private RecordingComponentCache componentCache;
    private Map<String, Provider<ILifecycleTask>> extensions;

    private ILifecycleTask parserTask;
    private ILifecycleTask behaviorTask;
    private ILifecycleTask outputTask;

    @BeforeEach
    void setUp() throws Exception {
        workflowStoreService = mock(IWorkflowStoreService.class);
        componentCache = new RecordingComponentCache();
        extensions = new HashMap<>();

        parserTask = task("ai.labs.parser", "expressions", PARSER_COMPONENT);
        behaviorTask = task("ai.labs.behavior", "behavior_rules", BEHAVIOR_COMPONENT);
        outputTask = task("ai.labs.output", "output", OUTPUT_COMPONENT);

        extensions.put("ai.labs.parser", () -> parserTask);
        extensions.put("ai.labs.behavior", () -> behaviorTask);
        extensions.put("ai.labs.output", () -> outputTask);

        var descriptor = new DocumentDescriptor();
        descriptor.setName("workflow");
        descriptor.setDescription("test workflow");
        descriptor.setResource(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=" + WORKFLOW_VERSION));
        when(workflowStoreService.getWorkflowDocumentDescriptor(WORKFLOW_ID, WORKFLOW_VERSION)).thenReturn(descriptor);
    }

    private static ILifecycleTask task(String id, String type, Object component) throws Exception {
        var task = mock(ILifecycleTask.class);
        when(task.getId()).thenReturn(new TaskId(id));
        when(task.getType()).thenReturn(type);
        when(task.configure(anyMap(), anyMap())).thenReturn(component);
        return task;
    }

    private static WorkflowConfiguration workflowOf(String... stepTypes) {
        var configuration = new WorkflowConfiguration();
        List<WorkflowConfiguration.WorkflowStep> steps = new LinkedList<>();
        for (String stepType : stepTypes) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create(stepType));
            steps.add(step);
        }
        configuration.setWorkflowSteps(steps);
        return configuration;
    }

    private WorkflowStoreClientLibrary libraryFor(WorkflowConfiguration configuration) throws Exception {
        when(workflowStoreService.getKnowledgeWorkflow(WORKFLOW_ID, WORKFLOW_VERSION)).thenReturn(configuration);
        return new WorkflowStoreClientLibrary(workflowStoreService, componentCache, extensions);
    }

    private static IConversationMemory memory() {
        var memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IConversationMemory.IWritableConversationStep.class));
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");
        return memory;
    }

    @Test
    @DisplayName("all-eddi workflow: components are cached at 0,1,2 (guards the normal path)")
    void everyStepProducesATaskSoKeysAreTheStepIndices() throws Exception {
        var library = libraryFor(workflowOf(
                "eddi://ai.labs.parser",
                "eddi://ai.labs.behavior",
                "eddi://ai.labs.output"));

        library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        assertEquals(List.of(WORKFLOW_ID + ":1:0", WORKFLOW_ID + ":1:1", WORKFLOW_ID + ":1:2"),
                componentCache.keysInPutOrder);
    }

    @Test
    @DisplayName("a non-eddi step does not shift the component keys of the steps after it")
    void nonEddiStepDoesNotShiftLaterComponentKeys() throws Exception {
        var library = libraryFor(workflowOf(
                "eddi://ai.labs.parser",
                "https://third.party/some.extension", // skipped — produces no task
                "eddi://ai.labs.output"));

        library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        // The output task is task #1 in the lifecycle manager, so its component must
        // be cached under index 1. Keying off the workflow step index wrote ":1:2".
        assertEquals(List.of(WORKFLOW_ID + ":1:0", WORKFLOW_ID + ":1:1"), componentCache.keysInPutOrder);
    }

    @Test
    @DisplayName("after a non-eddi step, every remaining task still receives its component at execution time")
    void tasksAfterASkippedStepStillResolveTheirComponent() throws Exception {
        var library = libraryFor(workflowOf(
                "eddi://ai.labs.parser",
                "https://third.party/some.extension",
                "eddi://ai.labs.output"));

        var workflow = library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        var memory = memory();
        workflow.getLifecycleManager().executeLifecycle(memory, null);

        verify(parserTask).execute(memory, PARSER_COMPONENT);
        // Before the fix this was execute(memory, null): LifecycleManager looked the
        // output task up at its task-list position (1) while the component had been
        // written at the workflow-step position (2).
        verify(outputTask).execute(memory, OUTPUT_COMPONENT);
    }

    @Test
    @DisplayName("a non-eddi step is skipped, not rejected — a stored config containing one still loads")
    void nonEddiStepDoesNotFailWorkflowCreation() throws Exception {
        var library = libraryFor(workflowOf(
                "https://third.party/some.extension",
                "eddi://ai.labs.parser"));

        var workflow = library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        assertEquals(WORKFLOW_ID, workflow.getWorkflowId());
        // The leading skip must not push the parser's component to index 1.
        assertEquals(List.of(WORKFLOW_ID + ":1:0"), componentCache.keysInPutOrder);

        var memory = memory();
        workflow.getLifecycleManager().executeLifecycle(memory, null);
        verify(parserTask).execute(memory, PARSER_COMPONENT);
    }

    /**
     * Minimal in-memory {@link IComponentCache} that also records the keys written,
     * in order, so the tests can assert on the exact cache layout rather than only
     * on what happens to resolve.
     */
    private static final class RecordingComponentCache implements IComponentCache {
        private final Map<String, Map<String, Object>> componentsByType = new HashMap<>();
        private final List<String> keysInPutOrder = new ArrayList<>();

        @Override
        public Map<String, Object> getComponentMap(String type) {
            return componentsByType.computeIfAbsent(type, t -> new HashMap<>());
        }

        @Override
        public void put(String type, String key, Object component) {
            keysInPutOrder.add(key);
            getComponentMap(type).put(key, component);
        }
    }
}
