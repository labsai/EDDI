package ai.labs.server;

import ai.labs.runtime.ThreadContext;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.SecurityUtilities;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.IdentityManager;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.security.Principal;
import java.util.Set;

import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;

/**
 * @author ginccc
 */
@Slf4j
public class MongoLoginService implements IdentityManager {
    @Inject
    private IUserStore userStore;

    private void bindUserDataToThread(User user) throws ResourceStoreException, ResourceNotFoundException {
        String username = user.getUsername();
        ThreadContext.put("currentuser:userid", userStore.searchUser(username));
        ThreadContext.put("currentuser:displayname", user.getDisplayName());
        ThreadContext.put("currentuser:username", username);
    }

    private User lookupUser(String username, Credential credential) throws ResourceStoreException, ResourceNotFoundException {
        User user = userStore.readUser(userStore.searchUser(username));
        String hashedPassword = SecurityUtilities.hashPassword(credential.toString(), user.getSalt());
        if (hashedPassword.equals(user.getPassword())) {
            return user;
        } else {
            return null;
        }
    }

    @Override
    public Account verify(Account account) {
        // An existing account so for testing assume still valid.
        return account;
    }

    @Override
    public Account verify(String id, Credential credential) {
        try {
            Account account = getAccount(id);
            User user;
            if (account != null && ((user = verifyCredential(account, credential)) != null)) {
                bindUserDataToThread(user);
                return account;
            }
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        return null;
    }

    @Override
    public Account verify(Credential credential) {
        return null;
    }

    private User verifyCredential(Account account, Credential credential) throws ResourceStoreException, ResourceNotFoundException {
        return verifyCredential(account.getPrincipal().getName(), credential);
    }

    private User verifyCredential(String name, Credential credential) throws ResourceStoreException, ResourceNotFoundException {
        return lookupUser(name, credential);
    }

    private Account getAccount(final String id) throws ResourceStoreException, ResourceNotFoundException {
        User user = userStore.readUser(id);
        if (user != null) {
            return new Account() {
                @Override
                public Principal getPrincipal() {
                    return user::getUsername;
                }

                @Override
                public Set<String> getRoles() {
                    return null;
                }
            };
        } else {
            return null;
        }
    }

}
