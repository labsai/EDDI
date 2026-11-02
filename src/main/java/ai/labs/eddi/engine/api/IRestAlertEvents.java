/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * The alert inbox: every fired alert with its delivery status per target.
 *
 * <h3>Who sees what</h3> The {@code /ratings} rule: administrators see every
 * agent's events; anyone else names an {@code agentId} and holds {@code EDIT}
 * on it ({@code AGENT_ID_REQUIRED} otherwise). An event names no user. Of the
 * conversation it carries only a rating comment, cut to 280 characters, and an
 * {@code OUTPUT_MATCH} value, cut to 280 characters and present only when the
 * rule sets {@code includeValue} and the agent version has conversation review
 * on. A webhook target appears by host only. A digest of a rule over several
 * agents names no agent and is an administrator's.
 */
@Path("/alerts/events")
@Tag(name = "Alerts", description = "Alert rules and the alert inbox")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAlertEvents {

    record EventPage(long total, int index, int limit, List<AlertEvent> events) {
    }

    /** Ids to acknowledge; when absent, the query filter selects the events. */
    record AcknowledgeRequest(List<String> ids) {
    }

    record AcknowledgeResult(long acknowledged) {
    }

    record UnacknowledgedCount(long count) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List alert events", description = "Newest first. agentId is required unless the caller is an administrator; "
            + "acknowledged=false lists the inbox.")
    @APIResponse(responseCode = "200", description = "One page of events.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, or a parameter could not be read.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    EventPage listEvents(@BeanParam AlertEventQueryParams query,
                         @QueryParam("index")
                         @DefaultValue("0") Integer index,
                         @QueryParam("limit")
                         @DefaultValue("20") Integer limit);

    @GET
    @Path("/unacknowledged-count")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Count unacknowledged alert events", description = "Within the caller's scope. Backs the Manager's Alerts badge.")
    @APIResponse(responseCode = "200", description = "The count.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    UnacknowledgedCount unacknowledgedCount(@QueryParam("agentId") String agentId);

    @GET
    @Path("/{eventId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read an alert event")
    @APIResponse(responseCode = "200", description = "The event.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the event's agent.")
    @APIResponse(responseCode = "404", description = "No such event.")
    AlertEvent readEvent(@PathParam("eventId") String eventId);

    @POST
    @Path("/{eventId}/acknowledge")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Acknowledge an alert event", description = "Records the caller and the time. Acknowledging twice changes nothing.")
    @APIResponse(responseCode = "200", description = "The event.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the event's agent.")
    @APIResponse(responseCode = "404", description = "No such event.")
    AlertEvent acknowledgeEvent(@PathParam("eventId") String eventId);

    @POST
    @Path("/acknowledge")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Acknowledge alert events in bulk",
               description = "The listed ids (each checked like a single acknowledgement), or — when ids is left out — every "
                       + "unacknowledged event matching the filter (same filters and scoping as the list). A present ids list is the "
                       + "selector: an empty list or an empty entry is refused, never read as \"everything\".")
    @APIResponse(responseCode = "200", description = "How many were acknowledged.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, an empty ids list or entry, or a parameter "
            + "could not be read (INVALID_PARAMETER).")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on an event's agent.")
    AcknowledgeResult acknowledgeEvents(@BeanParam AlertEventQueryParams query, AcknowledgeRequest request);

    @POST
    @Path("/{eventId}/retry")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Retry the failed deliveries of an alert event", description = "Puts every failed target back to pending, due now; "
            + "the delivery runs in the background.")
    @APIResponse(responseCode = "200", description = "The event, its failed targets pending again.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the event's agent.")
    @APIResponse(responseCode = "404", description = "No such event.")
    @APIResponse(responseCode = "409", description = "No delivery of the event has failed (NOTHING_TO_RETRY).")
    AlertEvent retryEvent(@PathParam("eventId") String eventId);
}
