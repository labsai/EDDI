package ai.labs.user.rest;

import ai.labs.user.model.User;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@Api(value = "User Management -> (1) Users", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/userstore/users")
public interface IRestUserStore {
    String resourceURI = "eddi://ai.labs.user/userstore/users/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Search user.")
    URI searchUser(@QueryParam("username") String username);

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read user.")
    User readUser(@PathParam("userId") String userId);

    @PUT
    @Path("/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update user.")
    void updateUser(@PathParam("userId") String userId, User user);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create user.")
    Response createUser(User user);

    @DELETE
    @Path("/{userId}")
    @ApiOperation(value = "Delete user.")
    void deleteUser(@PathParam("userId") String userId);

    @POST
    @Path("/changepassword")
    @ApiOperation(value = "Change password of user.")
    void changePassword(@QueryParam("userId") String userId, @QueryParam("newPassword") String newPassword);
}
