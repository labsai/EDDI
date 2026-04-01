package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * REST API for log management: in-memory ring buffer, historical DB logs, and
 * live SSE streaming.
 *
 * @since 6.0.0
 */
@Path("/administration/logs")
@Tag(name = "Log Admin")
@RolesAllowed("eddi-admin")
public interface IRestLogAdmin {

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get recent in-memory logs", description = "Returns recent logs from the in-memory ring buffer.")
    @APIResponse(responseCode = "200", description = "List of log entries.")
    List<LogEntry> getRecentLogs(@Parameter(description = "Filter by agent ID.") @QueryParam("agentId") String agentId,
            @Parameter(description = "Filter by conversation ID.") @QueryParam("conversationId") String conversationId,
            @Parameter(description = "Minimum log level filter (TRACE, DEBUG, INFO, WARN, ERROR).") @QueryParam("level") @DefaultValue("INFO") String level,
            @QueryParam("limit") @DefaultValue("100") int limit);

    @GET
    @Path("/history")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get historical logs from database", description = "Returns historical logs from the database. "
            + "Survives restarts and works cross-instance.")
    @APIResponse(responseCode = "200", description = "List of historical log entries.")
    List<LogEntry> getHistoryLogs(@QueryParam("environment") Deployment.Environment environment,
            @Parameter(description = "Filter by agent ID.") @QueryParam("agentId") String agentId, @QueryParam("agentVersion") Integer agentVersion,
            @Parameter(description = "Filter by conversation ID.") @QueryParam("conversationId") String conversationId,
            @QueryParam("userId") String userId, @QueryParam("instanceId") String instanceId, @QueryParam("skip") @DefaultValue("0") Integer skip,
            @QueryParam("limit") @DefaultValue("100") Integer limit);

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Live-tail log stream via SSE", description = "Streams log entries as they occur in real-time.")
    void streamLogs(@QueryParam("agentId") String agentId, @QueryParam("conversationId") String conversationId,
            @Parameter(description = "Minimum log level filter.") @QueryParam("level") @DefaultValue("INFO") String level,
            @Context SseEventSink eventSink, @Context Sse sse);

    @GET
    @Path("/instance-id")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get instance identifier", description = "Returns the current EDDI instance identifier for cluster tracking.")
    @APIResponse(responseCode = "200", description = "Instance info.")
    InstanceInfo getInstanceId();

    record InstanceInfo(String instanceId) {
    }
}
