/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.client.workflows.WorkflowStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.IWorkflowStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.inject.Provider;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deploying a workflow that contains a {@code eddi://ai.labs.rag} step.
 *
 * <p>
 * Documents the failure this task was added to fix: {@code
 * WorkflowStoreClientLibrary} throws {@code UnrecognizedExtensionException} for
 * any step type absent from the lifecycle-extension map, so before
 * {@code RagModule} existed a RAG workflow could not be deployed — the error
 * surfaced at agent deployment, far from the RAG config the operator had just
 * saved.
 *
 * <p>
 * The negative case here pins that mechanism; whether the real application
 * registers the step is asserted against live CDI wiring in
 * {@code RagWorkflowExtensionRegistrationTest}.
 */
class RagWorkflowDeploymentTest {

    private static final String WORKFLOW_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final int WORKFLOW_VERSION = 1;
    private static final String KB_URI = "eddi://ai.labs.rag/ragstore/rags/6b9c2d3e4f5a6b7c8d9e0f1a?version=1";

    private IWorkflowStoreService workflowStoreService;
    private Map<String, Provider<ILifecycleTask>> extensions;
    private IResourceClientLibrary resourceClientLibrary;
    private RagConfiguration knowledgeBase;

    @BeforeEach
    void setUp() throws Exception {
        workflowStoreService = mock(IWorkflowStoreService.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        extensions = new HashMap<>();

        knowledgeBase = new RagConfiguration();
        knowledgeBase.setName("product-docs");
        when(resourceClientLibrary.getResource(eq(URI.create(KB_URI)), eq(RagConfiguration.class)))
                .thenReturn(knowledgeBase);

        var descriptor = new DocumentDescriptor();
        descriptor.setName("workflow");
        descriptor.setResource(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=" + WORKFLOW_VERSION));
        when(workflowStoreService.getWorkflowDocumentDescriptor(WORKFLOW_ID, WORKFLOW_VERSION)).thenReturn(descriptor);
    }

    private void registerRagTask() {
        var ragTask = new RagTask(resourceClientLibrary);
        extensions.put(RagTask.ID, () -> ragTask);
    }

    private static WorkflowConfiguration ragWorkflow() {
        var configuration = new WorkflowConfiguration();
        List<WorkflowConfiguration.WorkflowStep> steps = new LinkedList<>();

        var ragStep = new WorkflowConfiguration.WorkflowStep();
        ragStep.setType(URI.create("eddi://ai.labs.rag"));
        ragStep.setConfig(Map.of("uri", KB_URI));
        steps.add(ragStep);

        configuration.setWorkflowSteps(steps);
        return configuration;
    }

    private WorkflowStoreClientLibrary libraryFor(WorkflowConfiguration configuration, IComponentCache cache)
            throws Exception {
        when(workflowStoreService.getKnowledgeWorkflow(WORKFLOW_ID, WORKFLOW_VERSION)).thenReturn(configuration);
        return new WorkflowStoreClientLibrary(workflowStoreService, cache, extensions);
    }

    @Test
    @DisplayName("a workflow with a RAG step deploys once ai.labs.rag is registered")
    void ragWorkflowDeploys() throws Exception {
        registerRagTask();
        var cache = new RecordingComponentCache();

        var workflow = libraryFor(ragWorkflow(), cache).getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        assertNotNull(workflow);
        assertEquals(WORKFLOW_ID, workflow.getWorkflowId());
        // The resolved knowledge base is cached as the step's component, keyed by
        // the task id — proof that configure() ran and resolved the binding.
        assertSame(knowledgeBase, cache.getComponentMap(RagTask.ID).get(WORKFLOW_ID + ":1:0"));
    }

    @Test
    @DisplayName("without the registration the whole workflow fails to deploy — the bug this fixes")
    void ragWorkflowIsRejectedWhenUnregistered() throws Exception {
        // deliberately NOT calling registerRagTask()
        var library = libraryFor(ragWorkflow(), new RecordingComponentCache());

        var e = assertThrows(ServiceException.class,
                () -> library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION));

        assertTrue(String.valueOf(e.getCause()).contains("ai.labs.rag"),
                "the failure should name the unregistered extension, was: " + e.getCause());
    }

    @Test
    @DisplayName("a broken knowledge-base binding fails at deploy time, not silently at conversation time")
    void unresolvableKnowledgeBaseFailsDeployment() throws Exception {
        registerRagTask();
        when(resourceClientLibrary.getResource(eq(URI.create(KB_URI)), eq(RagConfiguration.class)))
                .thenThrow(new ServiceException("knowledge base not found"));
        var library = libraryFor(ragWorkflow(), new RecordingComponentCache());

        var e = assertThrows(ServiceException.class,
                () -> library.getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION));

        assertTrue(String.valueOf(e.getMessage()).contains("configuring")
                || String.valueOf(e.getCause()).contains("knowledge base"),
                "deployment should surface the unresolvable knowledge base, was: " + e);
    }

    @Test
    @DisplayName("executing the RAG step is inert — it must not touch conversation memory")
    void ragStepDoesNothingAtRuntime() throws Exception {
        registerRagTask();
        var workflow = libraryFor(ragWorkflow(), new RecordingComponentCache())
                .getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        var memory = mock(IConversationMemory.class);
        var step = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        when(memory.getConversationId()).thenReturn("conv1");
        when(memory.getAgentId()).thenReturn("agent1");

        workflow.getLifecycleManager().executeLifecycle(memory, null);

        // Retrieval belongs to RagContextProvider inside the LLM task. The step is
        // asserted rather than the memory because LifecycleManager itself reads the
        // current step for tracing; what matters is that the RAG step contributes
        // nothing to it. If someone later moves retrieval into RagTask.execute, this
        // fails and they have to read the Javadoc explaining why it lives elsewhere.
        verify(step, never()).storeData(any());
        verify(step, never()).addConversationOutputString(any(), any());
    }

    /**
     * Minimal in-memory {@link IComponentCache} that records write order, mirroring
     * the helper in {@code WorkflowStoreClientLibraryTest}.
     */
    private static final class RecordingComponentCache implements IComponentCache {
        private final Map<String, Map<String, Object>> componentsByType = new HashMap<>();

        @Override
        public Map<String, Object> getComponentMap(String type) {
            return componentsByType.computeIfAbsent(type, t -> new HashMap<>());
        }

        @Override
        public void put(String type, String key, Object component) {
            getComponentMap(type).put(key, component);
        }
    }

    @Test
    @DisplayName("TaskId round-trips the documented step URI")
    void taskIdRoundTripsStepUri() {
        assertEquals("eddi://ai.labs.rag", RagTask.TASK_ID.getIdentifier());
        assertEquals(RagTask.TASK_ID, TaskId.fromValue("eddi://ai.labs.rag"));
        assertEquals(RagTask.TASK_ID, TaskId.fromValue("ai.labs.rag"));
    }

    @Test
    @DisplayName("the task resolves the knowledge base exactly once per deployment")
    void knowledgeBaseResolvedOncePerDeploy() throws Exception {
        registerRagTask();

        libraryFor(ragWorkflow(), new RecordingComponentCache())
                .getExecutableWorkflow(WORKFLOW_ID, WORKFLOW_VERSION);

        verify(resourceClientLibrary, times(1))
                .getResource(any(URI.class), eq(RagConfiguration.class));
    }
}
