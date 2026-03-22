package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

/**
 * @author ginccc
 */
public interface IWorkflowStoreService {
    WorkflowConfiguration getKnowledgeWorkflow(String workflowId, Integer packageVersion) throws ServiceException;

    DocumentDescriptor getWorkflowDocumentDescriptor(String workflowId, Integer packageVersion) throws ServiceException;
}
