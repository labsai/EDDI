package ai.labs.rest.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Api(value = "User Management -> (4) Logout Current User")
@Path("/user")
public interface ILogoutEndpoint {
    @GET
    @Path("/isAuthenticated")
    @ApiOperation(value = "Check if current user is authenticated.")
    Response isUserAuthenticated();

    @GET
    @POST
    @Path("/logout")
    @ApiOperation(value = "Logout current authenticated user.")
    void logout();
}
