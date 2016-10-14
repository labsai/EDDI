package io.sls.resources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * User: jarisch
 * Date: 17.08.12
 * Time: 14:58
 */
public interface IRestVersionInfo {
    @GET
    @Path("/{id}/currentversion")
    Integer getCurrentVersion(@PathParam("id") String id);
}
