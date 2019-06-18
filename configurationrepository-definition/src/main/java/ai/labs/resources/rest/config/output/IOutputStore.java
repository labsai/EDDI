package ai.labs.resources.rest.config.output;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.output.model.OutputConfigurationSet;

import java.util.List;

/**
 * @author ginccc
 */
public interface IOutputStore extends IResourceStore<OutputConfigurationSet> {
    OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws IResourceStore.ResourceNotFoundException, ResourceStoreException;

    List<String> readOutputActions(String id, Integer version, String filter, String order, Integer limit) throws ResourceStoreException, ResourceNotFoundException;
}
