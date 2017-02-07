package ai.labs.resources.rest.extensions;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.extensions.model.ExtensionDefinition;

import java.util.List;

/**
 * @author ginccc
 */
public interface IExtensionStore extends IResourceStore<ExtensionDefinition> {
    IResourceId searchExtension(String uri);

    List<ExtensionDefinition> readExtensions(String filter, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException;
}
