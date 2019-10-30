package ai.labs.core.rest.internal;

import ai.labs.rest.restinterfaces.ILogoutEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;

@Slf4j
@RequestScoped
public class LogoutEndpoint implements ILogoutEndpoint {

    private final String securityHandleType;
    @Inject
    @RequestScoped
    @Context
    private HttpServletRequest request;

    @Inject
    @RequestScoped
    @Context
    private HttpServletResponse response;

    @Inject
    public LogoutEndpoint(@Named("webServer.securityHandlerType") String securityHandleType) {
        this.securityHandleType = securityHandleType;
    }

    @Override
    public Response isUserAuthenticated() {
        return request.getUserPrincipal() != null ? Response.noContent().build() : Response.status(NOT_FOUND).build();
    }

    @Override
    public Response getSecurityType() {
        return Response.ok(securityHandleType).build();
    }

    @Override
    public void logout() {
        try {
            request.logout();
            response.sendRedirect("/");
        } catch (ServletException | IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }
}
