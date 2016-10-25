package io.sls.permission.interceptor;

import com.google.inject.Key;
import com.google.inject.name.Names;
import io.sls.group.IGroupStore;
import io.sls.permission.IAuthorization;
import io.sls.permission.IAuthorizationManager;
import io.sls.permission.IPermissionStore;
import io.sls.permission.impl.AuthorizationManager;
import io.sls.permission.utilities.PermissionUtilities;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.DependencyInjector;
import io.sls.user.IUserStore;
import io.sls.user.impl.rest.RestUserStore;
import io.sls.user.impl.utilities.UserUtilities;
import io.sls.user.model.User;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.RuntimeUtilities;
import org.jboss.resteasy.annotations.interception.SecurityPrecedence;
import org.jboss.resteasy.annotations.interception.ServerInterceptor;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;

/**
 * @author ginccc
 */
@Provider
@ServerInterceptor
@SecurityPrecedence
public class PermissionRequestInterceptor implements ContainerRequestFilter {
    private static final String POST = "POST";
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String PATCH = "PATCH";
    private static final String DELETE = "DELETE";
    private final String pathPermissionStore;

    private final IAuthorizationManager authorizationManager;
    private final IUserStore userStore;

    @Context
    private SecurityContext securityContext;
    private final IPermissionStore permissionStore;

    public PermissionRequestInterceptor() {
        DependencyInjector injector = DependencyInjector.getInstance();
        this.userStore = injector.getInstance(IUserStore.class);
        permissionStore = injector.getInstance(IPermissionStore.class);
        authorizationManager = new AuthorizationManager(injector.getInstance(IGroupStore.class),
                permissionStore);
        this.pathPermissionStore = injector.getInstance(Key.get(String.class, Names.named("system.pathOfPermissionStore")));
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        try {
            String httpMethod = request.getMethod();
            URI uri = request.getUriInfo().getRequestUri();
            String path = uri.getPath();

            IAuthorization.Type authorizationType;
            if (path.startsWith(pathPermissionStore)) {
                authorizationType = IAuthorization.Type.ADMINISTRATION;
            } else {
                authorizationType = getAuthorizationType(httpMethod);
            }

            if (authorizationType != null) {
                IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);

                if (resourceId.getId() != null) {
                    Principal principal = securityContext.getUserPrincipal();
                    URI user = getUser(principal);

                    if (!authorizationManager.isUserAuthorized(resourceId.getId(), resourceId.getVersion(), user, authorizationType)) {
                        String username = principal == null ? "anonymous" : principal.getName();
                        String message = "User %s does not have %s permission to access the requested resource.";
                        message = String.format(message, username, authorizationType);
                        throw new WebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
                    }
                }
                //no specific resource has been targeted --> allow access
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private URI getUser(Principal principal) throws IResourceStore.ResourceStoreException {
        try {
            return UserUtilities.getUserURI(userStore, principal);
        } catch (IResourceStore.ResourceNotFoundException e) {
            User user = new User();
            user.setUsername(principal.getName());
            String id = userStore.createUser(user);
            URI userUri = URI.create(RestUserStore.resourceURI + id);
            permissionStore.createPermissions(id, PermissionUtilities.createDefaultPermissions(userUri));
            return userUri;
        }
    }

    private IAuthorization.Type getAuthorizationType(String httpMethod) {
        RuntimeUtilities.checkNotNull(httpMethod, "httpMethod");

        if (POST.equals(httpMethod)) {
            return IAuthorization.Type.USE;
        }

        if (GET.equals(httpMethod)) {
            return IAuthorization.Type.READ;
        }

        if (PUT.equals(httpMethod)) {
            return IAuthorization.Type.WRITE;
        }

        if (PATCH.equals(httpMethod)) {
            return IAuthorization.Type.WRITE;
        }

        if (DELETE.equals(httpMethod)) {
            return IAuthorization.Type.ADMINISTRATION;
        }

        return null;
    }
}
