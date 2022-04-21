package ai.labs.eddi.configs.behavior;

import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IBehaviorStore extends IResourceStore<BehaviorConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
