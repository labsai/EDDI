package ai.labs.permission.interceptor;

import ai.labs.permission.IAuthorization;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.model.Permissions;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.DependencyInjector;
import ai.labs.runtime.ThreadContext;
import ai.labs.testing.ITestCaseStore;
import ai.labs.testing.model.TestCase;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.utilities.UserUtilities;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.SecurityUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.Provider;
import java.net.URI;
import java.security.Principal;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;


/**
 * @author ginccc
 */
@Provider
@Slf4j
public class PermissionResponseInterceptor implements ContainerResponseFilter {
    private static final String METHOD_NAME_CREATE_USER = "createUser";
    private static final String METHOD_NAME_DUPLICATE_RESOURCE = "duplicate";
    private static final String METHOD_NAME_START_CONVERSATION = "startConversation";
    private static final String METHOD_NAME_CREATE_TESTCASE = "createTestCase";
    private final IUserStore userStore;
    private final IPermissionStore permissionStore;

    @Inject
    @Context
    private HttpServletRequest httpServletRequest;

    @Inject
    @Context
    private ResourceInfo resourceInfo;
    private final DependencyInjector injector;

    public PermissionResponseInterceptor() {
        injector = DependencyInjector.getInstance();
        this.userStore = injector.getInstance(IUserStore.class);
        this.permissionStore = injector.getInstance(IPermissionStore.class);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        try {
            // it was most likely a CREATE request
            if (resourceInfo.getResourceMethod() != null && resourceInfo.getResourceMethod().isAnnotationPresent(POST.class)) {
                int httpStatus = response.getStatus();
                String methodName = resourceInfo.getResourceMethod().getName();
                // the resource was created successfully
                if (httpStatus == 201) {
                    String respondedResourceURIString = response.getHeaderString(HttpHeaders.LOCATION);
                    if (isNullOrEmpty(respondedResourceURIString)) {
                        log.info("No permission created for 201 response of method {}", methodName);
                        return;
                    }
                    URI respondedResourceURI = URI.create(respondedResourceURIString);
                    IResourceStore.IResourceId respondedResourceId = RestUtilities.extractResourceId(respondedResourceURI);

                    //if the created resource is a user, we treat it differently
                    String resourceId = respondedResourceId.getId();
                    if (methodName.equals(METHOD_NAME_CREATE_USER)) {
                        permissionStore.createPermissions(resourceId, PermissionUtilities.createDefaultPermissions(respondedResourceURI));
                    } else if (!methodName.startsWith(METHOD_NAME_DUPLICATE_RESOURCE)) {
                        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
                        URI userURI = UserUtilities.getUserURI(userStore, userPrincipal);

                        if (methodName.equals(METHOD_NAME_CREATE_TESTCASE)) {
                            ITestCaseStore testCaseStore = injector.getInstance(ITestCaseStore.class);
                            TestCase testCase = testCaseStore.loadTestCase(resourceId);
                            Permissions permissions = permissionStore.readPermissions(testCase.getBotId());
                            if (userURI != null) {
                                PermissionUtilities.addAuthorizedUser(permissions, IAuthorization.Type.ADMINISTRATION, new AuthorizedUser(userURI, null));
                            }
                            permissionStore.createPermissions(resourceId, permissions);
                        } else if (!methodName.equals(METHOD_NAME_START_CONVERSATION)) {
                            permissionStore.createPermissions(resourceId, PermissionUtilities.createDefaultPermissions(userURI));
                        }
                    }
                }
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }
}
