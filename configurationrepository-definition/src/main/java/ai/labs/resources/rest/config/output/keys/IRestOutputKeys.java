package ai.labs.resources.rest.config.output.keys;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
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
