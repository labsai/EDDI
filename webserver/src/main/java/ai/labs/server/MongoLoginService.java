package ai.labs.server;

import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.ThreadContext;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.SecurityUtilities;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.IdentityManager;
import io.undertow.security.idm.PasswordCredential;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.server.UserIdentity;

import javax.inject.Inject;
import java.util.Arrays;

/**
 * @author ginccc
 */
@Slf4j
public class MongoLoginService implements IdentityManager {
    @Inject
    private IUserStore userStore;

    private void bindUserDataToThread(User user) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        String username = user.getUsername();
        ThreadContext.put("currentuser:userid", userStore.searchUser(username));
        ThreadContext.put("currentuser:displayname", user.getDisplayName());
        ThreadContext.put("currentuser:username", username);
    }

    private User lookupUser(String username, Credential credential) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
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
        return null;
    }

    @Override
    public Account verify(String id, Credential credential) {
        return null;
    }

    @Override
    public Account verify(Credential credential) {
        try {
            PasswordCredential passwordCredential = (PasswordCredential) credential;
            User user = lookupUser(Arrays.toString(passwordCredential.getPassword()), passwordCredential);
            if (user != null) {
                UserIdentity userIdentity = createUserIdentity(username, passwordCredential);
                AbstractLoginService.UserPrincipal principal = (AbstractLoginService.UserPrincipal) userIdentity.getUserPrincipal();
                if (principal.authenticate(credentials)) {
                    bindUserDataToThread(user);
                    ThreadContext.bind(userIdentity.getSubject());
                    return userIdentity;
                }
            }
        } catch (IResourceStore.ResourceStoreException e) {
            log.error("Could not process login.", e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            //no user entity found for the given username
        }

        return null;
    }
}
