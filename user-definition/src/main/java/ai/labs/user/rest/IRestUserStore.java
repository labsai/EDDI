package ai.labs.user.rest;

import ai.labs.user.model.User;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@Api(value = "users")
@Path("/userstore/users")
public interface IRestUserStore {
    String resourceURI = "eddi://ai.labs.user/userstore/users/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    URI searchUser(@QueryParam("username") String username);

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    User readUser(@PathParam("userId") String userId);

    @PUT
    @Path("/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateUser(@PathParam("userId") String userId, User user);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createUser(User user);

    @DELETE
    @Path("/{userId}")
    void deleteUser(@PathParam("userId") String userId);

    @POST
    @Path("/changepassword")
    void changePassword(@QueryParam("userId") String userId, @QueryParam("newPassword") String newPassword);
}
