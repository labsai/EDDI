package io.sls.resources.rest.extensions;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.extensions.model.ExtensionDefinition;

import java.util.List;

/**
 * @author ginccc
 */
public interface IExtensionStore extends IResourceStore<ExtensionDefinition> {
    IResourceId searchExtension(String uri);

    List<ExtensionDefinition> readExtensions(String filter, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException;
}
