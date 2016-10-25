package io.sls.staticresources.rest;

import org.jboss.resteasy.annotations.GZIP;
import org.jboss.resteasy.annotations.cache.Cache;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.io.File;

/**
 * @author ginccc
 */
@Path("/text")
public interface IRestTextResource {
    int ONE_YEAR_IN_SECONDS = 60 * 60 * 24 * 365;

    @GET
    @GZIP
    @Cache(maxAge = ONE_YEAR_IN_SECONDS, sMaxAge = ONE_YEAR_IN_SECONDS, isPrivate = true)
    @Path("/{path:.*}")
    File getStaticResource(@PathParam("path") String path);
}
