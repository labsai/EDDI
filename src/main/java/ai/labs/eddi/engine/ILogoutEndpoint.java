package ai.labs.eddi.engine;

import org.eclipse.microprofile.openapi.annotations.Operation;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

// @Api(value = "User Management -> (4) Logout Current User", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/user")
@Produces(MediaType.TEXT_PLAIN)
public interface ILogoutEndpoint {
    @GET
    @Path("/isAuthenticated")
    @Operation(description = "Check if current user is authenticated.")
    Response isUserAuthenticated();

    @GET
    @Path("/securityType")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Read currently enabled security type.")
    Response getSecurityType();

    @POST
    @Path("/logout")
    @Operation(description = "Logout current authenticated user.")
    void logout();
}
