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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST surface for a group's standing workspace (I13): the backlog human PMs
 * feed, the cadences that pull from it, and the team's running metrics.
 *
 * @author ginccc
 */
@Path("/groupstore/groups/{groupId}/workspace")
@Tag(name = "13. Agent Groups", description = "Standing team workspace — backlog, cadences, metrics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestGroupWorkspace {

    /**
     * A backlog task as a human PM files it.
     *
     * @param priority
     *            higher runs earlier when a cadence pulls
     */
    record BacklogTaskRequest(String subject, String description, int priority) {
    }

    /**
     * A cadence definition. {@code cronExpression} is the standard 5-field form the
     * schedule store already speaks.
     */
    record CadenceRequest(String cronExpression, String timeZone, String inputTemplate,
            int maxBacklogTasksPerRun, Double maxCostPerRun) {
    }

    @GET
    @Operation(description = "Read the group's workspace (backlog, cadences, metrics). "
            + "Settles a finished cadence discussion on read, so metrics are current.")
    Response readWorkspace(@PathParam("groupId") String groupId);

    @GET
    @Path("/backlog")
    @Operation(description = "List the backlog tasks.")
    Response readBacklog(@PathParam("groupId") String groupId);

    @POST
    @Path("/backlog")
    @Operation(description = "Add a task to the backlog (cap: 200 — the backlog is a working set, not an archive).")
    Response addBacklogTask(@PathParam("groupId") String groupId, BacklogTaskRequest request);

    @POST
    @Path("/cadences")
    @Operation(description = "Add a cadence: a cron-scheduled pull of executable backlog tasks into a group discussion.")
    Response addCadence(@PathParam("groupId") String groupId, CadenceRequest request);

    @DELETE
    @Path("/cadences/{cadenceId}")
    @Operation(description = "Remove a cadence and its schedule.")
    Response deleteCadence(@PathParam("groupId") String groupId, @PathParam("cadenceId") String cadenceId);
}
