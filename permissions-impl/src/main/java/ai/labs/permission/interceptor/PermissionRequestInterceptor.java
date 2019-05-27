package ai.labs.permission.interceptor;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.IAuthorizationManager;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.impl.AuthorizationManager;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.DependencyInjector;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.rest.RestUserStore;
import ai.labs.user.impl.utilities.UserUtilities;
import ai.labs.user.model.User;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import com.google.inject.Key;
import com.google.inject.name.Names;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.net.URI;
import java.security.Principal;

/**
 * @author ginccc
 */
@Provider
public class PermissionRequestInterceptor implements ContainerRequestFilter {
    private static final String POST = "POST";
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String PATCH = "PATCH";
    private static final String DELETE = "DELETE";
    private final String pathPermissionStore;

    private final IAuthorizationManager authorizationManager;
    private final IUserStore userStore;
    private final boolean skipPermissionCheck;

    @Inject
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
        this.skipPermissionCheck = Boolean.parseBoolean(injector.getInstance(Key.get(String.class, Names.named("system.skipPermissionCheck"))));
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (skipPermissionCheck) {
            return;
        }

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

                    if (user != null) {
                        try {
                            if (!authorizationManager.isUserAuthorized(resourceId.getId(), resourceId.getVersion(), user, authorizationType)) {
                                String username = principal == null ? "anonymous" : principal.getName();
                                String message = "User %s does not have %s permission to access the requested resource.";
                                message = String.format(message, username, authorizationType);
                                throw new WebApplicationException(new Throwable(message), Response.Status.FORBIDDEN);
                            }
                        } catch (IResourceStore.ResourceNotFoundException e) {
                            String message = "Resource (id=%s) does not exist!";
                            message = String.format(message, resourceId.getId());
                            throw new NotFoundException(message);
                        }
                    }
                }
                //no specific resource has been targeted --> allow access
            }
        } catch (IResourceStore.ResourceStoreException e) {
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
