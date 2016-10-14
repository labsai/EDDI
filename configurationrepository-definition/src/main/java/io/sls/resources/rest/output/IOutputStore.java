package io.sls.resources.rest.output;

import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.output.model.OutputConfigurationSet;

import java.util.List;

/**
 * User: jarisch
 * Date: 09.08.12
 * Time: 15:32
 */
public interface IOutputStore extends IResourceStore<OutputConfigurationSet> {
    OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws IResourceStore.ResourceNotFoundException, ResourceStoreException;

    List<String> readOutputKeys(String id, Integer version, String filter, String order, Integer limit) throws ResourceStoreException, ResourceNotFoundException;
}
