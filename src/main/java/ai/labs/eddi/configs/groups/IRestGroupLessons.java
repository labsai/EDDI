/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST surface for a team's retro lessons: read its own, read what other teams
 * share with it, see what consolidation removed, and schedule or run the
 * consolidation job. Sharing and consolidation are off until the group's
 * configuration carries a {@code lessonConfig}.
 *
 * @since 6.8.0
 */
@Path("/groupstore/groups/{groupId}/lessons")
@Tag(name = "Agents / Groups", description = "Standing team workspace — backlog, cadences, metrics, outcomes, lessons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestGroupLessons {

    /**
     * The cron schedule of the consolidation job.
     *
     * @param cronExpression
     *            5-field Unix cron, e.g. {@code 0 3 * * 1}
     * @param timeZone
     *            IANA zone id; default UTC
     */
    record ScheduleRequest(String cronExpression, String timeZone) {
    }

    @GET
    @Operation(description = "The team's own retro lessons, newest first. limit defaults to 100 and is capped at 500.")
    Response list(@PathParam("groupId") String groupId, @QueryParam("limit") Integer limit);

    @GET
    @Path("/shared")
    @Operation(description = "Lessons other teams share with this one: only where this team lists the owner in lessonConfig.readFrom and "
            + "the owner lists this team in lessonConfig.shareWith. 'notShared' names the owners that did not grant.")
    Response shared(@PathParam("groupId") String groupId);

    @GET
    @Path("/history")
    @Operation(description = "Lessons consolidation removed (merged or retired), with their text, reason and cited outcomes; newest first. limit defaults to 100 and is capped at 500.")
    Response history(@PathParam("groupId") String groupId, @QueryParam("limit") Integer limit);

    @GET
    @Path("/consolidation")
    @Operation(description = "Whether consolidation is configured and scheduled for the team.")
    Response consolidation(@PathParam("groupId") String groupId);

    @PUT
    @Path("/consolidation/schedule")
    @Operation(description = "Schedule (or reschedule) the consolidation job. Needs lessonConfig.consolidation on the group.")
    Response schedule(@PathParam("groupId") String groupId, ScheduleRequest request);

    @DELETE
    @Path("/consolidation/schedule")
    @Operation(description = "Remove the consolidation schedule.")
    Response unschedule(@PathParam("groupId") String groupId);

    @POST
    @Path("/consolidation/run")
    @Operation(description = "Run consolidation now. dryRun=true calls the model and reports what it would merge or retire, changing nothing "
            + "(it still spends, up to maxCostPerRun).")
    Response run(@PathParam("groupId") String groupId, @QueryParam("dryRun") Boolean dryRun);
}
