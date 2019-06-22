package ai.labs.permission.rest;

import ai.labs.permission.model.Permissions;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
@Api(value = "User Management -> (3) Permissions", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/permissionstore/permissions")
public interface IRestPermissionStore {
    @GET
    @Path("/{resourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read permission.")
    Permissions readPermissions(@PathParam("resourceId") String resourceId);

    @PUT
    @Path("/{resourceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update permission.")
    void updatePermissions(@PathParam("resourceId") String resourceId, Permissions permissions);
}
