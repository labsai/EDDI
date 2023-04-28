package ai.labs.eddi.configs.output.keys;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) Output", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/outputstore/actions")
@Tag(name = "05. Output", description = "lifecycle extension for package")
public interface IRestOutputActions {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputActions(@QueryParam("packageId") String packageId,
                                   @QueryParam("packageVersion") Integer packageVersion,
                                   @QueryParam("filter") @DefaultValue("") String filter,
                                   @QueryParam("limit") @DefaultValue("20") Integer limit);
}
