package ai.labs.core.rest.internal;

import ai.labs.rest.rest.ILogoutEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

@Slf4j
@RequestScoped
public class LogoutEndpoint implements ILogoutEndpoint {
    @Inject
    @RequestScoped
    @Context
    private HttpServletRequest request;

    @Override
    public Response logout() {
        try {
            request.logout();
            return Response.ok().build();
        } catch (ServletException e) {
            log.error(e.getLocalizedMessage(), e);
            return Response.serverError().build();
        }
    }
}
