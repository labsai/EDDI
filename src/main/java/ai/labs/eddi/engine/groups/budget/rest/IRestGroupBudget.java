/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

/**
 * One team's monthly budget (docs/team-budgets.md). Reading needs VIEW on the
 * group; moving this month's cap is an {@code eddi-admin} act, as tenant quotas
 * are, because the cap is a spending lever.
 */
@Path("/groupstore/groups/{groupId}/budget")
@Tag(name = "Agents / Groups", description = "A team's monthly budget")
public interface IRestGroupBudget {

    /**
     * @param capUsd
     *            this month's cap, at least what is already spent
     * @param reason
     *            required, at most 1000 characters; kept in the audit ledger and
     *            the month's history, never shown to the team's members
     */
    record CapRequest(Double capUsd, String reason) {
    }

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
    @Operation(summary = "The team's budget this month", description = "Cap (base plus this month's adjustment), spent, remaining, "
            + "the reset instant and which limit (TEAM or TENANT) binds first. A group without a teamBudget returns spend only.")
    Response readBudget(@PathParam("groupId") String groupId);

    @GET
    @Path("/history")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
    @Operation(summary = "Past months", description = "Spend and adjustments of the most recent months, newest first, with the "
            + "adjustment history (actor, reason) of each. At most the retention window (eddi.groups.team-budget.retention-months, 13 by default).")
    Response readHistory(@PathParam("groupId") String groupId, @QueryParam("months")
    @DefaultValue("6") int months);

    @PUT
    @Path("/current-month")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Set this month's cap", description = "Sets this month's cap by adjusting the delta over the configured "
            + "base; the next month starts at the base again. 400 when the cap is not a positive number or is below what is "
            + "already spent, the reason is missing or over 1000 characters, or the group does not exist or has no teamBudget.")
    Response setCurrentMonthCap(@PathParam("groupId") String groupId, CapRequest request);
}
