package ai.labs.eddi.configs.regulardictionary;

import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (1) Regular Dictionary", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/expressions")
public interface IRestExpression {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read expressions.")
    List<String> readExpressions(@QueryParam("packageId") String packageId,
                                 @QueryParam("packageVersion") Integer packageVersion,
                                 @QueryParam("filter") @DefaultValue("") String filter,
                                 @QueryParam("limit") @DefaultValue("20") Integer limit);
}
