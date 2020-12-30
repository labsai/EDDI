package ai.labs.resources.rest.config.output.keys;

import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

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
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) Output", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/outputstore/actions")
public interface IRestOutputActions {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputActions(@QueryParam("packageId") String packageId,
                                   @QueryParam("packageVersion") Integer packageVersion,
                                   @QueryParam("filter") @DefaultValue("") String filter,
                                   @QueryParam("limit") @DefaultValue("20") Integer limit);
}
