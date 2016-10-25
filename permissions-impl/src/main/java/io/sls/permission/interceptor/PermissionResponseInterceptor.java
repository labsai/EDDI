package io.sls.permission.interceptor;

import io.sls.permission.IAuthorization;
import io.sls.permission.IPermissionStore;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.model.Permissions;
import io.sls.permission.utilities.PermissionUtilities;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.DependencyInjector;
import io.sls.runtime.ThreadContext;
import io.sls.testing.ITestCaseStore;
import io.sls.testing.model.TestCase;
import io.sls.user.IUserStore;
import io.sls.user.impl.utilities.UserUtilities;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.SecurityUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.annotations.interception.SecurityPrecedence;
import org.jboss.resteasy.annotations.interception.ServerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;

/**
 * User: jarisch
 * Date: 27.08.12
 * Time: 13:13
 */

@Provider
@ServerInterceptor
@SecurityPrecedence
@Slf4j
public class PermissionResponseInterceptor implements ContainerResponseFilter {
    public static final String METHOD_NAME_CREATE_USER = "createUser";
    private static final String METHOD_NAME_START_CONVERSATION = "startConversation";
    private static final String METHOD_NAME_CREATE_TESTCASE = "createTestCase";
    private final IUserStore userStore;
    private final IPermissionStore permissionStore;

    @Context
    private HttpServletRequest httpServletRequest;

    @Context
    private ResourceInfo resourceInfo;
    private final DependencyInjector injector;

    public PermissionResponseInterceptor() {
        injector = DependencyInjector.getInstance();
        this.userStore = injector.getInstance(IUserStore.class);
        this.permissionStore = injector.getInstance(IPermissionStore.class);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
        try {
            // it was most likely a CREATE request
            if (resourceInfo.getResourceMethod() != null && resourceInfo.getResourceMethod().isAnnotationPresent(POST.class)) {
                int httpStatus = response.getStatus();
                String methodName = resourceInfo.getResourceMethod().getName();
                // the resource was created successfully
                if (httpStatus == 201) {
                    String respondedResourceURIString = response.getEntity().toString();
                    URI respondedResourceURI = URI.create(respondedResourceURIString);
                    IResourceStore.IResourceId respondedResourceId = RestUtilities.extractResourceId(respondedResourceURI);

                    //if the created resource is a user, we treat it differently
                    if (methodName.equals(METHOD_NAME_CREATE_USER)) {
                        permissionStore.createPermissions(respondedResourceId.getId(), PermissionUtilities.createDefaultPermissions(respondedResourceURI));
                    } else {
                        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
                        URI userURI = UserUtilities.getUserURI(userStore, userPrincipal);
                        if (methodName.equals(METHOD_NAME_START_CONVERSATION)) {
                            IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(httpServletRequest.getRequestURI()));
                            Permissions permissions = permissionStore.readPermissions(resourceId.getId());
                            PermissionUtilities.addAuthorizedUser(permissions, IAuthorization.Type.WRITE, new AuthorizedUser(userURI, null));
                            permissionStore.createPermissions(respondedResourceId.getId(), permissions);
                        } else if (methodName.equals(METHOD_NAME_CREATE_TESTCASE)) {
                            ITestCaseStore testCaseStore = injector.getInstance(ITestCaseStore.class);
                            TestCase testCase = testCaseStore.loadTestCase(respondedResourceId.getId());
                            Permissions permissions = permissionStore.readPermissions(testCase.getBotId());
                            PermissionUtilities.addAuthorizedUser(permissions, IAuthorization.Type.ADMINISTRATION, new AuthorizedUser(userURI, null));
                            permissionStore.createPermissions(respondedResourceId.getId(), permissions);
                        } else {
                            permissionStore.createPermissions(respondedResourceId.getId(), PermissionUtilities.createDefaultPermissions(userURI));
                        }
                    }
                } else if (httpStatus >= 200 && httpStatus < 300 && httpStatus != 202) {
                    String message = "A POST request was successfully executed, but didn't return http code 201 or 202). [methodName=%s]";
                    message = String.format(message, methodName);
                    log.error(message);
                }
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }
}
