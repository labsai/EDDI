package ai.labs.server.providers;

import ai.labs.runtime.ThreadContext;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.KeycloakSecurityContext;

import javax.inject.Inject;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.security.Principal;

/**
 * @author ginccc
 */

@Provider
public class KeycloakAuthorizationHeaderFilter implements ContainerRequestFilter {
    @Inject
    @Context
    SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        final Principal userPrincipal = this.securityContext.getUserPrincipal();
        if (userPrincipal instanceof KeycloakPrincipal) {
            KeycloakPrincipal<KeycloakSecurityContext> keycloakPrincipal =
                    (KeycloakPrincipal<KeycloakSecurityContext>) userPrincipal;
            KeycloakSecurityContext securityContext = keycloakPrincipal.getKeycloakSecurityContext();
            ThreadContext.put("security.token", securityContext.getTokenString());
        }
    }

}
