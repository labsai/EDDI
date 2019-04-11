package ai.labs.resources.rest.properties;

import ai.labs.resources.rest.properties.model.Properties;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(value = "Bot Engine -> Properties")
@Path("/propertiesstore/properties")
public interface IRestPropertiesStore {
    String resourceURI = "eddi://ai.labs.properties/propertiesstore/properties/";

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    Properties readProperties(@PathParam("userId") String userId);

    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    Response mergeProperties(@PathParam("userId") String userId, Properties properties);

    @DELETE
    @Path("/{userId}")
    Response deleteProperties(@PathParam("userId") String userId);
}
