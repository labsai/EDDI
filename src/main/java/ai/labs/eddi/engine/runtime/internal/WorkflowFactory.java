/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.IWorkflowFactory;
import ai.labs.eddi.engine.runtime.client.workflows.IWorkflowStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowFactory implements IWorkflowFactory {
    private final Map<WorkflowId, IExecutableWorkflow> executableWorkflows = new ConcurrentHashMap<>();
    private final IWorkflowStoreClientLibrary workflowStoreClientLibrary;

    @Inject
    public WorkflowFactory(IWorkflowStoreClientLibrary workflowStoreClientLibrary) {
        this.workflowStoreClientLibrary = workflowStoreClientLibrary;
    }

    @Override
    public IExecutableWorkflow getExecutableWorkflow(final String workflowId, final Integer workflowVersion) throws ServiceException {
        WorkflowId id = new WorkflowId(workflowId, workflowVersion);
        if (!executableWorkflows.containsKey(id)) {
            synchronized (executableWorkflows) {
                IExecutableWorkflow executableWorkflow = workflowStoreClientLibrary.getExecutableWorkflow(workflowId, workflowVersion);
                executableWorkflows.put(id, executableWorkflow);
            }
        }

        return executableWorkflows.get(id);
    }

    private class WorkflowId {
        private final String id;
        private final Integer version;

        private WorkflowId(String id, Integer version) {
            this.id = id;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            WorkflowId workflowId = (WorkflowId) o;

            return id.equals(workflowId.id) && version.equals(workflowId.version);

        }

        @Override
        public int hashCode() {
            int result = (id != null ? id.hashCode() : 0);
            result = 31 * result + (version != null ? version.hashCode() : 0);
            return result;
        }
    }
}
