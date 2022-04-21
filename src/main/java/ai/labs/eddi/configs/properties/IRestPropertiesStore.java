package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import org.eclipse.microprofile.openapi.annotations.Operation;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

// @Api(value = "Bot Engine -> Properties", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/propertiesstore/properties")
public interface IRestPropertiesStore {
    String resourceURI = "eddi://ai.labs.properties/propertiesstore/properties/";

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read properties.")
    Properties readProperties(@PathParam("userId") String userId);

    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Merge properties.")
    Response mergeProperties(@PathParam("userId") String userId, Properties properties);

    @DELETE
    @Path("/{userId}")
    @Operation(description = "Delete properties.")
    Response deleteProperties(@PathParam("userId") String userId);
}
