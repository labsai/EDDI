/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Loads a workflow and its descriptor for the engine.
 * <p>
 * Reads the stores directly rather than the {@code IRest*} facades, for the
 * reason spelled out on {@link AgentStoreService}: this runs inside a
 * conversation turn, under the chatting user's identity, and the authoring
 * surface's ownership checks do not apply to it.
 *
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowStoreService implements IWorkflowStoreService {
    private final IWorkflowStore workflowStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public WorkflowStoreService(IWorkflowStore workflowStore, IDocumentDescriptorStore documentDescriptorStore) {
        this.workflowStore = workflowStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public WorkflowConfiguration getKnowledgeWorkflow(String workflowId, Integer workflowVersion) throws ServiceException {
        try {
            return workflowStore.read(workflowId, workflowVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor getWorkflowDocumentDescriptor(String workflowId, Integer workflowVersion) throws ServiceException {
        try {
            return documentDescriptorStore.readDescriptor(workflowId, workflowVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
