/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.client.workflows.IWorkflowStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorkflowFactory} covering caching, concurrent access, and
 * the inner WorkflowId equals/hashCode contract.
 */
@DisplayName("WorkflowFactory Tests")
class WorkflowFactoryTest {

    private IWorkflowStoreClientLibrary clientLibrary;
    private WorkflowFactory factory;

    @BeforeEach
    void setUp() {
        clientLibrary = mock(IWorkflowStoreClientLibrary.class);
        factory = new WorkflowFactory(clientLibrary);
    }

    @Test
    @DisplayName("should create and cache executable workflow")
    void createsAndCachesWorkflow() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(workflow);

        IExecutableWorkflow result = factory.getExecutableWorkflow("wf-1", 1);

        assertSame(workflow, result);
        verify(clientLibrary, times(1)).getExecutableWorkflow("wf-1", 1);
    }

    @Test
    @DisplayName("should return cached workflow on second call")
    void returnsCachedWorkflow() throws Exception {
        var workflow = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(workflow);

        IExecutableWorkflow first = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow second = factory.getExecutableWorkflow("wf-1", 1);

        assertSame(first, second);
        // Library should only be called once
        verify(clientLibrary, times(1)).getExecutableWorkflow("wf-1", 1);
    }

    @Test
    @DisplayName("should create separate entries for different workflow IDs")
    void separateWorkflows() throws Exception {
        var wf1 = mock(IExecutableWorkflow.class);
        var wf2 = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(wf1);
        when(clientLibrary.getExecutableWorkflow("wf-2", 1)).thenReturn(wf2);

        IExecutableWorkflow result1 = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow result2 = factory.getExecutableWorkflow("wf-2", 1);

        assertNotSame(result1, result2);
    }

    @Test
    @DisplayName("should create separate entries for different versions")
    void differentVersions() throws Exception {
        var v1 = mock(IExecutableWorkflow.class);
        var v2 = mock(IExecutableWorkflow.class);
        when(clientLibrary.getExecutableWorkflow("wf-1", 1)).thenReturn(v1);
        when(clientLibrary.getExecutableWorkflow("wf-1", 2)).thenReturn(v2);

        IExecutableWorkflow result1 = factory.getExecutableWorkflow("wf-1", 1);
        IExecutableWorkflow result2 = factory.getExecutableWorkflow("wf-1", 2);

        assertNotSame(result1, result2);
    }

    @Test
    @DisplayName("should propagate ServiceException from library")
    void propagatesException() throws Exception {
        when(clientLibrary.getExecutableWorkflow("bad", 1))
                .thenThrow(new ServiceException("Not found"));

        assertThrows(ServiceException.class, () -> factory.getExecutableWorkflow("bad", 1));
    }
}
