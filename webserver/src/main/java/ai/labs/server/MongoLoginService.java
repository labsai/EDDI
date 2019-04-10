package ai.labs.server;

import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.ThreadContext;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.SecurityUtilities;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Credential;

import javax.inject.Inject;
import javax.security.auth.Subject;
import javax.servlet.ServletRequest;
import java.security.Principal;

/**
 * @author ginccc
 */
@Slf4j
public class MongoLoginService implements LoginService {
    private final IUserStore userStore;
    private IdentityService identityService = new DefaultIdentityService();

    @Inject
    public MongoLoginService(IUserStore userStore) {
        this.userStore = userStore;
    }

    @Override
    public String getName() {
        return "Please log in into EDDI with username and password!";
    }

    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request) {
        try {
            Credential credential = Credential.getCredential((String) credentials);
            User user = lookupUser(username, credential);
            if (user != null) {
                UserIdentity userIdentity = createUserIdentity(username, credential);
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

    private UserIdentity createUserIdentity(String username, Credential credential) {
        Principal userPrincipal = new AbstractLoginService.UserPrincipal(username, credential);
        Subject subject = new Subject();
        subject.getPrincipals().add(userPrincipal);
        subject.getPrivateCredentials().add(credential);
        subject.setReadOnly();
        return identityService.newUserIdentity(subject, userPrincipal, new String[]{"user"});
    }

    @Override
    public boolean validate(UserIdentity userIdentity) {
        return false;  //TODO
    }

    @Override
    public IdentityService getIdentityService() {
        return identityService;
    }

    @Override
    public void setIdentityService(IdentityService identityService) {
        //TODO
    }

    @Override
    public void logout(UserIdentity userIdentity) {
        //TODO
    }
}
