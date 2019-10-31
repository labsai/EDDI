package ai.labs.rest.restinterfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(value = "User Management -> (4) Logout Current User", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/user")
@Produces(MediaType.TEXT_PLAIN)
public interface ILogoutEndpoint {
    @GET
    @Path("/isAuthenticated")
    @ApiOperation(value = "Check if current user is authenticated.")
    Response isUserAuthenticated();

    @GET
    @Path("/securityType")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Read currently enabled security type.")
    Response getSecurityType();

    @GET
    @POST
    @Path("/logout")
    @ApiOperation(value = "Logout current authenticated user.")
    void logout();
}
