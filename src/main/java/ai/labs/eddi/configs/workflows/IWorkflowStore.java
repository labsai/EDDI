package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IWorkflowStore extends IResourceStore<WorkflowConfiguration> {
    List<DocumentDescriptor> getPackageDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
