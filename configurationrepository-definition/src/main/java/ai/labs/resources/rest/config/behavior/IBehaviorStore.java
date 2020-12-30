package ai.labs.resources.rest.config.behavior;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;

import java.util.List;

/**
 * @author ginccc
 */
public interface IBehaviorStore extends IResourceStore<BehaviorConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit) throws ResourceStoreException, ResourceNotFoundException;
}
