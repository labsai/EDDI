/*
 * Copyright (c) 2016-2026 EDDI contributors
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
@Tag(name = "Coordinator Admin")
@RolesAllowed("eddi-admin")
public interface IRestCoordinatorAdmin {

    @GET
    @Path("/status")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get coordinator status", description = "Returns coordinator type, connection state, queue depths, and processing stats.")
    @APIResponse(responseCode = "200", description = "Coordinator status.")
    CoordinatorStatus getStatus();

    @GET
    @Path("/dead-letters")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List dead-letter entries", description = "Returns all dead-letter entries from the coordinator.")
    @APIResponse(responseCode = "200", description = "List of dead-letter entries.")
    List<DeadLetterEntry> getDeadLetters();

    @POST
    @Path("/dead-letters/{entryId}/replay")
    @Operation(summary = "Replay a dead-letter entry", description = "Re-injects a dead-letter entry into the processing pipeline.")
    @APIResponse(responseCode = "200", description = "Entry replayed.")
    @APIResponse(responseCode = "404", description = "Entry not found.")
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
    @Operation(summary = "Stream coordinator events via SSE", description = "Live-tail of coordinator events: task_submitted, "
            + "task_completed, task_failed, task_dead_lettered.")
    void streamEvents(@Context SseEventSink eventSink, @Context Sse sse);
}
