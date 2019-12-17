package ai.labs.rest.restinterfaces;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Api(value = "Bot Engine -> Logs", authorizations = {@Authorization(value = "eddi_auth")})
@Path("logs")
public interface IRestLogs {

    @POST
    @ApiOperation(value = "Set logLevel of a specific packageName.")
    void setLogLevel(@QueryParam("packageName") String packageName, @QueryParam("logLevel") String logLevel);


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get error logs.")
    List<DatabaseLog> getLogs(@QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{environment}/{botId}")
    @ApiOperation(value = "Get error logs by bot.")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{environment}/{botId}/{conversationId}")
    @ApiOperation(value = "Get error logs by conversationId.")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @PathParam("conversationId") String conversationId,
                              @QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{environment}/{botId}/{conversationId}/{userId}")
    @ApiOperation(value = "Get error logs by userId.")
    List<DatabaseLog> getLogs(@PathParam("environment") Deployment.Environment environment,
                              @PathParam("botId") String botId,
                              @PathParam("conversationId") String conversationId,
                              @PathParam("userId") String userId,
                              @QueryParam("skip") @DefaultValue("0") Integer skip,
                              @QueryParam("limit") @DefaultValue("200") Integer limit);
}
