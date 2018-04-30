package ai.labs.resources.rest.packages;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.model.PackageConfiguration;

import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
public interface IPackageStore extends IResourceStore<PackageConfiguration> {
    List<DocumentDescriptor> getPackageDescriptorsContainingResource(URI resourceURI) throws ResourceStoreException, ResourceNotFoundException;
}
