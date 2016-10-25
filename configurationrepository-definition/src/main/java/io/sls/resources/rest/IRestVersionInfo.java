package io.sls.resources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    @GET
    @Path("/{id}/currentversion")
    Integer getCurrentVersion(@PathParam("id") String id);
}
