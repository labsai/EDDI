package ai.labs.staticresources.rest;

import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */
@Path("/editor")
public interface IRestEditor {
    @GET
    @NoCache
    @Path("/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    String getEditor(@PathParam("path") String path,
                     @QueryParam("lang") @DefaultValue("en") String language,
                     @QueryParam("loc") @DefaultValue("US") String location);
}
