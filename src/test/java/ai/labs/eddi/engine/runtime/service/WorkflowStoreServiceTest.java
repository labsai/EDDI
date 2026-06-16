/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WorkflowStoreService")
class WorkflowStoreServiceTest {

    private WorkflowStoreService service;
    private IRestWorkflowStore workflowStore;
    private IRestDocumentDescriptorStore descriptorStore;

    @BeforeEach
    void setUp() {
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        service = new WorkflowStoreService(workflowStore, descriptorStore);
    }

    @Test
    @DisplayName("getKnowledgeWorkflow delegates to restWorkflowStore")
    void getKnowledgeWorkflow() throws Exception {
        var config = new WorkflowConfiguration();
        doReturn(config).when(workflowStore).readWorkflow("wf1", 1);

        var result = service.getKnowledgeWorkflow("wf1", 1);

        assertSame(config, result);
        verify(workflowStore).readWorkflow("wf1", 1);
    }

    @Test
    @DisplayName("getKnowledgeWorkflow wraps exception as ServiceException")
    void getKnowledgeWorkflowException() throws Exception {
        doThrow(new RuntimeException("not found")).when(workflowStore).readWorkflow("wf1", 1);

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
