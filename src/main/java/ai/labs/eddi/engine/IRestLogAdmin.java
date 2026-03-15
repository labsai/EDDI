package ai.labs.eddi.engine;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.util.List;

/**
 * REST interface for log administration — real-time streaming and historical queries.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("administration/logs")
@Tag(name = "09. Log Administration", description = "Real-time log streaming and historical log queries")
public interface IRestLogAdmin {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Get recent logs from the in-memory ring buffer.")
    List<LogEntry> getRecentLogs(
            @QueryParam("botId") String botId,
            @QueryParam("conversationId") String conversationId,
            @QueryParam("level") String level,
            @QueryParam("limit") @DefaultValue("200") int limit);

    @GET
    @Path("/history")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Get historical logs from the database (survives restarts, cross-instance).")
    List<DatabaseLog> getHistoryLogs(
            @QueryParam("environment") Deployment.Environment environment,
            @QueryParam("botId") String botId,
            @QueryParam("botVersion") Integer botVersion,
            @QueryParam("conversationId") String conversationId,
            @QueryParam("userId") String userId,
            @QueryParam("instanceId") String instanceId,
            @QueryParam("skip") @DefaultValue("0") Integer skip,
            @QueryParam("limit") @DefaultValue("200") Integer limit);

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(description = "Live-tail log stream via SSE. Pushes log entries as they occur.")
    void streamLogs(
            @QueryParam("botId") String botId,
            @QueryParam("conversationId") String conversationId,
            @QueryParam("level") String level,
            @Context SseEventSink eventSink,
            @Context Sse sse);

    @GET
    @Path("/instance")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Get the current EDDI instance identifier.")
    InstanceInfo getInstanceId();

    record InstanceInfo(String instanceId) {}
}
