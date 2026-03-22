package ai.labs.eddi.engine.runtime.service;

import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowStoreService implements IWorkflowStoreService {
    private final IRestWorkflowStore restWorkflowStore;
    private final IRestDocumentDescriptorStore restDocumentDescriptorStore;

    @Inject
    public WorkflowStoreService(IRestWorkflowStore restWorkflowStore,
            IRestDocumentDescriptorStore restDocumentDescriptorStore) {
        this.restWorkflowStore = restWorkflowStore;
        this.restDocumentDescriptorStore = restDocumentDescriptorStore;
    }

    @Override
    public WorkflowConfiguration getKnowledgeWorkflow(String workflowId, Integer packageVersion)
            throws ServiceException {
        try {
            return restWorkflowStore.readWorkflow(workflowId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor getWorkflowDocumentDescriptor(String workflowId, Integer packageVersion)
            throws ServiceException {
        try {
            return restDocumentDescriptorStore.readDescriptor(workflowId, packageVersion);
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }
    }
}
