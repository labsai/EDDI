package io.sls.resources.rest.output.keys;

import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * User: jarisch
 * Date: 26.11.12
 * Time: 16:16
 */
@Path("/outputKeys")
public interface IRestOutputKeys {
    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputKeys(@QueryParam("packageId") String packageId,
                                @QueryParam("packageVersion") Integer packageVersion,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);
}
