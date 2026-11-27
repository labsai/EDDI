/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The signed-in user's event stream: things that concern them, as they happen,
 * for every tab they have open. See {@code docs/user-events.md}.
 */
@Path("/me")
@Tag(name = "User Events", description = "The signed-in user's event stream")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver", "eddi-viewer"})
public interface IRestUserEvents {

    @GET
    @Path("/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Stream the events that concern the caller (SSE)",
               description = "A long-lived server-sent event stream. The first frame is `ready`; then one named event per thing that "
                       + "concerns the caller: `approval.pending`, `approval.decided`, `team.started`, `team.ended`, "
                       + "`team.waiting_for_human`, `job.finished`, `job.run_completed`, `job.failed`, `notification.created`. "
                       + "Payloads carry ids and kinds, never content: fetch details through the usual endpoints. A comment line "
                       + "every `eddi.user-events.heartbeat-interval` keeps it open. Events are hints: there is no replay, so on "
                       + "reconnect refetch what you show. One stream per tab; at most `eddi.user-events.max-connections-per-user` "
                       + "per user.")
    @APIResponse(responseCode = "200", description = "The stream")
    @APIResponse(responseCode = "401", description = "Not signed in (authorization on)")
    @APIResponse(responseCode = "404", description = "The stream is switched off (`eddi.user-events.enabled=false`)")
    @APIResponse(responseCode = "429", description = "Too many streams open for this user, or on this node")
    void stream(@Context SseEventSink eventSink, @Context Sse sse);
}
