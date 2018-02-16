package ai.labs.user.impl.utilities;

import ai.labs.persistence.IResourceStore;
import ai.labs.user.IUserStore;
import ai.labs.user.rest.IRestUserStore;
import ai.labs.utilities.RuntimeUtilities;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.KeycloakSecurityContext;

import java.net.URI;
import java.security.Principal;

/**
 * @author ginccc
 */
public final class UserUtilities {
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
        if (principal instanceof KeycloakPrincipal) {
            KeycloakSecurityContext securityContext = ((KeycloakPrincipal) principal).getKeycloakSecurityContext();
            username = securityContext.getToken().getPreferredUsername();
        }

        return username;
    }
}
