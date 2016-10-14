package io.sls.user.impl.utilities;

import io.sls.persistence.IResourceStore;
import io.sls.user.IUserStore;
import io.sls.user.rest.IRestUserStore;
import io.sls.utilities.RuntimeUtilities;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.KeycloakSecurityContext;

import java.net.URI;
import java.security.Principal;

/**
 * User: jarisch
 * Date: 12.09.12
 * Time: 13:47
 */
public final class UserUtilities {
    private static final String USER_STORE = "userstore";

    private UserUtilities() {
        //utility class
    }

    public static URI getUserURI(IUserStore userStore, Principal principal) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        if (RuntimeUtilities.isNullOrEmpty(principal)) {
            return null;
        }

        String username = getUserName(principal);
        String userId = userStore.searchUser(username != null ? username : principal.getName());
        String userURI = IRestUserStore.resourceURI + userId;

        return URI.create(userURI);
    }

    private static String getUserName(Principal principal) {
        String username = null;
        if(principal instanceof KeycloakPrincipal) {
            KeycloakSecurityContext securityContext = ((KeycloakPrincipal) principal).getKeycloakSecurityContext();
            username = securityContext.getToken().getPreferredUsername();
        }

        return username;
    }
}
