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
 * Cross-team handoff intake for a group's standing workspace: the inbox of
 * handoffs another team or agent filed under a REVIEW accept rule, and the
 * decision on each. See {@code docs/team-handoff.md}.
 * <p>
 * Shares the class-level path of {@link IRestGroupWorkspace}; the methods do
 * not overlap.
 */
@Path("/groupstore/groups/{groupId}/workspace")
@Tag(name = "Agents / Groups", description = "Standing team workspace — cross-team handoff intake")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestTeamHandoffs {

    /**
     * Optional edits made while accepting; any change makes the decision
     * {@code APPROVED_EDITED}.
     */
    record AcceptRequest(String subject, String description, Integer priority, String note) {
    }

    /**
     * @param reasonCode
     *            a short machine-readable reason (e.g. {@code out_of_scope},
     *            {@code duplicate}), recorded in the audit entry
     */
    record DeclineRequest(String reasonCode, String note) {
    }

    @GET
    @Path("/handoffs")
    @Operation(description = "The intake inbox (handoffs waiting for review) and the origin of every handed-off backlog task. Needs VIEW on the group.")
    Response readHandoffs(@PathParam("groupId") String groupId);

    @POST
    @Path("/handoffs/{handoffId}/accept")
    @Operation(description = "Accept a pending handoff onto the backlog, optionally editing its subject, description or priority. "
            + "Needs EDIT on the group. 409 when the backlog is full or already has that subject.")
    Response acceptHandoff(@PathParam("groupId") String groupId, @PathParam("handoffId") String handoffId, AcceptRequest request);

    @POST
    @Path("/handoffs/{handoffId}/decline")
    @Operation(description = "Decline a pending handoff. The same handoff is not accepted again for 30 days. Needs EDIT on the group.")
    Response declineHandoff(@PathParam("groupId") String groupId, @PathParam("handoffId") String handoffId, DeclineRequest request);

    @DELETE
    @Path("/backlog/{taskId}")
    @Operation(description = "Delete a backlog task (and its handoff origin). The backlog is capped at 200 tasks and has no other way to shrink. "
            + "Needs EDIT on the group. 409 while a cadence discussion is working the task.")
    Response deleteBacklogTask(@PathParam("groupId") String groupId, @PathParam("taskId") String taskId);
}
