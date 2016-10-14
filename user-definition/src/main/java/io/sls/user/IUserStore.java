package io.sls.user;

import io.sls.persistence.IResourceStore;
import io.sls.user.model.User;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 13:43
 */
public interface IUserStore {
    String searchUser(String username) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    User readUser(String userId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void updateUser(String userId, User user) throws IResourceStore.ResourceStoreException;

    String createUser(User user) throws IResourceStore.ResourceStoreException;

    void deleteUser(String userId);
}
