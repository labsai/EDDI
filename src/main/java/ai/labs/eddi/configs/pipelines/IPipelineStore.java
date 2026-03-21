package ai.labs.eddi.configs.pipelines;

import ai.labs.eddi.configs.pipelines.model.PipelineConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPipelineStore extends IResourceStore<PipelineConfiguration> {
    List<DocumentDescriptor> getPackageDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
