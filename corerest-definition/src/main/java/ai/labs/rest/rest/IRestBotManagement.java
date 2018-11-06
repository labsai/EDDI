package ai.labs.rest.rest;

import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.InputData;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(value = "bot engine")
@Path("/botcrowd")
public interface IRestBotManagement {

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleConversationMemorySnapshot loadConversationMemory(@PathParam("intent") String intent,
                                                            @PathParam("userId") String userId,
                                                            @ApiParam(name = "returnDetailed", format = "boolean", example = "false")
                                                            @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                                            @ApiParam(name = "returnCurrentStepOnly", format = "boolean", example = "true")
                                                            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly);

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

    @POST
    @Path("/{intent}/{userId}/endConversation")
    Response endCurrentConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
