package ai.labs.permission.rest;

import ai.labs.permission.model.Permissions;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
@Path("/permissionstore/permissions")
public interface IRestPermissionStore {
    @GET
    @Path("/{resourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    Permissions readPermissions(@PathParam("resourceId") String resourceId) throws Exception;

    @PUT
    @Path("/{resourceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updatePermissions(@PathParam("resourceId") String resourceId, Permissions permissions);
}
