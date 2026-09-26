/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST API for managing scheduled Agent triggers.
 * <p>
 * Provides CRUD operations for schedules, plus admin endpoints for managing
 * fire history and dead-lettered schedules.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/schedulestore/schedules")
@Tag(name = "Operations / Schedules", description = "Scheduled agent triggers (heartbeat, cron)")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestScheduleStore {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List schedules, newest first. Optional filter by agentId. "
            + "Paged: 'limit' is capped at 1000 (default 500) and 'offset' skips that many rows. "
            + "A response holding exactly 'limit' entries may be truncated — request the next page to find out. "
            + "Human-in-the-loop approval-timeout schedules are excluded for non-admins by the query itself, "
            + "so they never occupy a slot in the page.")
    List<ScheduleConfiguration> readAllSchedules(@QueryParam("agentId") String agentId, @QueryParam("limit")
    @DefaultValue("500") int limit, @QueryParam("offset")
    @DefaultValue("0") int offset);

    @GET
    @Path("/{scheduleId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read a specific schedule by ID.")
    ScheduleConfiguration readSchedule(@PathParam("scheduleId") String scheduleId);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Create a new schedule. Returns the created schedule with generated ID.")
    Response createSchedule(ScheduleConfiguration schedule);

    @PUT
    @Path("/{scheduleId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update an existing schedule. Omitted metadata, tenantId and allowSelfScheduling keep their "
            + "stored values. A RAG-ingestion schedule cannot be updated here (409 — change the source's cron on the knowledge "
            + "base); a team-cadence schedule requires EDIT on its group.")
    Response updateSchedule(@PathParam("scheduleId") String scheduleId, ScheduleConfiguration schedule);

    @DELETE
    @Path("/{scheduleId}")
    @Operation(description = "Delete a schedule.")
    Response deleteSchedule(@PathParam("scheduleId") String scheduleId);

    @POST
    @Path("/{scheduleId}/enable")
    @Operation(description = "Enable a schedule.")
    Response enableSchedule(@PathParam("scheduleId") String scheduleId);

    @POST
    @Path("/{scheduleId}/disable")
    @Operation(description = "Disable a schedule.")
    Response disableSchedule(@PathParam("scheduleId") String scheduleId);

    @POST
    @Path("/{scheduleId}/fire")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Manually trigger a schedule fire immediately.")
    Response fireNow(@PathParam("scheduleId") String scheduleId);

    // --- Fire Log ---

    @GET
    @Path("/{scheduleId}/fires")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read fire history for a schedule, newest first. "
            + "'limit' must be > 0 and is capped at 500.")
    List<ScheduleFireLog> readFireLogs(@PathParam("scheduleId") String scheduleId, @QueryParam("limit")
    @DefaultValue("20") int limit);

    // --- Admin ---

    @GET
    @Path("/admin/failed")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List all failed and dead-lettered fire logs across all schedules, newest first. "
            + "'limit' must be > 0 and is capped at 500.")
    List<ScheduleFireLog> readFailedFires(@QueryParam("limit")
    @DefaultValue("50") int limit);

    @POST
    @Path("/{scheduleId}/retry")
    @Operation(description = "Re-queue a dead-lettered schedule for another fire attempt.")
    Response retryDeadLetter(@PathParam("scheduleId") String scheduleId);

    @POST
    @Path("/{scheduleId}/dismiss")
    @Operation(description = "Reset a dead-lettered schedule to PENDING without immediate retry, re-armed at its next "
            + "regular fire (a one-shot with nothing left to fire is disabled). 409 if the schedule is not dead-lettered.")
    @APIResponse(responseCode = "200", description = "Dismissed and re-armed.")
    @APIResponse(responseCode = "404", description = "No schedule with this id.")
    @APIResponse(responseCode = "409",
                 description = "The schedule is not dead-lettered (it recovered, was requeued or is running) — nothing was changed.")
    Response dismissDeadLetter(@PathParam("scheduleId") String scheduleId);
}
