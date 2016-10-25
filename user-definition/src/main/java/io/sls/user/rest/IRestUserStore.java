package io.sls.user.rest;

import io.sls.user.model.User;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@Path("/userstore/users")
public interface IRestUserStore {
    String resourceURI = "resource://io.sls.user/userstore/users/";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    URI searchUser(@QueryParam("username") String username);

    @GET
    @GZIP
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    User readUser(@PathParam("userId") String userId);

    @PUT
    @Path("/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateUser(@PathParam("userId") String userId, @GZIP User user);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createUser(@GZIP User user);

    @DELETE
    @Path("/{userId}")
    void deleteUser(@PathParam("userId") String userId);

    @POST
    @Path("/changepassword")
    void changePassword(@QueryParam("userId") String userId, @QueryParam("newPassword") String newPassword);
}
