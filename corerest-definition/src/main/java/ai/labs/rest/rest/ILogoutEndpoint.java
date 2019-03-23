package ai.labs.rest.rest;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("logout")
public interface ILogoutEndpoint {
    @POST
    Response logout();
}
