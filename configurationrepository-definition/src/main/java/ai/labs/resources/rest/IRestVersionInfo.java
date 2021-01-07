package ai.labs.resources.rest;

import ai.labs.persistence.IResourceStore;
import io.swagger.annotations.ApiOperation;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    @ApiOperation(value = "Redirect to latest version.")
    Response redirectToLatestVersion(@PathParam("id") String id);

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Get current version of this resource.")
    IResourceStore.IResourceId getCurrentVersion(@PathParam("id") String id) throws IResourceStore.ResourceNotFoundException;
}
