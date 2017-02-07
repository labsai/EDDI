package ai.labs.resources.rest.output.keys;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/outputKeys")
public interface IRestOutputKeys {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputKeys(@QueryParam("packageId") String packageId,
                                @QueryParam("packageVersion") Integer packageVersion,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);
}
