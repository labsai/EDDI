/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WorkflowStoreService")
class WorkflowStoreServiceTest {

    private WorkflowStoreService service;
    private IWorkflowStore workflowStore;
    private IDocumentDescriptorStore descriptorStore;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        service = new WorkflowStoreService(workflowStore, descriptorStore);
    }

    @Test
    @DisplayName("getKnowledgeWorkflow delegates to restWorkflowStore")
    void getKnowledgeWorkflow() throws Exception {
        var config = new WorkflowConfiguration();
        doReturn(config).when(workflowStore).read("wf1", 1);

        var result = service.getKnowledgeWorkflow("wf1", 1);

        assertSame(config, result);
        verify(workflowStore).read("wf1", 1);
    }

    @Test
    @DisplayName("getKnowledgeWorkflow wraps exception as ServiceException")
    void getKnowledgeWorkflowException() throws Exception {
        doThrow(new RuntimeException("not found")).when(workflowStore).read("wf1", 1);

        var ex = assertThrows(ServiceException.class, () -> service.getKnowledgeWorkflow("wf1", 1));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("getWorkflowDocumentDescriptor delegates to descriptorStore")
    void getWorkflowDocumentDescriptor() throws Exception {
        var descriptor = new DocumentDescriptor();
        doReturn(descriptor).when(descriptorStore).readDescriptor("wf1", 1);

        var result = service.getWorkflowDocumentDescriptor("wf1", 1);

        assertSame(descriptor, result);
        verify(descriptorStore).readDescriptor("wf1", 1);
    }

    @Test
    @DisplayName("getWorkflowDocumentDescriptor wraps exception as ServiceException")
    void getWorkflowDocumentDescriptorException() throws Exception {
        doThrow(new RuntimeException("db error")).when(descriptorStore).readDescriptor("wf1", 1);

        var ex = assertThrows(ServiceException.class, () -> service.getWorkflowDocumentDescriptor("wf1", 1));
        assertTrue(ex.getMessage().contains("db error"));
    }
}
