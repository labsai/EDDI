package io.sls.group;

import io.sls.group.model.Group;
import io.sls.persistence.IResourceStore;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 13:43
 */
public interface IGroupStore {
    Group readGroup(String groupId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void updateGroup(String groupId, Group group);

    String createGroup(Group group) throws IResourceStore.ResourceStoreException;

    void deleteGroup(String groupId);
}
