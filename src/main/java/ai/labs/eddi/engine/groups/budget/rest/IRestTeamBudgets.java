/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * Budgets across teams, and moving cap between them (docs/team-budgets.md).
 */
@Path("/administration/team-budgets")
@Tag(name = "Operations / Team Budgets", description = "Per-team monthly budgets across teams")
public interface IRestTeamBudgets {

    /** At most this many ids per listing. */
    int MAX_LISTED_GROUPS = 50;

    /**
     * @param reason
     *            required, at most 1000 characters
     */
    record TransferRequest(String fromGroupId, String toGroupId, Double amountUsd, String reason) {
    }

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
    @Operation(summary = "Budgets of several teams", description = "The snapshot of each listed group the caller may view (comma "
            + "separated groupIds, at most 50 or the request is a 400; groups the caller may not view are left out, not refused), plus the tenant's remaining monthly budget.")
    Response listBudgets(@QueryParam("groupIds") String groupIds);

    @POST
    @Path("/transfers")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Move cap from one team to another", description = "Moves part of this month's cap. The source must keep "
            + "a cap at or above what it has spent. An Idempotency-Key header makes a retry a no-op. Both teams need a teamBudget.")
    Response transfer(@HeaderParam("Idempotency-Key") String idempotencyKey, TransferRequest request);
}
