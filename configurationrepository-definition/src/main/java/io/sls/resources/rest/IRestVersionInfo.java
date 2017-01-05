package io.sls.resources.rest;

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
    @Produces(MediaType.TEXT_PLAIN)
    Response redirectToLatestVersion(@PathParam("id") String id);

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    Integer getCurrentVersion(@PathParam("id") String id);
}
