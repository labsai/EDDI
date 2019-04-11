package ai.labs.rest.rest;

import io.swagger.annotations.Api;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Api(value = "User Management -> (4) Logout Current User")
@Path("logout")
public interface ILogoutEndpoint {
    @POST
    Response logout();
}
