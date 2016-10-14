package io.sls.staticresources.rest;

import org.jboss.resteasy.annotations.GZIP;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * User: jarisch
 * Date: 08.08.12
 * Time: 12:36
 */
@Path("/editor")
public interface IRestEditor {
    @GET
    @GZIP
    @NoCache
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    String getEditor(@PathParam("path") String path,
                     @QueryParam("lang") @DefaultValue("en") String language,
                     @QueryParam("loc") @DefaultValue("US") String location);
}
