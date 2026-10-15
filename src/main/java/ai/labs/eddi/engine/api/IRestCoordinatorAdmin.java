/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * REST API for monitoring and administrating the conversation coordinator.
 *
 * @since 6.0.0
 */
@Path("/administration/coordinator")
@Tag(name = "Operations / Coordinator", description = "Conversation coordinator monitoring and dead letters")
@RolesAllowed("eddi-admin")
public interface IRestCoordinatorAdmin {

    @GET
    @Path("/status")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get coordinator status", description = "Returns coordinator type, connection state, queue depths, and processing stats. "
            + "In cluster mode also nodeId and a cluster section (members, leases held, NATS state, degraded); "
            + "scope=cluster adds every member's queue depths.")
    @APIResponse(responseCode = "200", description = "Coordinator status.")
    CoordinatorStatus getStatus(@QueryParam("scope") String scope);

    @GET
    @Path("/dead-letters")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List dead-letter entries", description = "Dead-letter entries, oldest first. In cluster mode the shared stream "
            + "every node reads; page with limit and after (the last id of the previous page).")
    @APIResponse(responseCode = "200", description = "List of dead-letter entries.")
    List<DeadLetterEntry> getDeadLetters(@QueryParam("limit")
    @DefaultValue("100") int limit, @QueryParam("after") String after);

    @POST
    @Path("/dead-letters/{entryId}/replay")
    @Operation(summary = "Replay a dead-letter entry", description = "Submits the failed turn's captured input as a NEW turn of its "
            + "conversation (context replayOf=<id>), as the calling admin, and removes the entry once the turn was accepted. "
            + "The failed task itself is never re-run.")
    @APIResponse(responseCode = "204", description = "Replay submitted; entry removed.")
    @APIResponse(responseCode = "404", description = "Entry not found.")
    @APIResponse(responseCode = "409", description = "Not replayable (no captured input) or the conversation cannot take a turn; "
            + "the entry is kept.")
    void replayDeadLetter(@PathParam("entryId") String entryId);

    @DELETE
    @Path("/dead-letters/{entryId}")
    @Operation(summary = "Discard a dead-letter entry", description = "Permanently removes a single dead-letter entry.")
    @APIResponse(responseCode = "204", description = "Entry discarded.")
    @APIResponse(responseCode = "404", description = "Entry not found.")
    void discardDeadLetter(@PathParam("entryId") String entryId);

    @DELETE
    @Path("/dead-letters")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Purge all dead-letter entries",
               description = "Permanently removes all dead-letter entries. Returns count of purged entries.")
    @APIResponse(responseCode = "200", description = "Count of purged entries.")
    int purgeDeadLetters();

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Stream coordinator status via SSE", description = "Emits a 'status' event — the same object as GET /status — "
            + "on connect and every 2 seconds.")
    void streamEvents(@Context SseEventSink eventSink, @Context Sse sse);
}
