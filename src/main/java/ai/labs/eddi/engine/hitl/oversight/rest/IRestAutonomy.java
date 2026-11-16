/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Earned autonomy: which approval categories are ready to stop asking, which
 * are suspended, and the human actions on them. Categories contain {@code :}
 * and {@code /}, so they are a query parameter, never a path segment.
 *
 * @since 6.7.0
 */
@Path("/hitl/autonomy")
@Tag(name = "HITL Oversight", description = "Approval analytics, sampled audit and earned autonomy")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
public interface IRestAutonomy {

    /** Apply a recommendation. */
    record ApplyRequest(String recommendationId, Integer expectedAgentVersion) {
    }

    /** Dismiss a recommendation. */
    record DismissRequest(String recommendationId, String note) {
    }

    /** Suspend or lift. */
    record NoteRequest(String note) {
    }

    /** The categories with stored state. */
    record CategoryList(List<AutonomyService.CategoryView> categories) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List earned-autonomy states",
               description = "Recommendations and suspensions, with each category's tier: GATED, READY, PROMOTED or SUSPENDED.")
    CategoryList listStates(@QueryParam("agentId") String agentId);

    @GET
    @Path("/{agentId}/category")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read one category", description = "Its state and its evidence evaluated now against the newest agent version.")
    AutonomyService.CategoryView readCategory(@PathParam("agentId") String agentId, @QueryParam("category") String category);

    // apply, dismiss and lift remove a gate: never eddi-approver alone
    // (OversightAccess#requireEditor).
    @POST
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Path("/{agentId}/apply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Apply a recommendation",
               description = "Re-checks the evidence, writes a new agent version (not deployed). 409 AGENT_CHANGED / EVIDENCE_CHANGED / "
                       + "RECOMMENDATION_CHANGED when what you reviewed is no longer current.")
    AutonomyService.CategoryView apply(@PathParam("agentId") String agentId, @QueryParam("category") String category, ApplyRequest request);

    @POST
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Path("/{agentId}/dismiss")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Dismiss a recommendation", description = "A note is required.")
    AutonomyService.CategoryView dismiss(@PathParam("agentId") String agentId, @QueryParam("category") String category,
                                         DismissRequest request);

    @POST
    @Path("/{agentId}/suspend")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Suspend a tool category", description = "Its calls need approval again until lifted. A note is required.")
    AutonomyService.CategoryView suspend(@PathParam("agentId") String agentId, @QueryParam("category") String category,
                                         NoteRequest request);

    @POST
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Path("/{agentId}/lift")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Lift a suspension", description = "The category runs without approval again. A note is required.")
    AutonomyService.CategoryView lift(@PathParam("agentId") String agentId, @QueryParam("category") String category, NoteRequest request);
}
