package io.sls.resources.rest.extensions;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.extensions.model.ExtensionDefinition;

import java.util.List;

/**
 * User: jarisch
 * Date: 11.09.12
 * Time: 12:06
 */
public interface IExtensionStore extends IResourceStore<ExtensionDefinition> {
    IResourceId searchExtension(String uri);

    List<ExtensionDefinition> readExtensions(String filter, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException;
}
