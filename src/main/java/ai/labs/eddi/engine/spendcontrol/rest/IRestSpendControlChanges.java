/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * Spend-control changes waiting for a human
 * ({@code docs/spend-control-changes.md}): a change to the model price catalog
 * or a tenant quota that an MCP client asked for and that would loosen
 * spending. Administrators only — approving one writes a spending control with
 * the approver as actor. A non-read request an agent's own tool call makes here
 * is refused with 403 {@code AGENT_ORIGINATED}
 * ({@code AgentOriginatedRequestFilter}); there is no MCP tool to approve.
 *
 * @since 6.8.0
 */
@Path("/administration/spend-control-changes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Operations / Spend-Control Changes", description = "Loosening price and quota changes waiting for an administrator's approval")
@RolesAllowed("eddi-admin")
public interface IRestSpendControlChanges {

    /** The decider's note. */
    record DecisionRequest(String note) {
    }

    @GET
    @NoCache
    @Operation(summary = "List spend-control changes", description = "Newest first. status: PENDING (default), APPLIED, REJECTED, "
            + "WITHDRAWN, STALE, EXPIRED or ALL; limit 1..100 (default 20).")
    Response list(@QueryParam("status") String status, @QueryParam("limit") Integer limit);

    @GET
    @Path("/{id}")
    @NoCache
    @Operation(summary = "Read one spend-control change", description = "With the proposed document, what changes and which part loosens.")
    Response read(@PathParam("id") String id);

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "Approve and apply", description = "Applies the proposed catalog or quota with you as actor — only while the "
            + "control is still what the proposal was computed against (409 STALE otherwise; nothing applied).")
    Response approve(@PathParam("id") String id, DecisionRequest request);

    @POST
    @Path("/{id}/reject")
    @Operation(summary = "Reject", description = "Nothing is applied.")
    Response reject(@PathParam("id") String id, DecisionRequest request);
}
