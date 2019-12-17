package ai.labs.rest.restinterfaces;

import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.InputData;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Api(value = "Bot Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/managedbots")
public interface IRestBotManagement {

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read conversation.")
    SimpleConversationMemorySnapshot loadConversationMemory(@PathParam("intent") String intent,
                                                            @PathParam("userId") String userId,
                                                            @ApiParam(name = "returnDetailed", format = "boolean", example = "false")
                                                            @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                                                            @ApiParam(name = "returnCurrentStepOnly", format = "boolean", example = "true")
                                                            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                            @QueryParam("returningFields") List<String> returningFields);

    @POST
    @Path("/{intent}/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Talk to bot with context.")
    void sayWithinContext(@PathParam("intent") String intent,
                          @PathParam("userId") String userId,
                          @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
                          @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
                          @QueryParam("returningFields") List<String> returningFields,
                          InputData inputData,
                          @Suspended final AsyncResponse response);

    @POST
    @Path("/{intent}/{userId}/endConversation")
    @ApiOperation(value = "End conversation.")
    Response endCurrentConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
