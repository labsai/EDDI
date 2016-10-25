package io.sls.permission.rest;

import io.sls.permission.model.Permissions;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
@Path("/permissionstore/permissions")
public interface IRestPermissionStore {
    @GET
    @GZIP
    @Path("/{resourceId}")
    @Produces(MediaType.APPLICATION_JSON)
    Permissions readPermissions(@PathParam("resourceId") String resourceId) throws Exception;

    @PUT
    @Path("/{resourceId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updatePermissions(@PathParam("resourceId") String resourceId, @GZIP Permissions permissions);
}
