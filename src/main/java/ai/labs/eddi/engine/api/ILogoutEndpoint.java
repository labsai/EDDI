package ai.labs.eddi.engine.api;

import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
