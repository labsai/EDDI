package io.sls.resources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    Integer getCurrentVersion(@PathParam("id") String id);
}
