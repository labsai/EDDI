package ai.labs.resources.rest.output.keys;

import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
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
