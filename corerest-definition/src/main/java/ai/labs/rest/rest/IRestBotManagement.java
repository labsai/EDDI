package ai.labs.rest.rest;

import ai.labs.models.InputData;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;

@Api(value = "bot management")
@Path("/bot-management")
public interface IRestBotManagement {
    @POST
    @Path("/{intent}/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    void sayWithinContext(@PathParam("intent") String intent,
                          @PathParam("userId") String userId,
                          @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                          InputData inputData,
                          @Suspended final AsyncResponse response);
}
