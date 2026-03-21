package ai.labs.eddi.engine.schedule;

import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST API for managing scheduled Agent triggers.
 * <p>
 * Provides CRUD operations for schedules, plus admin endpoints
 * for managing fire history and dead-lettered schedules.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/schedulestore/schedules")
@Tag(name = "11. Schedules", description = "Manage scheduled Agent triggers (heartbeat, cron jobs)")
public interface IRestScheduleStore {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List all schedules. Optional filter by agentId.")
    List<ScheduleConfiguration> readAllSchedules(@QueryParam("agentId") String agentId);

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
    @Operation(description = "Update an existing schedule.")
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
    @Operation(description = "Read fire history for a schedule.")
    List<ScheduleFireLog> readFireLogs(
            @PathParam("scheduleId") String scheduleId,
            @QueryParam("limit") @DefaultValue("20") int limit
    );

    // --- Admin ---

    @GET
    @Path("/admin/failed")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List all failed and dead-lettered fire logs across all schedules.")
    List<ScheduleFireLog> readFailedFires(@QueryParam("limit") @DefaultValue("50") int limit);

    @POST
    @Path("/{scheduleId}/retry")
    @Operation(description = "Re-queue a dead-lettered schedule for another fire attempt.")
    Response retryDeadLetter(@PathParam("scheduleId") String scheduleId);

    @POST
    @Path("/{scheduleId}/dismiss")
    @Operation(description = "Reset a dead-lettered schedule to PENDING without immediate retry.")
    Response dismissDeadLetter(@PathParam("scheduleId") String scheduleId);
}
