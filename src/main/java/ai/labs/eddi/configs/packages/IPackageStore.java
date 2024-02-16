package ai.labs.eddi.configs.packages;

import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPackageStore extends IResourceStore<PackageConfiguration> {
    List<DocumentDescriptor> getPackageDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
