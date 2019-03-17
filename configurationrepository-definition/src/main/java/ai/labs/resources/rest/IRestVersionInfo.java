package ai.labs.resources.rest;

import io.swagger.annotations.ApiOperation;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    Response redirectToLatestVersion(@PathParam("id") String id);

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    Integer getCurrentVersion(@PathParam("id") String id);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this resource.")
    Response duplicateResource(@PathParam("id") String id, @QueryParam("version") Integer version);
}
