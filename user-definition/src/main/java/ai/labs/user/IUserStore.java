package ai.labs.user;

import ai.labs.persistence.IResourceStore;
import ai.labs.user.model.User;

/**
 * @author ginccc
 */
public interface IUserStore {
    String searchUser(String username) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    User readUser(String userId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void updateUser(String userId, User user) throws IResourceStore.ResourceStoreException;

    String createUser(User user) throws IResourceStore.ResourceStoreException;

    void deleteUser(String userId);

    int getUsersCount();
}
