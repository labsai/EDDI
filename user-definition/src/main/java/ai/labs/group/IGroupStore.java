package ai.labs.group;

import ai.labs.group.model.Group;
import ai.labs.persistence.IResourceStore;

/**
 * @author ginccc
 */
public interface IGroupStore {
    Group readGroup(String groupId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void updateGroup(String groupId, Group group);

    String createGroup(Group group) throws IResourceStore.ResourceStoreException;

    void deleteGroup(String groupId);
}
