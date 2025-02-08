package ai.labs.eddi.engine.logging;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("logs")
public interface IRestLogs {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Get error logs.")
    List<DatabaseLog> getLogs(@QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{environment}/{botId}")
    @Operation(description = "Get error logs by bot.")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @QueryParam("botVersion") Integer botVersion,
                              @QueryParam("conversationId") String conversationId,
                              @QueryParam("userId") String userId,
                              @QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);

}

