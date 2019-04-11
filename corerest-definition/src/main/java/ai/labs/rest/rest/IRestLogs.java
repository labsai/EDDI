package ai.labs.rest.rest;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment;
import io.swagger.annotations.Api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.util.List;

@Api(value = "Bot Engine -> Logs")
@Path("logs")
public interface IRestLogs {
    @GET
    @Path("/{environment}/{botId}")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId);

    @GET
    @Path("/{environment}/{botId}/{conversationId}")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @PathParam("conversationId") String conversationId);

    @GET
    @Path("/{environment}/{botId}/{conversationId}/{userId}")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @PathParam("conversationId") String conversationId,
                              @PathParam("userId") String userId);
}
