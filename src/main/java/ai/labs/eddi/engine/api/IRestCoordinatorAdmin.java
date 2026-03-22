package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * REST API for monitoring and administrating the conversation coordinator.
 *
 * <p>
 * Provides status introspection, live SSE event streaming, and dead-letter
 * management for agenth in-memory and NATS coordinators.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/administration/coordinator")
@Tag(name = "10. Coordinator Admin", description = "Monitor and manage conversation coordinators")
public interface IRestCoordinatorAdmin {

    @GET
    @Path("/status")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Get coordinator status: type, connection, queue depths, processing stats.")
    CoordinatorStatus getStatus();

    @GET
    @Path("/dead-letters")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List all dead-letter entries.")
    List<DeadLetterEntry> getDeadLetters();

    @POST
    @Path("/dead-letters/{entryId}/replay")
    @Operation(description = "Replay a dead-letter entry (re-inject into processing).")
    void replayDeadLetter(@PathParam("entryId") String entryId);

    @DELETE
    @Path("/dead-letters/{entryId}")
    @Operation(description = "Discard a single dead-letter entry.")
    void discardDeadLetter(@PathParam("entryId") String entryId);

    @DELETE
    @Path("/dead-letters")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Purge all dead-letter entries. Returns count of purged entries.")
    int purgeDeadLetters();

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(description = "SSE stream of live coordinator events (task_submitted, task_completed, task_failed, task_dead_lettered).")
    void streamEvents(@Context SseEventSink eventSink, @Context Sse sse);
}
